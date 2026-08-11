package dev.po4yka.lenswake.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.NormalizedPoint
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

    /** An accessibility node could not be refreshed, so the snapshot is unsafe to inspect. */
    data object RefreshFailed : AccessibilitySnapshotResult
}

sealed interface AccessibilityDispatchResult {
    /** Android reported that ACTION_CLICK was dispatched, not that the UI postcondition changed. */
    data object SemanticActionDispatched : AccessibilityDispatchResult

    /** Android accepted a gesture, not that the target was activated. */
    data object GestureSubmitted : AccessibilityDispatchResult

    /** Android accepted a global navigation action, not that the UI postcondition changed. */
    data object GlobalActionDispatched : AccessibilityDispatchResult

    data object ServiceDisconnected : AccessibilityDispatchResult

    /** An accessibility node could not be refreshed, so a path must not be resolved against it. */
    data object RefreshFailed : AccessibilityDispatchResult

    data object TargetNotFound : AccessibilityDispatchResult

    data object TargetNotEligible : AccessibilityDispatchResult

    /** The old path now resolves to a different interaction target. */
    data object TargetIdentityChanged : AccessibilityDispatchResult

    data object GestureRejected : AccessibilityDispatchResult

    data object GlobalActionRejected : AccessibilityDispatchResult
}

internal fun UiNodeSnapshot.hasSameInteractionIdentityAs(expected: UiNodeSnapshot): Boolean =
    packageName == expected.packageName &&
        resourceId == expected.resourceId &&
        role == expected.role &&
        contentDescription == expected.contentDescription &&
        text == expected.text &&
        bounds == expected.bounds &&
        visible == expected.visible &&
        clickable == expected.clickable &&
        selected == expected.selected &&
        checkable == expected.checkable &&
        (!checkable || checked == expected.checked) &&
        enabled == expected.enabled

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
        if (!root.refreshSafely()) return AccessibilitySnapshotResult.RefreshFailed
        if (root.packageName?.toString() != PIXEL_CAMERA_PACKAGE) {
            return AccessibilitySnapshotResult.PixelCameraNotForeground
        }
        val metrics = resources.displayMetrics
        val nodes = ArrayList<UiNodeSnapshot>(64)
        val collection = AccessibilityTreeInspector.collectSnapshots(
            node = root,
            path = ROOT_PATH,
            depth = 0,
            screenWidth = metrics.widthPixels.coerceAtLeast(1),
            screenHeight = metrics.heightPixels.coerceAtLeast(1),
            destination = nodes,
            nodeIsFresh = true,
        )
        return when (collection) {
            SnapshotCollection.RefreshFailed -> AccessibilitySnapshotResult.RefreshFailed
            SnapshotCollection.Complete,
            SnapshotCollection.Truncated,
            -> AccessibilitySnapshotResult.Available(
                nodes = nodes,
                truncated = collection == SnapshotCollection.Truncated,
            )
        }
    }

    internal fun dispatchClick(expectedNode: UiNodeSnapshot): AccessibilityDispatchResult {
        val metrics = resources.displayMetrics
        return when (
            val resolution = AccessibilityTreeInspector.resolveExpectedNode(
                root = rootInActiveWindow,
                expected = expectedNode,
                screenWidth = metrics.widthPixels.coerceAtLeast(1),
                screenHeight = metrics.heightPixels.coerceAtLeast(1),
            )
        ) {
            is ExpectedNodeResolution.Found -> dispatchClick(resolution.node)
            is ExpectedNodeResolution.Rejected -> resolution.result
        }
    }

    private fun dispatchClick(target: AccessibilityNodeInfo): AccessibilityDispatchResult =
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AccessibilityDispatchResult.SemanticActionDispatched
        } else {
            val bounds = Rect().also(target::getBoundsInScreen)
            if (bounds.isEmpty) {
                AccessibilityDispatchResult.TargetNotEligible
            } else {
                dispatchTap(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }

    internal fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult {
        val root = rootInActiveWindow ?: return AccessibilityDispatchResult.TargetNotFound
        if (!root.refreshSafely()) return AccessibilityDispatchResult.RefreshFailed
        if (root.packageName?.toString() != PIXEL_CAMERA_PACKAGE) {
            return AccessibilityDispatchResult.TargetNotEligible
        }
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels.coerceAtLeast(1) - 1).toFloat()
        val maxY = (metrics.heightPixels.coerceAtLeast(1) - 1).toFloat()
        return dispatchTap(
            x = (point.x * metrics.widthPixels).coerceIn(0f, maxX),
            y = (point.y * metrics.heightPixels).coerceIn(0f, maxY),
        )
    }

    private fun dispatchTap(x: Float, y: Float): AccessibilityDispatchResult {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
            .build()
        return if (dispatchGesture(gesture, null, null)) {
            AccessibilityDispatchResult.GestureSubmitted
        } else {
            AccessibilityDispatchResult.GestureRejected
        }
    }

    internal fun dispatchGlobalBack(expectedPicker: UiNodeSnapshot): AccessibilityDispatchResult {
        val metrics = resources.displayMetrics
        when (
            val resolution = AccessibilityTreeInspector.resolveExpectedNode(
                root = rootInActiveWindow,
                expected = expectedPicker,
                screenWidth = metrics.widthPixels.coerceAtLeast(1),
                screenHeight = metrics.heightPixels.coerceAtLeast(1),
            )
        ) {
            is ExpectedNodeResolution.Found -> Unit
            is ExpectedNodeResolution.Rejected -> return resolution.result
        }
        return if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            AccessibilityDispatchResult.GlobalActionDispatched
        } else {
            AccessibilityDispatchResult.GlobalActionRejected
        }
    }

    private companion object {
        const val ROOT_PATH = "root"
        const val GESTURE_DURATION_MS = 50L
    }
}

private object AccessibilityTreeInspector {
    fun collectSnapshots(
        node: AccessibilityNodeInfo,
        path: String,
        depth: Int,
        screenWidth: Int,
        screenHeight: Int,
        destination: MutableList<UiNodeSnapshot>,
        nodeIsFresh: Boolean = false,
    ): SnapshotCollection = when {
        destination.size >= MAX_NODE_COUNT || depth > MAX_DEPTH -> SnapshotCollection.Truncated
        !nodeIsFresh && !node.refreshSafely() -> SnapshotCollection.RefreshFailed
        else -> {
            if (node.packageName?.toString() == PIXEL_CAMERA_PACKAGE) {
                destination += node.toSnapshot(path, screenWidth, screenHeight)
            }
            collectChildren(node, path, depth, screenWidth, screenHeight, destination)
        }
    }

    fun resolveExpectedNode(
        root: AccessibilityNodeInfo?,
        expected: UiNodeSnapshot,
        screenWidth: Int,
        screenHeight: Int,
    ): ExpectedNodeResolution = when {
        root == null -> rejected(AccessibilityDispatchResult.TargetNotFound)
        !root.refreshSafely() -> rejected(AccessibilityDispatchResult.RefreshFailed)
        root.packageName?.toString() != PIXEL_CAMERA_PACKAGE ->
            rejected(AccessibilityDispatchResult.TargetNotEligible)
        else -> resolveTarget(root, expected, screenWidth, screenHeight)
    }

    private fun collectChildren(
        node: AccessibilityNodeInfo,
        path: String,
        depth: Int,
        screenWidth: Int,
        screenHeight: Int,
        destination: MutableList<UiNodeSnapshot>,
    ): SnapshotCollection {
        var result: SnapshotCollection = SnapshotCollection.Complete
        var index = 0
        while (index < node.childCount && result != SnapshotCollection.RefreshFailed) {
            if (destination.size >= MAX_NODE_COUNT) {
                result = SnapshotCollection.Truncated
            } else {
                val child = node.getChild(index)
                val childResult = child?.let {
                    collectSnapshots(
                        node = it,
                        path = "$path/$index",
                        depth = depth + 1,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        destination = destination,
                    )
                }
                result = when (childResult) {
                    SnapshotCollection.RefreshFailed -> SnapshotCollection.RefreshFailed
                    SnapshotCollection.Truncated -> SnapshotCollection.Truncated
                    SnapshotCollection.Complete,
                    null,
                    -> result
                }
            }
            index += 1
        }
        return result
    }

    private fun resolveTarget(
        root: AccessibilityNodeInfo,
        expected: UiNodeSnapshot,
        screenWidth: Int,
        screenHeight: Int,
    ): ExpectedNodeResolution = when (val resolution = resolvePath(root, expected.id)) {
        PathResolution.RefreshFailed -> rejected(AccessibilityDispatchResult.RefreshFailed)
        PathResolution.NotFound -> rejected(AccessibilityDispatchResult.TargetNotFound)
        is PathResolution.Found -> validateTarget(
            target = resolution.node,
            expected = expected,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
    }

    private fun validateTarget(
        target: AccessibilityNodeInfo,
        expected: UiNodeSnapshot,
        screenWidth: Int,
        screenHeight: Int,
    ): ExpectedNodeResolution {
        val isEligible = target.packageName?.toString() == PIXEL_CAMERA_PACKAGE &&
            target.isVisibleToUser &&
            target.isEnabled
        val observed = if (isEligible) {
            target.toSnapshot(expected.id, screenWidth, screenHeight)
        } else {
            null
        }
        return when {
            !isEligible -> rejected(AccessibilityDispatchResult.TargetNotEligible)
            observed?.hasSameInteractionIdentityAs(expected) != true ->
                rejected(AccessibilityDispatchResult.TargetIdentityChanged)
            else -> ExpectedNodeResolution.Found(target)
        }
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
                checkedValue()
            },
            enabled = isEnabled,
        )
    }

    private fun resolvePath(root: AccessibilityNodeInfo, path: String): PathResolution {
        val segments = path.split('/')
        var current: AccessibilityNodeInfo? = root.takeIf { segments.firstOrNull() == ROOT_PATH }
        var failure: PathResolution? = if (current == null) PathResolution.NotFound else null
        var currentIsFresh = true
        for (segment in segments.drop(1)) {
            if (failure == null) {
                when (val step = resolveChild(current, currentIsFresh, segment)) {
                    is PathResolution.Found -> {
                        current = step.node
                        currentIsFresh = false
                    }
                    PathResolution.NotFound,
                    PathResolution.RefreshFailed,
                    -> failure = step
                }
            }
        }
        return when {
            failure != null -> failure
            current == null -> PathResolution.NotFound
            !currentIsFresh && !current.refreshSafely() -> PathResolution.RefreshFailed
            else -> PathResolution.Found(current)
        }
    }

    private fun resolveChild(
        node: AccessibilityNodeInfo?,
        nodeIsFresh: Boolean,
        segment: String,
    ): PathResolution {
        val index = segment.toIntOrNull()
        return when {
            node == null -> PathResolution.NotFound
            !nodeIsFresh && !node.refreshSafely() -> PathResolution.RefreshFailed
            index == null || index !in 0 until node.childCount -> PathResolution.NotFound
            else -> node.getChild(index)?.let(PathResolution::Found) ?: PathResolution.NotFound
        }
    }

    private fun AccessibilityNodeInfo.checkedValue(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            checkedValueApi36()
        } else {
            isChecked
        }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun AccessibilityNodeInfo.checkedValueApi36(): Boolean? = when (getChecked()) {
        AccessibilityNodeInfo.CHECKED_STATE_TRUE -> true
        AccessibilityNodeInfo.CHECKED_STATE_FALSE -> false
        AccessibilityNodeInfo.CHECKED_STATE_PARTIAL -> null
        else -> null
    }

    private fun rejected(result: AccessibilityDispatchResult): ExpectedNodeResolution =
        ExpectedNodeResolution.Rejected(result)

    private const val ROOT_PATH = "root"
    private const val MAX_NODE_COUNT = 512
    private const val MAX_DEPTH = 32
}

private sealed interface SnapshotCollection {
    data object Complete : SnapshotCollection

    data object Truncated : SnapshotCollection

    data object RefreshFailed : SnapshotCollection
}

private sealed interface PathResolution {
    data class Found(val node: AccessibilityNodeInfo) : PathResolution

    data object NotFound : PathResolution

    data object RefreshFailed : PathResolution
}

private sealed interface ExpectedNodeResolution {
    data class Found(val node: AccessibilityNodeInfo) : ExpectedNodeResolution

    data class Rejected(val result: AccessibilityDispatchResult) : ExpectedNodeResolution
}

private fun AccessibilityNodeInfo.refreshSafely(): Boolean = runCatching(::refresh)
    .fold(
        onSuccess = { it },
        onFailure = { error ->
            if (error is RuntimeException) false else throw error
        },
    )

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

    suspend fun dispatchClick(node: UiNodeSnapshot): AccessibilityDispatchResult =
        withContext(Dispatchers.Main.immediate) {
            serviceReference.get()?.get()?.dispatchClick(node)
                ?: AccessibilityDispatchResult.ServiceDisconnected
        }

    suspend fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult =
        withContext(Dispatchers.Main.immediate) {
            serviceReference.get()?.get()?.dispatchProfileGesture(point)
                ?: AccessibilityDispatchResult.ServiceDisconnected
        }

    suspend fun dispatchGlobalBack(pickerNode: UiNodeSnapshot): AccessibilityDispatchResult =
        withContext(Dispatchers.Main.immediate) {
            serviceReference.get()?.get()?.dispatchGlobalBack(pickerNode)
                ?: AccessibilityDispatchResult.ServiceDisconnected
        }
}
