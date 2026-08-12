package dev.po4yka.lenswake.di

import android.app.Application
import dev.po4yka.lenswake.BuildConfig
import dev.po4yka.lenswake.alarm.AlarmManagerRecordingScheduler
import dev.po4yka.lenswake.alarm.AlarmManagerRehearsalStopScheduler
import dev.po4yka.lenswake.alarm.InterruptedScheduledSessionRecovery
import dev.po4yka.lenswake.alarm.MutexAlarmRecoveryScheduler
import dev.po4yka.lenswake.alarm.PreflightAlarmRecoveryReadiness
import dev.po4yka.lenswake.alarm.RehearsalStopTriggerCoordinator
import dev.po4yka.lenswake.alarm.SchedulerAlarmRecoveryCoordinator
import dev.po4yka.lenswake.application.DefaultAlarmTriggerCoordinator
import dev.po4yka.lenswake.application.DefaultRehearsalCoordinator
import dev.po4yka.lenswake.application.DefaultRehearsalStopTriggerCoordinator
import dev.po4yka.lenswake.application.AlarmTransportIncidentSource
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfile
import dev.po4yka.lenswake.application.ArtifactBoundAutomationProfileRepository
import dev.po4yka.lenswake.application.InstallReleaseCertification
import dev.po4yka.lenswake.application.RehearsalCoordinator
import dev.po4yka.lenswake.application.RehearsalStopWorkflow
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.application.ScheduleWorkflow
import dev.po4yka.lenswake.application.MutexRecordingScheduler
import dev.po4yka.lenswake.application.SharedPreferencesAlarmTransportIncidentSource
import dev.po4yka.lenswake.automation.DefaultAutomationEngine
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraEnvironment
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
import dev.po4yka.lenswake.integration.AndroidRecordingMediaPort
import dev.po4yka.lenswake.integration.AndroidRuntimePreflightProbe
import dev.po4yka.lenswake.integration.AndroidLenswakeArtifactIdentity
import dev.po4yka.lenswake.integration.AndroidReleaseCertificationBundleReader
import dev.po4yka.lenswake.integration.PixelCameraAccessibilityPort
import dev.po4yka.lenswake.platform.SecurePixelCameraLauncher
import dev.po4yka.lenswake.platform.AndroidDeviceWakeController
import dev.po4yka.lenswake.privileged.UnavailablePrivilegedBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** Small explicit process-wide composition root; no dependency reports synthetic availability. */
class ApplicationGraph(application: Application) {
    internal val alarmTransportIncidentSource: AlarmTransportIncidentSource =
        SharedPreferencesAlarmTransportIncidentSource(application)
    val database: LenswakeDatabase = LenswakeDatabase.create(application)
    val scheduleRepository: ScheduleRepository = RoomScheduleRepository(database)
    private val lenswakeArtifactIdentity = AndroidLenswakeArtifactIdentity(
        application,
        BuildConfig.RELEASE_SIGNING_CERTIFICATE_SHA256,
    )
    val profileRepository: AutomationProfileRepository = ArtifactBoundAutomationProfileRepository(
        RoomAutomationProfileRepository(database),
        lenswakeArtifactIdentity::releaseApkSha256,
    )
    private val roomExecutionRepository = RoomExecutionRepository(database)
    val executionRepository: ExecutionRepository = roomExecutionRepository
    val environmentSnapshotRepository: EnvironmentSnapshotRepository = roomExecutionRepository
    val clock: LenswakeClock = SystemLenswakeClock()
    private val scheduleMutationMutex = Mutex()
    private val alarmManagerRecordingScheduler: RecordingScheduler = AlarmManagerRecordingScheduler(
        context = application,
        scheduleRepository = scheduleRepository,
        executionRepository = executionRepository,
        clock = clock,
    )
    val recordingScheduler: RecordingScheduler = MutexRecordingScheduler(
        delegate = alarmManagerRecordingScheduler,
        mutex = scheduleMutationMutex,
    )
    private val unavailablePrivilegedBridge = UnavailablePrivilegedBridge()
    private val cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(application)
    private val deviceWakeController = AndroidDeviceWakeController(application)
    val installKnownPixelCameraProfile = InstallKnownPixelCameraProfile(
        environmentProbe = cameraEnvironmentProbe::inspect,
        profileRepository = profileRepository,
    )
    val installReleaseCertification = InstallReleaseCertification(
        bundleReader = AndroidReleaseCertificationBundleReader(
            application,
            BuildConfig.RELEASE_SIGNING_CERTIFICATE_SHA256,
        ),
        environmentProbe = { cameraEnvironmentProbe.inspect().observedEnvironmentOrThrow() },
        profileRepository = profileRepository,
    )
    val runtimePreflightProbe: RuntimePreflightProbe = AndroidRuntimePreflightProbe(
        context = application,
        cameraEnvironmentProbe = cameraEnvironmentProbe,
        executionRepository = executionRepository,
        deviceWakeController = deviceWakeController,
    )
    val scheduleWorkflow = ScheduleWorkflow(
        scheduleRepository = scheduleRepository,
        executionRepository = executionRepository,
        profileRepository = profileRepository,
        scheduler = alarmManagerRecordingScheduler,
        clock = clock,
        preflightProbe = runtimePreflightProbe,
        mutationMutex = scheduleMutationMutex,
    )
    private val deviceControl = AndroidDeviceControlPort(
        context = application,
        wakeController = deviceWakeController,
    )
    private val pixelCamera = PixelCameraAccessibilityPort(
        launcher = SecurePixelCameraLauncher(application),
        selectorMatcher = SelectorMatcher(),
        environmentProbe = cameraEnvironmentProbe,
    )
    private val recordingMedia = AndroidRecordingMediaPort(application)
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
        recordingMedia = recordingMedia,
        clock = clock,
    )
    private val rehearsalMutex = Mutex()
    private val rehearsalStopBackstop = AlarmManagerRehearsalStopScheduler(
        context = application,
        executionRepository = executionRepository,
        clock = clock,
    )
    private val rehearsalStopWorkflow = RehearsalStopWorkflow(
        executionRepository = executionRepository,
        environmentSnapshotRepository = environmentSnapshotRepository,
        profileRepository = profileRepository,
        environmentProbe = cameraEnvironmentProbe::inspect,
        automationEngine = automationEngine,
        backstop = rehearsalStopBackstop,
        clock = clock,
        mutex = rehearsalMutex,
    )
    val rehearsalCoordinator: RehearsalCoordinator = DefaultRehearsalCoordinator(
        profileRepository = profileRepository,
        executionRepository = executionRepository,
        environmentSnapshotRepository = environmentSnapshotRepository,
        environmentSnapshotCollector = environmentSnapshotCollector,
        environmentProbe = cameraEnvironmentProbe::inspect,
        automationEngine = automationEngine,
        backstop = rehearsalStopBackstop,
        stopWorkflow = rehearsalStopWorkflow,
        clock = clock,
        mutex = rehearsalMutex,
    )
    val rehearsalStopTriggerCoordinator: RehearsalStopTriggerCoordinator =
        DefaultRehearsalStopTriggerCoordinator(rehearsalStopWorkflow)

    val alarmTriggerCoordinator = DefaultAlarmTriggerCoordinator(
        scheduleRepository = scheduleRepository,
        executionRepository = executionRepository,
        environmentSnapshotRepository = environmentSnapshotRepository,
        environmentSnapshotCollector = environmentSnapshotCollector,
        automationEngine = automationEngine,
        startReadiness = { session ->
            val profile = profileRepository.get(session.profileId)
            if (profile == null) {
                Result.failure(IllegalStateException("Selected Pixel Camera profile is missing"))
            } else {
                PreflightAlarmRecoveryReadiness {
                    runtimePreflightProbe.inspectForCapture(listOf(profile), session.capture)
                }.check()
            }
        },
        clock = clock,
        scheduleMutationMutex = scheduleMutationMutex,
    )
    val alarmRecoveryCoordinator = SchedulerAlarmRecoveryCoordinator(
        scheduler = recordingScheduler,
        additionalSchedulers = listOf(
            MutexAlarmRecoveryScheduler(rehearsalStopBackstop, rehearsalMutex),
        ),
        interruptedSessionRecovery = InterruptedScheduledSessionRecovery {
            runCatching {
                executionRepository.reconcileInterruptedScheduledSessions(clock.now())
                Unit
            }
        },
        readiness = PreflightAlarmRecoveryReadiness {
            runtimePreflightProbe.inspect(profileRepository.observeProfiles().first())
        },
    )
}

private fun PortResult<PixelCameraEnvironment>.observedEnvironmentOrThrow() =
    when (this) {
        is PortResult.Observed -> value
        is PortResult.Unavailable -> error(failure.message)
    }
