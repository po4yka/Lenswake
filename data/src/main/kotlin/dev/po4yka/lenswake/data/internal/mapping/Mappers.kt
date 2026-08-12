package dev.po4yka.lenswake.data.internal.mapping

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileSource
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfileProvenance
import dev.po4yka.lenswake.core.SelectorTemplateReference
import dev.po4yka.lenswake.core.SupportTier
import dev.po4yka.lenswake.core.DisplayOrientation
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.data.internal.entity.AutomationProfileEntity
import dev.po4yka.lenswake.data.internal.entity.EnvironmentSnapshotEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionEventEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionSessionEntity
import dev.po4yka.lenswake.data.internal.entity.ScheduleEntity
import java.time.Instant
import java.time.ZoneId

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
        videoResolution = captureColumns.videoResolution,
        videoFrameRate = captureColumns.videoFrameRate,
        experimentalRiskAccepted = experimentalRiskAccepted,
        profileId = profileId.value,
        profileSupportTier = profileProvenance.supportTier.name,
        profileSource = profileProvenance.source.name,
        profileTemplateId = profileProvenance.selectorTemplate.id,
        profileTemplateVersion = profileProvenance.selectorTemplate.version,
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
    capture = captureFromColumns(
        captureType, timeLapseSpeed, lensSelection, zoomFactor, videoResolution, videoFrameRate,
    ),
    profileId = ProfileId(profileId),
    profileProvenance = ProfileProvenance(
        enumValueOf(profileSupportTier),
        enumValueOf(profileSource),
        SelectorTemplateReference(profileTemplateId, profileTemplateVersion),
    ),
    experimentalRiskAccepted = experimentalRiskAccepted,
    enabled = enabled,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
)

internal fun PixelCameraProfile.toEntity(): AutomationProfileEntity = AutomationProfileEntity(
    id = id.value,
    deviceManufacturer = environment.deviceManufacturer,
    deviceModel = environment.deviceModel,
    deviceCodename = environment.deviceCodename,
    androidSdk = environment.androidSdk,
    androidBuildFingerprint = environment.androidBuildFingerprint,
    cameraPackage = environment.cameraPackage,
    cameraVersionCode = environment.cameraVersionCode,
    cameraSigningCertificateSha256 = environment.cameraSigningCertificateSha256,
    localeTag = environment.localeTag,
    displayWidthPx = environment.displayWidthPx,
    displayHeightPx = environment.displayHeightPx,
    densityDpi = environment.densityDpi,
    fontScale = environment.fontScale,
    displayOrientation = environment.orientation.name,
    defaultDisplayConfiguration = environment.defaultDisplayConfiguration,
    selectorSchemaVersion = selectorSchemaVersion,
    supportTier = supportTier.name,
    profileSource = source.name,
    selectorTemplateId = selectorTemplate.id,
    selectorTemplateVersion = selectorTemplate.version,
    targetsJson = JsonColumnCodec.encodeTargets(targets),
    speedTargetsJson = JsonColumnCodec.encodeSpeedTargets(speedTargets),
    stateSignalsJson = JsonColumnCodec.encodeStateSignals(stateSignals),
    dialogProfilesJson = JsonColumnCodec.encodeDialogProfiles(dialogProfiles),
    fallbackGesturesJson = JsonColumnCodec.encodeGestures(fallbackGestures),
    compatibility = compatibility.name,
    verifiedAtEpochMs = verifiedAt?.toEpochMilli(),
)

internal fun AutomationProfileEntity.toDomain(): PixelCameraProfile = PixelCameraProfile(
    id = ProfileId(id),
    environment = PixelCameraEnvironment(
        deviceManufacturer = deviceManufacturer,
        deviceModel = deviceModel,
        deviceCodename = deviceCodename,
        androidSdk = androidSdk,
        androidBuildFingerprint = androidBuildFingerprint,
        cameraPackage = cameraPackage,
        cameraVersionCode = cameraVersionCode,
        cameraSigningCertificateSha256 = cameraSigningCertificateSha256,
        localeTag = localeTag,
        displayWidthPx = displayWidthPx,
        displayHeightPx = displayHeightPx,
        densityDpi = densityDpi,
        fontScale = fontScale,
        orientation = enumValueOf<DisplayOrientation>(displayOrientation),
        defaultDisplayConfiguration = defaultDisplayConfiguration,
    ),
    selectorSchemaVersion = selectorSchemaVersion,
    supportTier = enumValueOf<SupportTier>(supportTier),
    source = enumValueOf<ProfileSource>(profileSource),
    selectorTemplate = SelectorTemplateReference(selectorTemplateId, selectorTemplateVersion),
    targets = JsonColumnCodec.decodeTargets(targetsJson),
    speedTargets = JsonColumnCodec.decodeSpeedTargets(speedTargetsJson),
    stateSignals = JsonColumnCodec.decodeStateSignals(stateSignalsJson),
    dialogProfiles = JsonColumnCodec.decodeDialogProfiles(dialogProfilesJson),
    fallbackGestures = JsonColumnCodec.decodeGestures(fallbackGesturesJson),
    compatibility = enumValueOf<ProfileCompatibility>(compatibility),
    verifiedAt = verifiedAtEpochMs?.let(Instant::ofEpochMilli),
)

internal fun EnvironmentSnapshot.toEntity(): EnvironmentSnapshotEntity = EnvironmentSnapshotEntity(
    id = id.value,
    sessionId = sessionId.value,
    profileSupportTier = profileProvenance.supportTier.name,
    profileSource = profileProvenance.source.name,
    profileTemplateId = profileProvenance.selectorTemplate.id,
    profileTemplateVersion = profileProvenance.selectorTemplate.version,
    capturedAtEpochMs = capturedAt.toEpochMilli(),
    lenswakeVersion = lenswakeVersion,
    deviceManufacturer = cameraEnvironment.deviceManufacturer,
    deviceModel = cameraEnvironment.deviceModel,
    deviceCodename = cameraEnvironment.deviceCodename,
    androidSdk = cameraEnvironment.androidSdk,
    androidBuildFingerprint = cameraEnvironment.androidBuildFingerprint,
    cameraPackage = cameraEnvironment.cameraPackage,
    cameraVersionCode = cameraEnvironment.cameraVersionCode,
    cameraSigningCertificateSha256 = cameraEnvironment.cameraSigningCertificateSha256,
    localeTag = cameraEnvironment.localeTag,
    displayWidthPx = cameraEnvironment.displayWidthPx,
    displayHeightPx = cameraEnvironment.displayHeightPx,
    densityDpi = cameraEnvironment.densityDpi,
    fontScale = cameraEnvironment.fontScale,
    displayOrientation = cameraEnvironment.orientation.name,
    defaultDisplayConfiguration = cameraEnvironment.defaultDisplayConfiguration,
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
    profileProvenance = ProfileProvenance(
        enumValueOf(profileSupportTier),
        enumValueOf(profileSource),
        SelectorTemplateReference(profileTemplateId, profileTemplateVersion),
    ),
    capturedAt = Instant.ofEpochMilli(capturedAtEpochMs),
    lenswakeVersion = lenswakeVersion,
    cameraEnvironment = PixelCameraEnvironment(
        deviceManufacturer = deviceManufacturer,
        deviceModel = deviceModel,
        deviceCodename = deviceCodename,
        androidSdk = androidSdk,
        androidBuildFingerprint = androidBuildFingerprint,
        cameraPackage = cameraPackage,
        cameraVersionCode = cameraVersionCode,
        cameraSigningCertificateSha256 = cameraSigningCertificateSha256,
        localeTag = localeTag,
        displayWidthPx = displayWidthPx,
        displayHeightPx = displayHeightPx,
        densityDpi = densityDpi,
        fontScale = fontScale,
        orientation = enumValueOf<DisplayOrientation>(displayOrientation),
        defaultDisplayConfiguration = defaultDisplayConfiguration,
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
        profileSupportTier = profileProvenance.supportTier.name,
        profileSource = profileProvenance.source.name,
        profileTemplateId = profileProvenance.selectorTemplate.id,
        profileTemplateVersion = profileProvenance.selectorTemplate.version,
        captureType = captureColumns.type,
        timeLapseSpeed = captureColumns.speed,
        lensSelection = captureColumns.lens,
        zoomFactor = captureColumns.zoom,
        videoResolution = captureColumns.videoResolution,
        videoFrameRate = captureColumns.videoFrameRate,
        expectedStartAtEpochMs = expectedStartAt.toEpochMilli(),
        expectedStopAtEpochMs = expectedStopAt.toEpochMilli(),
        alarmStartDeliveredAtEpochMs = alarmStartDeliveredAt?.toEpochMilli(),
        alarmStopDeliveredAtEpochMs = alarmStopDeliveredAt?.toEpochMilli(),
        status = status.name,
        currentAutomationState = currentAutomationState?.name,
        recordActionAtEpochMs = recordActionAt?.toEpochMilli(),
        recordingVerifiedAtEpochMs = recordingVerifiedAt?.toEpochMilli(),
        rehearsalVerifiedAtEpochMs = rehearsalVerifiedAt?.toEpochMilli(),
        mediaBaselineGeneration = mediaBaselineGeneration,
        mediaStoreVersion = mediaStoreVersion,
        mediaVerificationRequired = mediaVerificationRequired,
        mediaSavedVerifiedAtEpochMs = mediaSavedVerifiedAt?.toEpochMilli(),
        savedMediaGeneration = savedMediaGeneration,
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
    profileProvenance = ProfileProvenance(
        enumValueOf(profileSupportTier),
        enumValueOf(profileSource),
        SelectorTemplateReference(profileTemplateId, profileTemplateVersion),
    ),
    capture = captureFromColumns(
        captureType, timeLapseSpeed, lensSelection, zoomFactor, videoResolution, videoFrameRate,
    ),
    expectedStartAt = Instant.ofEpochMilli(expectedStartAtEpochMs),
    expectedStopAt = Instant.ofEpochMilli(expectedStopAtEpochMs),
    alarmStartDeliveredAt = alarmStartDeliveredAtEpochMs?.let(Instant::ofEpochMilli),
    alarmStopDeliveredAt = alarmStopDeliveredAtEpochMs?.let(Instant::ofEpochMilli),
    status = enumValueOf<SessionStatus>(status),
    currentAutomationState = currentAutomationState?.let { enumValueOf<AutomationStateName>(it) },
    recordActionAt = recordActionAtEpochMs?.let(Instant::ofEpochMilli),
    recordingVerifiedAt = recordingVerifiedAtEpochMs?.let(Instant::ofEpochMilli),
    rehearsalVerifiedAt = rehearsalVerifiedAtEpochMs?.let(Instant::ofEpochMilli),
    mediaBaselineGeneration = mediaBaselineGeneration,
    mediaStoreVersion = mediaStoreVersion,
    mediaVerificationRequired = mediaVerificationRequired,
    mediaSavedVerifiedAt = mediaSavedVerifiedAtEpochMs?.let(Instant::ofEpochMilli),
    savedMediaGeneration = savedMediaGeneration,
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
