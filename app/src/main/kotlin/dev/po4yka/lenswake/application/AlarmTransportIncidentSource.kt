package dev.po4yka.lenswake.application

import android.content.Context
import android.content.SharedPreferences
import dev.po4yka.lenswake.alarm.AlarmTransportFailureCode
import dev.po4yka.lenswake.alarm.AlarmTransportFailureMarker
import dev.po4yka.lenswake.alarm.SharedPreferencesAlarmTransportFailurePersistence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only, durable transport failures for Diagnostics; resolution remains owned by alarm recovery. */
internal interface AlarmTransportIncidentSource {
    val incidents: StateFlow<List<AlarmTransportIncident>>
}

internal object EmptyAlarmTransportIncidentSource : AlarmTransportIncidentSource {
    override val incidents: StateFlow<List<AlarmTransportIncident>> = MutableStateFlow(emptyList())
}

internal data class AlarmTransportIncident(
    val id: String,
    val code: AlarmTransportFailureCode,
    val title: String,
    val detail: String,
    val recordedAtEpochMillis: Long,
    val action: AlarmTransportIncidentAction?,
)

internal enum class AlarmTransportIncidentAction {
    OPEN_PIXEL_CAMERA,
}

/**
 * Uses the same device-protected preference file as alarm delivery, so markers remain visible
 * before notification permission is granted and across process recreation.
 */
internal class SharedPreferencesAlarmTransportIncidentSource(
    context: Context,
    preferenceName: String = SharedPreferencesAlarmTransportFailurePersistence.PREFERENCE_NAME,
) : AlarmTransportIncidentSource {
    private val storageContext = context.createDeviceProtectedStorageContext()
    private val persistence = SharedPreferencesAlarmTransportFailurePersistence(storageContext, preferenceName)
    private val preferences = storageContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )
    private val mutableIncidents = MutableStateFlow(readIncidents())

    override val incidents: StateFlow<List<AlarmTransportIncident>> = mutableIncidents.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableIncidents.value = readIncidents()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun readIncidents(): List<AlarmTransportIncident> = persistence.markers()
        .map(AlarmTransportFailureMarker::toIncident)
        .sortedWith(compareByDescending<AlarmTransportIncident> { it.recordedAtEpochMillis }.thenBy { it.id })
}

private fun AlarmTransportFailureMarker.toIncident(): AlarmTransportIncident = AlarmTransportIncident(
    id = id,
    code = code,
    title = title,
    detail = message,
    recordedAtEpochMillis = recordedAtEpochMillis,
    action = if (cameraAction) AlarmTransportIncidentAction.OPEN_PIXEL_CAMERA else null,
)
