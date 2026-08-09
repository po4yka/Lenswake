package dev.po4yka.lenswake.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.po4yka.lenswake.data.internal.dao.AutomationProfileDao
import dev.po4yka.lenswake.data.internal.dao.ExecutionDao
import dev.po4yka.lenswake.data.internal.dao.ScheduleDao
import dev.po4yka.lenswake.data.internal.entity.AutomationProfileEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionEventEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionSessionEntity
import dev.po4yka.lenswake.data.internal.entity.ScheduleEntity

@Database(
    entities = [
        ScheduleEntity::class,
        AutomationProfileEntity::class,
        ExecutionSessionEntity::class,
        ExecutionEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LenswakeDatabase : RoomDatabase() {
    internal abstract fun scheduleDao(): ScheduleDao

    internal abstract fun automationProfileDao(): AutomationProfileDao

    internal abstract fun executionDao(): ExecutionDao

    companion object {
        const val DATABASE_NAME: String = "lenswake.db"

        fun create(context: Context): LenswakeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LenswakeDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
