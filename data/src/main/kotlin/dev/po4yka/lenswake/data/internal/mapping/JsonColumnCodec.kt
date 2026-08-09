package dev.po4yka.lenswake.data.internal.mapping

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.GestureProfile
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.NormalizedPoint
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object JsonColumnCodec {
    private const val PROFILE_JSON_SCHEMA_VERSION = 2
    private const val MAX_DIAGNOSTIC_JSON_LENGTH = 16_384
    private const val MAX_PROFILE_JSON_LENGTH = 262_144

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encodeStringMap(values: Map<String, String>): String {
        val orderedValues: Map<String, String> = values.entries
            .sortedBy { it.key }
            .associateTo(linkedMapOf()) { it.key to it.value }
        return json.encodeToString<Map<String, String>>(orderedValues)
            .bounded(MAX_DIAGNOSTIC_JSON_LENGTH, "diagnostic metadata")
    }

    fun decodeStringMap(encoded: String): Map<String, String> =
        json.decodeFromString<Map<String, String>>(
            encoded.bounded(MAX_DIAGNOSTIC_JSON_LENGTH, "diagnostic metadata"),
        )

    fun encodeTargets(targets: Map<AutomationAction, UiSelectorSet>): String {
        val payload = TargetsPayload(
            schemaVersion = PROFILE_JSON_SCHEMA_VERSION,
            targets = targets.entries
                .sortedBy { it.key.name }
                .map { (action, set) ->
                    TargetPayload(
                        action = action.name,
                        minimumScore = set.minimumScore,
                        selectors = set.selectors.map { it.toPayload() },
                    )
                },
        )
        return json.encodeToString(payload).bounded(MAX_PROFILE_JSON_LENGTH, "profile targets")
    }

    fun decodeTargets(encoded: String): Map<AutomationAction, UiSelectorSet> {
        val payload = json.decodeFromString<TargetsPayload>(
            encoded.bounded(MAX_PROFILE_JSON_LENGTH, "profile targets"),
        )
        require(payload.schemaVersion == PROFILE_JSON_SCHEMA_VERSION) {
            "Unsupported profile targets JSON schema version: ${payload.schemaVersion}"
        }
        require(payload.targets.map { it.action }.distinct().size == payload.targets.size) {
            "Persisted profile targets contain duplicate action keys"
        }
        return payload.targets.associate { target ->
            enumValueOf<AutomationAction>(target.action) to UiSelectorSet(
                selectors = target.selectors.map { it.toDomain() },
                minimumScore = target.minimumScore,
            )
        }
    }

    fun encodeSpeedTargets(speedTargets: Map<TimeLapseSpeed, UiSelectorSet>): String {
        val payload = SpeedTargetsPayload(
            schemaVersion = PROFILE_JSON_SCHEMA_VERSION,
            targets = speedTargets.entries
                .sortedBy { it.key.name }
                .map { (speed, set) ->
                    SpeedTargetPayload(
                        speed = speed.name,
                        minimumScore = set.minimumScore,
                        selectors = set.selectors.map { it.toPayload() },
                    )
                },
        )
        return json.encodeToString(payload).bounded(MAX_PROFILE_JSON_LENGTH, "profile speed targets")
    }

    fun decodeSpeedTargets(encoded: String): Map<TimeLapseSpeed, UiSelectorSet> {
        val payload = json.decodeFromString<SpeedTargetsPayload>(
            encoded.bounded(MAX_PROFILE_JSON_LENGTH, "profile speed targets"),
        )
        require(payload.schemaVersion == PROFILE_JSON_SCHEMA_VERSION) {
            "Unsupported profile speed targets JSON schema version: ${payload.schemaVersion}"
        }
        require(payload.targets.map { it.speed }.distinct().size == payload.targets.size) {
            "Persisted profile speed targets contain duplicate speed keys"
        }
        return payload.targets.associate { target ->
            enumValueOf<TimeLapseSpeed>(target.speed) to UiSelectorSet(
                selectors = target.selectors.map { it.toDomain() },
                minimumScore = target.minimumScore,
            )
        }
    }

    fun encodeStateSignals(stateSignals: Map<PixelCameraStateSignal, UiSelectorSet>): String {
        val payload = StateSignalsPayload(
            schemaVersion = PROFILE_JSON_SCHEMA_VERSION,
            signals = stateSignals.entries
                .sortedBy { it.key.name }
                .map { (signal, set) ->
                    StateSignalPayload(
                        signal = signal.name,
                        minimumScore = set.minimumScore,
                        selectors = set.selectors.map { it.toPayload() },
                    )
                },
        )
        return json.encodeToString(payload).bounded(MAX_PROFILE_JSON_LENGTH, "profile state signals")
    }

    fun decodeStateSignals(encoded: String): Map<PixelCameraStateSignal, UiSelectorSet> {
        val payload = json.decodeFromString<StateSignalsPayload>(
            encoded.bounded(MAX_PROFILE_JSON_LENGTH, "profile state signals"),
        )
        require(payload.schemaVersion == PROFILE_JSON_SCHEMA_VERSION) {
            "Unsupported profile state signals JSON schema version: ${payload.schemaVersion}"
        }
        require(payload.signals.map { it.signal }.distinct().size == payload.signals.size) {
            "Persisted profile state signals contain duplicate signal keys"
        }
        return payload.signals.associate { signalPayload ->
            enumValueOf<PixelCameraStateSignal>(signalPayload.signal) to UiSelectorSet(
                selectors = signalPayload.selectors.map { it.toDomain() },
                minimumScore = signalPayload.minimumScore,
            )
        }
    }

    fun encodeGestures(gestures: Map<AutomationAction, GestureProfile>): String {
        val payload = gestures.entries
            .sortedBy { it.key.name }
            .map { (action, gesture) ->
                GesturePayload(
                    action = action.name,
                    x = gesture.point.x,
                    y = gesture.point.y,
                )
            }
        return json.encodeToString(payload).bounded(MAX_PROFILE_JSON_LENGTH, "profile gestures")
    }

    fun decodeGestures(encoded: String): Map<AutomationAction, GestureProfile> =
        json.decodeFromString<List<GesturePayload>>(
            encoded.bounded(MAX_PROFILE_JSON_LENGTH, "profile gestures"),
        ).associate { payload ->
            enumValueOf<AutomationAction>(payload.action) to GestureProfile(
                NormalizedPoint(payload.x, payload.y),
            )
        }

    private fun String.bounded(maxLength: Int, columnPurpose: String): String {
        require(length <= maxLength) {
            "$columnPurpose JSON exceeds the $maxLength character persistence limit"
        }
        return this
    }
}

@Serializable
private data class TargetsPayload(
    val schemaVersion: Int,
    val targets: List<TargetPayload>,
)

@Serializable
private data class TargetPayload(
    val action: String,
    val minimumScore: Int,
    val selectors: List<SelectorPayload>,
)

@Serializable
private data class SpeedTargetsPayload(
    val schemaVersion: Int,
    val targets: List<SpeedTargetPayload>,
)

@Serializable
private data class SpeedTargetPayload(
    val speed: String,
    val minimumScore: Int,
    val selectors: List<SelectorPayload>,
)

@Serializable
private data class SelectorPayload(
    val packageName: String,
    val resourceId: String?,
    val role: String?,
    val contentDescription: String?,
    val text: String?,
    val expectedSelected: Boolean?,
    val expectedChecked: Boolean? = null,
    val expectedRegion: BoundsPayload?,
    val requiresClickable: Boolean,
    val requiresVisible: Boolean,
)

@Serializable
private data class StateSignalsPayload(
    val schemaVersion: Int,
    val signals: List<StateSignalPayload>,
)

@Serializable
private data class StateSignalPayload(
    val signal: String,
    val minimumScore: Int,
    val selectors: List<SelectorPayload>,
)

@Serializable
private data class BoundsPayload(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Serializable
private data class GesturePayload(
    val action: String,
    val x: Float,
    val y: Float,
)

private fun UiSelector.toPayload(): SelectorPayload = SelectorPayload(
    packageName = packageName,
    resourceId = resourceId,
    role = role,
    contentDescription = contentDescription,
    text = text,
    expectedSelected = expectedSelected,
    expectedChecked = expectedChecked,
    expectedRegion = expectedRegion?.let {
        BoundsPayload(it.left, it.top, it.right, it.bottom)
    },
    requiresClickable = requiresClickable,
    requiresVisible = requiresVisible,
)

private fun SelectorPayload.toDomain(): UiSelector = UiSelector(
    packageName = packageName,
    resourceId = resourceId,
    role = role,
    contentDescription = contentDescription,
    text = text,
    expectedSelected = expectedSelected,
    expectedChecked = expectedChecked,
    expectedRegion = expectedRegion?.let {
        NormalizedBounds(it.left, it.top, it.right, it.bottom)
    },
    requiresClickable = requiresClickable,
    requiresVisible = requiresVisible,
)
