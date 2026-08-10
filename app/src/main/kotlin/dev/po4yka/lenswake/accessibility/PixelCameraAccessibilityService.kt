package dev.po4yka.lenswake.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

sealed interface AccessibilitySnapshotResult {
    data class Available(
        val nodes: List<UiNodeSnapshot>,
        val truncated: Boolean,
    ) : AccessibilitySnapshotResult

    data object ServiceDisconnected : AccessibilitySnapshotResult

    data object NoActiveWindow : AccessibilitySnapshotResult

    data object PixelCameraNotForeground : AccessibilitySnapshotResult

    /** The active-window node could not be refreshed, so its contents are not safe to inspect. */
    data object RootRefreshFailed : AccessibilitySnapshotResult
}

sealed interface AccessibilityDispatchResult {
    /** Android reported that ACTION_CLICK was dispatched, not that the UI postcondition changed. */
    data object SemanticActionDispatched : AccessibilityDispatchResult

    /** Android accepted a gesture, not that the target was activated. */
    data object GestureSubmitted : AccessibilityDispatchResult

    data object ServiceDisconnected : AccessibilityDispatchResult

    /** The active-window node could not be refreshed, so a path must not be resolved against it. */
    data object RootRefreshFailed : AccessibilityDispatchResult

    data object TargetNotFound : AccessibilityDispatchResult

    data object TargetNotEligible : AccessibilityDispatchResult

    data object GestureRejected : AccessibilityDispatchResult
}

/**
 * Narrow Pixel Camera accessibility boundary. It creates ephemeral platform-neutral snapshots and
 * never persists an AccessibilityNodeInfo or a UI tree.
 */
class PixelCameraAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        PixelCameraAccessibilityRuntime.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != PIXEL_CAMERA_PACKAGE) return
        // Events only invalidate observations. State inference always reads a fresh bounded snapshot.
        PixelCameraAccessibilityRuntime.markCameraEvent(event.eventTime)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        PixelCameraAccessibilityRuntime.detach(this)
        super.onDestroy()
    }

    internal fun readSnapshot(): AccessibilitySnapshotResult {
        val root = rootInActiveWindow ?: return AccessibilitySnapshotResult.NoActiveWindow
        if (!root.refreshSafely()) return AccessibilitySnapshotResult.RootRefreshFailed
        if (root.packageName?.toString() != PIXEL_CAMERA_PACKAGE) {
            return AccessibilitySnapshotResult.PixelCameraNotForeground
        }
        val metrics = resources.displayMetrics
        val nodes = ArrayList<UiNodeSnapshot>(64)
        val truncated = collectSnapshots(
            node = root,
            path = ROOT_PATH,
            depth = 0,
            screenWidth = metrics.widthPixels.coerceAtLeast(1),
            screenHeight = metrics.heightPixels.coerceAtLeast(1),
            destination = nodes,
        )
        return AccessibilitySnapshotResult.Available(nodes, truncated)
    }

    internal fun dispatchClick(nodePath: String): AccessibilityDispatchResult {
        val root = rootInActiveWindow ?: return AccessibilityDispatchResult.TargetNotFound
        if (!root.refreshSafely()) return AccessibilityDispatchResult.RootRefreshFailed
        if (root.packageName?.toString() != PIXEL_CAMERA_PACKAGE) {
            return AccessibilityDispatchResult.TargetNotEligible
        }
        val target = resolvePath(root, nodePath) ?: return AccessibilityDispatchResult.TargetNotFound
        if (
            target.packageName?.toString() != PIXEL_CAMERA_PACKAGE ||
            !target.isVisibleToUser ||
            !target.isEnabled
        ) {
            return AccessibilityDispatchResult.TargetNotEligible
        }
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return AccessibilityDispatchResult.SemanticActionDispatched
        }

        val bounds = Rect().also(target::getBoundsInScreen)
        if (bounds.isEmpty) return AccessibilityDispatchResult.TargetNotEligible
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
            .build()
        return if (dispatchGesture(gesture, null, null)) {
            AccessibilityDispatchResult.GestureSubmitted
        } else {
            AccessibilityDispatchResult.GestureRejected
        }
    }

    private fun collectSnapshots(
        node: AccessibilityNodeInfo,
        path: String,
        depth: Int,
        screenWidth: Int,
        screenHeight: Int,
        destination: MutableList<UiNodeSnapshot>,
    ): Boolean {
        if (destination.size >= MAX_NODE_COUNT || depth > MAX_DEPTH) return true
        if (node.packageName?.toString() == PIXEL_CAMERA_PACKAGE) {
            destination += node.toSnapshot(path, screenWidth, screenHeight)
        }
        var truncated = false
        for (index in 0 until node.childCount) {
            if (destination.size >= MAX_NODE_COUNT) return true
            val child = node.getChild(index) ?: continue
            if (
                collectSnapshots(
                    node = child,
                    path = "$path/$index",
                    depth = depth + 1,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    destination = destination,
                )
            ) {
                truncated = true
            }
        }
        return truncated
    }

    private fun AccessibilityNodeInfo.toSnapshot(
        path: String,
        screenWidth: Int,
        screenHeight: Int,
    ): UiNodeSnapshot {
        val bounds = Rect().also(::getBoundsInScreen)
        val left = (bounds.left.toFloat() / screenWidth).coerceIn(0f, 1f)
        val top = (bounds.top.toFloat() / screenHeight).coerceIn(0f, 1f)
        val right = (bounds.right.toFloat() / screenWidth).coerceIn(0f, 1f)
        val bottom = (bounds.bottom.toFloat() / screenHeight).coerceIn(0f, 1f)
        return UiNodeSnapshot(
            id = path,
            packageName = packageName?.toString(),
            resourceId = viewIdResourceName,
            role = className?.toString(),
            contentDescription = contentDescription?.toString(),
            text = text?.toString(),
            bounds = if (bounds.isEmpty || left >= right || top >= bottom) {
                null
            } else {
                NormalizedBounds(left, top, right, bottom)
            },
            visible = isVisibleToUser,
            clickable = isClickable,
            selected = isSelected,
            checkable = isCheckable,
            checked = if (!isCheckable) {
                null
            } else {
                when (getChecked()) {
                    AccessibilityNodeInfo.CHECKED_STATE_TRUE -> true
                    AccessibilityNodeInfo.CHECKED_STATE_FALSE -> false
                    AccessibilityNodeInfo.CHECKED_STATE_PARTIAL -> null
                    else -> null
                }
            },
            enabled = isEnabled,
        )
    }

    private fun resolvePath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        val segments = path.split('/')
        if (segments.firstOrNull() != ROOT_PATH) return null
        var current = root
        for (segment in segments.drop(1)) {
            val index = segment.toIntOrNull() ?: return null
            if (index !in 0 until current.childCount) return null
            current = current.getChild(index) ?: return null
        }
        return current
    }

    private fun AccessibilityNodeInfo.refreshSafely(): Boolean = try {
        refresh()
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val ROOT_PATH = "root"
        const val MAX_NODE_COUNT = 512
        const val MAX_DEPTH = 32
        const val GESTURE_DURATION_MS = 50L
    }
}

/** Runtime-only service access for the application adapter; it intentionally holds a weak reference. */
object PixelCameraAccessibilityRuntime {
    private val serviceReference = AtomicReference<WeakReference<PixelCameraAccessibilityService>?>()
    private val mutableConnectionState = MutableStateFlow(false)

    val connectionState: StateFlow<Boolean> = mutableConnectionState.asStateFlow()

    @Volatile
    var lastCameraEventAtMillis: Long? = null
        private set

    val isConnected: Boolean
        get() = connectionState.value

    internal fun attach(service: PixelCameraAccessibilityService) {
        serviceReference.set(WeakReference(service))
        mutableConnectionState.value = true
    }

    internal fun detach(service: PixelCameraAccessibilityService) {
        val current = serviceReference.get()?.get()
        if (current === service) {
            serviceReference.set(null)
            mutableConnectionState.value = false
        }
    }

    internal fun markCameraEvent(eventTime: Long) {
        lastCameraEventAtMillis = eventTime
    }

    suspend fun snapshot(): AccessibilitySnapshotResult = withContext(Dispatchers.Main.immediate) {
        serviceReference.get()?.get()?.readSnapshot()
            ?: AccessibilitySnapshotResult.ServiceDisconnected
    }

    suspend fun dispatchClick(nodePath: String): AccessibilityDispatchResult =
        withContext(Dispatchers.Main.immediate) {
            serviceReference.get()?.get()?.dispatchClick(nodePath)
                ?: AccessibilityDispatchResult.ServiceDisconnected
        }
}
