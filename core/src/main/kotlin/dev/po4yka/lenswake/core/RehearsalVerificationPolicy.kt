package dev.po4yka.lenswake.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Reasons why persisted rehearsal evidence cannot authorize a capture configuration. */
enum class RehearsalVerificationFailure {
    NOT_REHEARSAL,
    NOT_COMPLETED,
    EXECUTION_FAILED,
    PROFILE_MISMATCH,
    CAPTURE_MISMATCH,
    RECORD_ACTION_MISSING,
    RECORDING_NOT_VERIFIED,
    STOP_ACTION_MISSING,
    STOP_NOT_VERIFIED,
    MEDIA_NOT_VERIFIED,
    RECEIPT_MISSING,
    PROFILE_DEFINITION_CHANGED,
}

/** Canonical fail-closed policy for rehearsal promotion and capture authorization. */
object RehearsalVerificationPolicy {
    fun fullProofFailure(session: ExecutionSession): RehearsalVerificationFailure? = when {
        session.kind != SessionKind.REHEARSAL -> RehearsalVerificationFailure.NOT_REHEARSAL
        session.status != SessionStatus.COMPLETED -> RehearsalVerificationFailure.NOT_COMPLETED
        session.failure != null -> RehearsalVerificationFailure.EXECUTION_FAILED
        session.recordActionAt == null -> RehearsalVerificationFailure.RECORD_ACTION_MISSING
        session.recordingVerifiedAt == null -> RehearsalVerificationFailure.RECORDING_NOT_VERIFIED
        session.stopActionAt == null -> RehearsalVerificationFailure.STOP_ACTION_MISSING
        session.stoppedVerifiedAt == null -> RehearsalVerificationFailure.STOP_NOT_VERIFIED
        session.mediaSavedVerifiedAt == null -> RehearsalVerificationFailure.MEDIA_NOT_VERIFIED
        else -> null
    }

    fun receiptQualificationFailure(
        session: ExecutionSession,
        profile: PixelCameraProfile,
    ): RehearsalVerificationFailure? =
        fullProofFailure(session)
            ?: profileFailure(session, profile)

    fun qualificationFailure(
        session: ExecutionSession,
        profile: PixelCameraProfile,
        capture: CaptureConfiguration,
    ): RehearsalVerificationFailure? =
        receiptQualificationFailure(session, profile)
            ?: when {
                session.capture != capture -> RehearsalVerificationFailure.CAPTURE_MISMATCH
                session.rehearsalVerifiedAt == null -> RehearsalVerificationFailure.RECEIPT_MISSING
                else -> null
            }

    fun qualifies(
        session: ExecutionSession,
        profile: PixelCameraProfile,
        capture: CaptureConfiguration,
    ): Boolean = qualificationFailure(session, profile, capture) == null

    fun hasDurableReceipt(session: ExecutionSession): Boolean =
        fullProofFailure(session) == null && session.rehearsalVerifiedAt != null

    fun awaitsDurableReceipt(session: ExecutionSession): Boolean =
        fullProofFailure(session) == null && session.rehearsalVerifiedAt == null

    private fun profileFailure(
        session: ExecutionSession,
        profile: PixelCameraProfile,
    ): RehearsalVerificationFailure? = when {
        session.profileId != profile.id -> RehearsalVerificationFailure.PROFILE_MISMATCH
        session.testedProfileFingerprint() != profile.definitionFingerprint() ->
            RehearsalVerificationFailure.PROFILE_DEFINITION_CHANGED
        else -> null
    }
}

fun ExecutionSession.testedProfileFingerprint(): String? =
    executionKey
        .substringAfterLast('/', missingDelimiterValue = "")
        .takeIf { fingerprint ->
            fingerprint.length == SHA_256_HEX_LENGTH && fingerprint.all(Char::isHexDigit)
        }

fun PixelCameraProfile.definitionFingerprint(): String = ProfileDefinitionFingerprint.calculate(this)

private object ProfileDefinitionFingerprint {
    fun calculate(profile: PixelCameraProfile): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> FingerprintWriter(output).writeProfile(profile) }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(bytes.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }
    }
}

private class FingerprintWriter(
    private val output: DataOutputStream,
) {
    fun writeProfile(profile: PixelCameraProfile) {
        writeString(profile.id.value)
        writeEnvironment(profile.environment)
        output.writeInt(profile.selectorSchemaVersion)
        writeString(profile.supportTier.name)
        writeCertification(profile.certification)
        writeString(profile.source.name)
        writeString(profile.selectorTemplate.id)
        output.writeInt(profile.selectorTemplate.version)
        writeSelectorSets("action", profile.targets.mapKeys { it.key.name })
        writeSelectorSets("speed", profile.speedTargets.mapKeys { it.key.name })
        writeSelectorSets("signal", profile.stateSignals.mapKeys { it.key.name })
        writeGestures(profile.fallbackGestures)
        writeDialogs(profile.dialogProfiles)
    }

    private fun writeCertification(certification: ProfileCertification?) {
        output.writeBoolean(certification != null)
        if (certification == null) return
        writeString(certification.releaseTag)
        writeString(certification.releaseCommit)
        output.writeLong(certification.candidateRunId)
        writeString(certification.lenswakeApkSha256)
        writeString(certification.bundleSha256)
        writeString(certification.pixel7EvidenceSha256)
        writeString(certification.pixel8ProEvidenceSha256)
    }

    private fun writeEnvironment(environment: PixelCameraEnvironment) = with(environment) {
        writeString(deviceManufacturer)
        writeString(deviceModel)
        writeString(deviceCodename)
        output.writeInt(androidSdk)
        writeString(androidBuildFingerprint)
        writeString(cameraPackage)
        output.writeLong(cameraVersionCode)
        writeString(cameraSigningCertificateSha256)
        writeString(localeTag)
        output.writeInt(displayWidthPx)
        output.writeInt(displayHeightPx)
        output.writeInt(densityDpi)
        output.writeFloat(fontScale)
        writeString(orientation.name)
        output.writeBoolean(defaultDisplayConfiguration)
    }

    private fun writeDialogs(dialogs: Map<PixelCameraDialogKind, PixelCameraDialogProfile>) {
        dialogs.toSortedMap().forEach { (kind, dialog) ->
            writeString("dialog:${kind.name}:presence")
            writeSelectorSet(dialog.presence)
            writeString("dialog:${kind.name}:recovery")
            output.writeBoolean(dialog.recoveryTarget != null)
            dialog.recoveryTarget?.let(::writeSelectorSet)
        }
    }

    private fun writeSelectorSets(
        prefix: String,
        sets: Map<String, UiSelectorSet>,
    ) {
        sets.toSortedMap().forEach { (name, set) ->
            writeString("$prefix:$name")
            writeSelectorSet(set)
        }
    }

    private fun writeSelectorSet(set: UiSelectorSet) {
        output.writeInt(set.minimumScore)
        output.writeInt(set.selectors.size)
        set.selectors.forEach(::writeSelector)
    }

    private fun writeSelector(selector: UiSelector) = with(selector) {
        writeString(packageName)
        writeString(resourceId)
        writeString(role)
        writeString(contentDescription)
        writeString(text)
        writeString(expectedSelected?.toString())
        writeString(expectedChecked?.toString())
        writeBounds(expectedRegion)
        output.writeBoolean(requiresClickable)
        output.writeBoolean(requiresVisible)
    }

    private fun writeBounds(bounds: NormalizedBounds?) {
        output.writeBoolean(bounds != null)
        if (bounds == null) return
        output.writeInt(bounds.left.toRawBits())
        output.writeInt(bounds.top.toRawBits())
        output.writeInt(bounds.right.toRawBits())
        output.writeInt(bounds.bottom.toRawBits())
    }

    private fun writeGestures(gestures: Map<AutomationAction, GestureProfile>) {
        gestures.toSortedMap(compareBy(AutomationAction::name)).forEach { (action, gesture) ->
            writeString("gesture:${action.name}")
            output.writeInt(gesture.point.x.toRawBits())
            output.writeInt(gesture.point.y.toRawBits())
        }
    }

    private fun writeString(value: String?) {
        if (value == null) {
            output.writeInt(-1)
            return
        }
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(encoded.size)
        output.write(encoded)
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

private const val SHA_256_HEX_LENGTH = 64
private const val UNSIGNED_BYTE_MASK = 0xFF
