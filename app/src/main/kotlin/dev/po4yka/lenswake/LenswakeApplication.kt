package dev.po4yka.lenswake

import android.app.Application
import dev.po4yka.lenswake.alarm.AlarmComponentProvider
import dev.po4yka.lenswake.alarm.AlarmRecoveryCoordinator
import dev.po4yka.lenswake.alarm.AlarmTriggerCoordinator
import dev.po4yka.lenswake.di.ApplicationGraph

class LenswakeApplication : Application(), AlarmComponentProvider {
    val graph: ApplicationGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ApplicationGraph(this)
    }

    override val alarmTriggerCoordinator: AlarmTriggerCoordinator
        get() = graph.alarmTriggerCoordinator

    override val alarmRecoveryCoordinator: AlarmRecoveryCoordinator
        get() = graph.alarmRecoveryCoordinator
}
