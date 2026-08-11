package dev.po4yka.lenswake.integration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.RecordingMediaBaseline
import dev.po4yka.lenswake.automation.RecordingMediaPort
import dev.po4yka.lenswake.automation.SavedRecordingEvidence
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE

/** Correlates a recording with published, Pixel Camera-owned video media without exposing paths. */
class AndroidRecordingMediaPort internal constructor(
    context: Context,
    private val hasVideoReadPermission: () -> Boolean,
) : RecordingMediaPort {
    private val applicationContext = context.applicationContext

    constructor(context: Context) : this(
        context = context,
        hasVideoReadPermission = {
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) == PackageManager.PERMISSION_GRANTED
        },
    )

    override suspend fun captureBaseline(): PortResult<RecordingMediaBaseline> = mediaBoundary(
        failureCode = AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
        failureMessage = "MediaStore generation could not be read",
    ) {
        val permissionFailure = requireVideoReadPermission()
        if (permissionFailure != null) {
            permissionFailure
        } else {
            val version = currentVersion()
            val generation = MediaStore.getGeneration(
                applicationContext,
                MediaStore.VOLUME_EXTERNAL_PRIMARY,
            )
            if (currentVersion() != version) {
                PortResult.Unavailable(
                    AutomationFailure(
                        code = AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                        message = "MediaStore changed while the recording baseline was captured",
                    ),
                )
            } else {
                PortResult.Observed(
                    RecordingMediaBaseline(
                        generation = generation,
                        version = version,
                    ),
                )
            }
        }
    }

    override suspend fun findSavedRecording(
        baseline: RecordingMediaBaseline,
    ): PortResult<SavedRecordingEvidence?> = mediaBoundary(
        failureCode = AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
        failureMessage = "Saved recording could not be queried from MediaStore",
    ) {
        val permissionFailure = requireVideoReadPermission()
        if (permissionFailure != null) {
            permissionFailure
        } else {
            val versionBeforeQuery = currentVersion()
            if (versionBeforeQuery != baseline.version) {
                PortResult.Unavailable(
                    AutomationFailure(
                        code = AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                        message = "MediaStore changed after the recording baseline was captured",
                        context = mapOf(
                            "baselineVersion" to baseline.version,
                            "currentVersion" to versionBeforeQuery,
                        ),
                    ),
                )
            } else {
                querySavedRecording(baseline, versionBeforeQuery)
            }
        }
    }

    private fun querySavedRecording(
        baseline: RecordingMediaBaseline,
        versionBeforeQuery: String,
    ): PortResult<SavedRecordingEvidence?> {
        val projection = arrayOf(
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Video.VideoColumns.DURATION,
        )
        val selection = listOf(
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            "${MediaStore.MediaColumns.GENERATION_ADDED} > ?",
            "${MediaStore.MediaColumns.IS_PENDING} = 0",
            "${MediaStore.MediaColumns.SIZE} > 0",
            "${MediaStore.Video.VideoColumns.DURATION} > 0",
        ).joinToString(separator = " AND ")
        val result = applicationContext.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection,
            selection,
            arrayOf(PIXEL_CAMERA_PACKAGE, baseline.generation.toString()),
            "${MediaStore.MediaColumns.GENERATION_ADDED} ASC",
        )?.use(::uniqueSavedRecording) ?: PortResult.Observed(null)
        return if (currentVersion() == versionBeforeQuery) {
            result
        } else {
            PortResult.Unavailable(
                AutomationFailure(
                    code = AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                    message = "MediaStore changed while saved recording evidence was queried",
                ),
            )
        }
    }

    private suspend fun <T> mediaBoundary(
        failureCode: AutomationFailureCode,
        failureMessage: String,
        operation: suspend () -> PortResult<T>,
    ): PortResult<T> {
        val attempt = runSuspendCatchingPreservingCancellation(operation)
        val failure = attempt.exceptionOrNull()
        return when (failure) {
            null -> attempt.getOrThrow()
            is SecurityException -> missingReadPermission(failure)
            else -> PortResult.Unavailable(
                AutomationFailure(
                    code = failureCode,
                    message = failureMessage,
                    context = mapOf("exception" to failure.javaClass.simpleName),
                ),
            )
        }
    }

    private fun requireVideoReadPermission(): PortResult.Unavailable? =
        if (hasVideoReadPermission()) {
            null
        } else {
            missingReadPermission()
        }

    private fun missingReadPermission(error: SecurityException? = null): PortResult.Unavailable =
        PortResult.Unavailable(
            AutomationFailure(
                code = AutomationFailureCode.MEDIA_READ_PERMISSION_MISSING,
                message = "Video media read permission is not granted",
                context = error?.let { mapOf("exception" to it.javaClass.simpleName) } ?: emptyMap(),
            ),
        )

    private fun currentVersion(): String =
        MediaStore.getVersion(applicationContext, MediaStore.VOLUME_EXTERNAL_PRIMARY)
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("External primary MediaStore version is unavailable")
}

internal fun uniqueSavedRecording(cursor: Cursor): PortResult<SavedRecordingEvidence?> {
    if (!cursor.moveToFirst()) return PortResult.Observed(null)
    val evidence = SavedRecordingEvidence(
        generationAdded = cursor.getLong(0),
        sizeBytes = cursor.getLong(1),
        durationMillis = cursor.getLong(2),
    )
    if (cursor.moveToNext()) {
        return PortResult.Unavailable(
            AutomationFailure(
                code = AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
                message = "Multiple Pixel Camera videos followed the recording baseline",
            ),
        )
    }
    return PortResult.Observed(evidence)
}
