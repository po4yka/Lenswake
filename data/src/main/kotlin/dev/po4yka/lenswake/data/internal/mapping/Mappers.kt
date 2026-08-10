package dev.po4yka.lenswake.data.internal.mapping

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.Zoom
import dev.po4yka.lenswake.data.internal.entity.AutomationProfileEntity
import dev.po4yka.lenswake.data.internal.entity.EnvironmentSnapshotEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionEventEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionSessionEntity
import dev.po4yka.lenswake.data.internal.entity.ScheduleEntity
import java.time.Instant
import java.time.ZoneId

private const val CAPTURE_TIME_LAPSE = "TIME_LAPSE"

internal fun RecordingSchedule.toEntity(): ScheduleEntity {
    val captureColumns = capture.toColumns()
    return ScheduleEntity(
        id = id.value,
        name = name,
        startAtEpochMs = startAt.toEpochMilli(),
        stopAtEpochMs = stopAt.toEpochMilli(),
        zoneId = zoneId.id,
        captureType = captureColumns.type,
        timeLapseSpeed = captureColumns.speed,
        lensSelection = captureColumns.lens,
        zoomFactor = captureColumns.zoom,
        profileId = profileId.value,
        enabled = enabled,
        createdAtEpochMs = createdAt.toEpochMilli(),
        updatedAtEpochMs = updatedAt.toEpochMilli(),
    )
}

internal fun ScheduleEntity.toDomain(): RecordingSchedule = RecordingSchedule(
    id = ScheduleId(id),
    name = name,
    startAt = Instant.ofEpochMilli(startAtEpochMs),
    stopAt = Instant.ofEpochMilli(stopAtEpochMs),
    zoneId = ZoneId.of(zoneId),
    capture = captureFromColumns(captureType, timeLapseSpeed, lensSelection, zoomFactor),
    profileId = ProfileId(profileId),
    enabled = enabled,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
)

internal fun PixelCameraProfile.toEntity(): AutomationProfileEntity = AutomationProfileEntity(
    id = id.value,
    deviceManufacturer = environment.deviceManufacturer,
    deviceModel = environment.deviceModel,
    androidSdk = environment.androidSdk,
    androidBuildFingerprint = environment.androidBuildFingerprint,
    cameraPackage = environment.cameraPackage,
    cameraVersionCode = environment.cameraVersionCode,
    localeTag = environment.localeTag,
    displayWidthPx = environment.displayWidthPx,
    displayHeightPx = environment.displayHeightPx,
    densityDpi = environment.densityDpi,
    selectorSchemaVersion = selectorSchemaVersion,
    targetsJson = JsonColumnCodec.encodeTargets(targets),
    speedTargetsJson = JsonColumnCodec.encodeSpeedTargets(speedTargets),
    stateSignalsJson = JsonColumnCodec.encodeStateSignals(stateSignals),
    fallbackGesturesJson = JsonColumnCodec.encodeGestures(fallbackGestures),
    compatibility = compatibility.name,
    verifiedAtEpochMs = verifiedAt?.toEpochMilli(),
)

internal fun AutomationProfileEntity.toDomain(): PixelCameraProfile = PixelCameraProfile(
    id = ProfileId(id),
    environment = PixelCameraEnvironment(
        deviceManufacturer = deviceManufacturer,
        deviceModel = deviceModel,
        androidSdk = androidSdk,
        androidBuildFingerprint = androidBuildFingerprint,
        cameraPackage = cameraPackage,
        cameraVersionCode = cameraVersionCode,
        localeTag = localeTag,
        displayWidthPx = displayWidthPx,
        displayHeightPx = displayHeightPx,
        densityDpi = densityDpi,
    ),
    selectorSchemaVersion = selectorSchemaVersion,
    targets = JsonColumnCodec.decodeTargets(targetsJson),
    speedTargets = JsonColumnCodec.decodeSpeedTargets(speedTargetsJson),
    stateSignals = JsonColumnCodec.decodeStateSignals(stateSignalsJson),
    fallbackGestures = JsonColumnCodec.decodeGestures(fallbackGesturesJson),
    compatibility = enumValueOf<ProfileCompatibility>(compatibility),
    verifiedAt = verifiedAtEpochMs?.let(Instant::ofEpochMilli),
)

internal fun EnvironmentSnapshot.toEntity(): EnvironmentSnapshotEntity = EnvironmentSnapshotEntity(
    id = id.value,
    sessionId = sessionId.value,
    capturedAtEpochMs = capturedAt.toEpochMilli(),
    lenswakeVersion = lenswakeVersion,
    deviceManufacturer = cameraEnvironment.deviceManufacturer,
    deviceModel = cameraEnvironment.deviceModel,
    androidSdk = cameraEnvironment.androidSdk,
    androidBuildFingerprint = cameraEnvironment.androidBuildFingerprint,
    cameraPackage = cameraEnvironment.cameraPackage,
    cameraVersionCode = cameraEnvironment.cameraVersionCode,
    localeTag = cameraEnvironment.localeTag,
    displayWidthPx = cameraEnvironment.displayWidthPx,
    displayHeightPx = cameraEnvironment.displayHeightPx,
    densityDpi = cameraEnvironment.densityDpi,
    accessibilityStatus = accessibilityStatus.name,
    privilegedBridgeStatus = privilegedBridgeStatus.name,
    screenInteractive = screenInteractive,
    keyguardLocked = keyguardLocked,
    batteryPercent = batteryPercent,
    charging = charging,
    availableStorageBytes = availableStorageBytes,
)

internal fun EnvironmentSnapshotEntity.toDomain(): EnvironmentSnapshot = EnvironmentSnapshot(
    id = EnvironmentSnapshotId(id),
    sessionId = SessionId(sessionId),
    capturedAt = Instant.ofEpochMilli(capturedAtEpochMs),
    lenswakeVersion = lenswakeVersion,
    cameraEnvironment = PixelCameraEnvironment(
        deviceManufacturer = deviceManufacturer,
        deviceModel = deviceModel,
        androidSdk = androidSdk,
        androidBuildFingerprint = androidBuildFingerprint,
        cameraPackage = cameraPackage,
        cameraVersionCode = cameraVersionCode,
        localeTag = localeTag,
        displayWidthPx = displayWidthPx,
        displayHeightPx = displayHeightPx,
        densityDpi = densityDpi,
    ),
    accessibilityStatus = enumValueOf<EnvironmentCapabilityStatus>(accessibilityStatus),
    privilegedBridgeStatus = enumValueOf<EnvironmentCapabilityStatus>(privilegedBridgeStatus),
    screenInteractive = screenInteractive,
    keyguardLocked = keyguardLocked,
    batteryPercent = batteryPercent,
    charging = charging,
    availableStorageBytes = availableStorageBytes,
)

internal fun ExecutionSession.toEntity(): ExecutionSessionEntity {
    val captureColumns = capture.toColumns()
    return ExecutionSessionEntity(
        id = id.value,
        executionKey = executionKey,
        kind = kind.name,
        scheduleId = scheduleId?.value,
        scheduleName = scheduleName,
        profileId = profileId.value,
        captureType = captureColumns.type,
        timeLapseSpeed = captureColumns.speed,
        lensSelection = captureColumns.lens,
        zoomFactor = captureColumns.zoom,
        expectedStartAtEpochMs = expectedStartAt.toEpochMilli(),
        expectedStopAtEpochMs = expectedStopAt.toEpochMilli(),
        alarmStartDeliveredAtEpochMs = alarmStartDeliveredAt?.toEpochMilli(),
        alarmStopDeliveredAtEpochMs = alarmStopDeliveredAt?.toEpochMilli(),
        status = status.name,
        currentAutomationState = currentAutomationState?.name,
        recordActionAtEpochMs = recordActionAt?.toEpochMilli(),
        recordingVerifiedAtEpochMs = recordingVerifiedAt?.toEpochMilli(),
        stopActionAtEpochMs = stopActionAt?.toEpochMilli(),
        stoppedVerifiedAtEpochMs = stoppedVerifiedAt?.toEpochMilli(),
        cameraOwnershipReleasedAtEpochMs = cameraOwnershipReleasedAt?.toEpochMilli(),
        environmentSnapshotId = environmentSnapshotId?.value,
        failureCode = failure?.code?.name,
        failureMessage = failure?.message,
        failureContextJson = failure?.context?.let(JsonColumnCodec::encodeStringMap),
        revision = revision,
        createdAtEpochMs = createdAt.toEpochMilli(),
        updatedAtEpochMs = updatedAt.toEpochMilli(),
    )
}

internal fun ExecutionSessionEntity.toDomain(): ExecutionSession = ExecutionSession(
    id = SessionId(id),
    executionKey = executionKey,
    kind = enumValueOf<SessionKind>(kind),
    scheduleId = scheduleId?.let(::ScheduleId),
    scheduleName = scheduleName,
    profileId = ProfileId(profileId),
    capture = captureFromColumns(captureType, timeLapseSpeed, lensSelection, zoomFactor),
    expectedStartAt = Instant.ofEpochMilli(expectedStartAtEpochMs),
    expectedStopAt = Instant.ofEpochMilli(expectedStopAtEpochMs),
    alarmStartDeliveredAt = alarmStartDeliveredAtEpochMs?.let(Instant::ofEpochMilli),
    alarmStopDeliveredAt = alarmStopDeliveredAtEpochMs?.let(Instant::ofEpochMilli),
    status = enumValueOf<SessionStatus>(status),
    currentAutomationState = currentAutomationState?.let { enumValueOf<AutomationStateName>(it) },
    recordActionAt = recordActionAtEpochMs?.let(Instant::ofEpochMilli),
    recordingVerifiedAt = recordingVerifiedAtEpochMs?.let(Instant::ofEpochMilli),
    stopActionAt = stopActionAtEpochMs?.let(Instant::ofEpochMilli),
    stoppedVerifiedAt = stoppedVerifiedAtEpochMs?.let(Instant::ofEpochMilli),
    cameraOwnershipReleasedAt = cameraOwnershipReleasedAtEpochMs?.let(Instant::ofEpochMilli),
    environmentSnapshotId = environmentSnapshotId?.let(::EnvironmentSnapshotId),
    failure = failureFromColumns(failureCode, failureMessage, failureContextJson),
    revision = revision,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
)

internal fun AutomationEvent.toEntity(sequence: Long = this.sequence ?: 0): ExecutionEventEntity =
    ExecutionEventEntity(
        id = id.value,
        sessionId = sessionId.value,
        name = name,
        sequence = sequence,
        timestampEpochMs = timestamp.toEpochMilli(),
        state = state.name,
        operation = operation?.name,
        outcome = outcome.name,
        interactionMethod = interactionMethod?.name,
        attempt = attempt,
        durationMs = durationMs,
        failureCode = failure?.code?.name,
        failureMessage = failure?.message,
        failureContextJson = failure?.context?.let(JsonColumnCodec::encodeStringMap),
        metadataJson = JsonColumnCodec.encodeStringMap(metadata),
    )

internal fun ExecutionEventEntity.toDomain(): AutomationEvent = AutomationEvent(
    id = EventId(id),
    sessionId = SessionId(sessionId),
    name = name,
    sequence = sequence,
    timestamp = Instant.ofEpochMilli(timestampEpochMs),
    state = enumValueOf<AutomationStateName>(state),
    operation = operation?.let { enumValueOf<AutomationOperation>(it) },
    outcome = enumValueOf<AutomationOutcome>(outcome),
    interactionMethod = interactionMethod?.let { enumValueOf<InteractionMethod>(it) },
    attempt = attempt,
    durationMs = durationMs,
    failure = failureFromColumns(failureCode, failureMessage, failureContextJson),
    metadata = JsonColumnCodec.decodeStringMap(metadataJson),
)

private data class CaptureColumns(
    val type: String,
    val speed: String,
    val lens: String,
    val zoom: Float?,
)

private fun CaptureConfiguration.toColumns(): CaptureColumns = when (this) {
    is CaptureConfiguration.TimeLapse -> CaptureColumns(
        type = CAPTURE_TIME_LAPSE,
        speed = speed.name,
        lens = lens.name,
        zoom = zoom?.factor,
    )
}

private fun captureFromColumns(
    type: String,
    speed: String,
    lens: String,
    zoom: Float?,
): CaptureConfiguration {
    require(type == CAPTURE_TIME_LAPSE) { "Unsupported persisted capture type: $type" }
    return CaptureConfiguration.TimeLapse(
        speed = enumValueOf<TimeLapseSpeed>(speed),
        lens = enumValueOf<LensSelection>(lens),
        zoom = zoom?.let {
            requireNotNull(Zoom.of(it)) { "Invalid persisted zoom factor: $it" }
        },
    )
}

private fun failureFromColumns(
    code: String?,
    message: String?,
    contextJson: String?,
): AutomationFailure? {
    if (code == null && message == null && contextJson == null) return null
    require(code != null && message != null && contextJson != null) {
        "Persisted automation failure is incomplete"
    }
    return AutomationFailure(
        code = enumValueOf<AutomationFailureCode>(code),
        message = message,
        context = JsonColumnCodec.decodeStringMap(contextJson),
    )
}
