package dev.po4yka.lenswake.di

import android.app.Application
import dev.po4yka.lenswake.alarm.AlarmManagerRecordingScheduler
import dev.po4yka.lenswake.alarm.SchedulerAlarmRecoveryCoordinator
import dev.po4yka.lenswake.application.DefaultAlarmTriggerCoordinator
import dev.po4yka.lenswake.automation.DefaultAutomationEngine
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SystemLenswakeClock
import dev.po4yka.lenswake.data.LenswakeDatabase
import dev.po4yka.lenswake.data.RoomAutomationProfileRepository
import dev.po4yka.lenswake.data.RoomExecutionRepository
import dev.po4yka.lenswake.data.RoomScheduleRepository
import dev.po4yka.lenswake.integration.AndroidDeviceControlPort
import dev.po4yka.lenswake.integration.AndroidEnvironmentSnapshotCollector
import dev.po4yka.lenswake.integration.AndroidPixelCameraEnvironmentProbe
import dev.po4yka.lenswake.integration.PixelCameraAccessibilityPort
import dev.po4yka.lenswake.platform.SecurePixelCameraLauncher
import dev.po4yka.lenswake.platform.UnavailableDeviceWakeController
import dev.po4yka.lenswake.privileged.UnavailablePrivilegedBridge

/** Small explicit process-wide composition root; no dependency reports synthetic availability. */
class ApplicationGraph(application: Application) {
    val database: LenswakeDatabase = LenswakeDatabase.create(application)
    val scheduleRepository: ScheduleRepository = RoomScheduleRepository(database)
    val profileRepository: AutomationProfileRepository = RoomAutomationProfileRepository(database)
    private val roomExecutionRepository = RoomExecutionRepository(database)
    val executionRepository: ExecutionRepository = roomExecutionRepository
    val environmentSnapshotRepository: EnvironmentSnapshotRepository = roomExecutionRepository
    val clock: LenswakeClock = SystemLenswakeClock()
    val recordingScheduler: RecordingScheduler = AlarmManagerRecordingScheduler(
        context = application,
        scheduleRepository = scheduleRepository,
        clock = clock,
    )

    private val unavailablePrivilegedBridge = UnavailablePrivilegedBridge()
    private val cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(application)
    private val deviceControl = AndroidDeviceControlPort(
        context = application,
        wakeController = UnavailableDeviceWakeController(),
    )
    private val pixelCamera = PixelCameraAccessibilityPort(
        launcher = SecurePixelCameraLauncher(application),
        selectorMatcher = SelectorMatcher(),
        environmentProbe = cameraEnvironmentProbe,
    )
    private val environmentSnapshotCollector = AndroidEnvironmentSnapshotCollector(
        context = application,
        cameraEnvironmentProbe = cameraEnvironmentProbe,
        privilegedBridge = unavailablePrivilegedBridge,
        clock = clock,
    )
    private val automationEngine = DefaultAutomationEngine(
        executionRepository = executionRepository,
        profileRepository = profileRepository,
        deviceControl = deviceControl,
        pixelCamera = pixelCamera,
        clock = clock,
    )

    val alarmTriggerCoordinator = DefaultAlarmTriggerCoordinator(
        scheduleRepository = scheduleRepository,
        executionRepository = executionRepository,
        environmentSnapshotRepository = environmentSnapshotRepository,
        environmentSnapshotCollector = environmentSnapshotCollector,
        automationEngine = automationEngine,
        clock = clock,
    )
    val alarmRecoveryCoordinator = SchedulerAlarmRecoveryCoordinator(recordingScheduler)
}
