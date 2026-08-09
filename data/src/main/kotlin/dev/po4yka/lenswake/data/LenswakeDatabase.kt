package dev.po4yka.lenswake.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import dev.po4yka.lenswake.data.internal.dao.AutomationProfileDao
import dev.po4yka.lenswake.data.internal.dao.EnvironmentSnapshotDao
import dev.po4yka.lenswake.data.internal.dao.ExecutionDao
import dev.po4yka.lenswake.data.internal.dao.ScheduleDao
import dev.po4yka.lenswake.data.internal.entity.AutomationProfileEntity
import dev.po4yka.lenswake.data.internal.entity.EnvironmentSnapshotEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionEventEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionSessionEntity
import dev.po4yka.lenswake.data.internal.entity.ScheduleEntity

@Database(
    entities = [
        ScheduleEntity::class,
        AutomationProfileEntity::class,
        ExecutionSessionEntity::class,
        ExecutionEventEntity::class,
        EnvironmentSnapshotEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LenswakeDatabase : RoomDatabase() {
    internal abstract fun scheduleDao(): ScheduleDao

    internal abstract fun automationProfileDao(): AutomationProfileDao

    internal abstract fun executionDao(): ExecutionDao

    internal abstract fun environmentSnapshotDao(): EnvironmentSnapshotDao

    companion object {
        const val DATABASE_NAME: String = "lenswake.db"

        val MIGRATION_1_2: Migration = Migration(1, 2) { database ->
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `environment_snapshots` (
                    `id` TEXT NOT NULL,
                    `session_id` TEXT NOT NULL,
                    `captured_at_epoch_ms` INTEGER NOT NULL,
                    `lenswake_version` TEXT NOT NULL,
                    `device_manufacturer` TEXT NOT NULL,
                    `device_model` TEXT NOT NULL,
                    `android_sdk` INTEGER NOT NULL,
                    `android_build_fingerprint` TEXT,
                    `camera_package` TEXT NOT NULL,
                    `camera_version_code` INTEGER NOT NULL,
                    `locale_tag` TEXT NOT NULL,
                    `display_width_px` INTEGER NOT NULL,
                    `display_height_px` INTEGER NOT NULL,
                    `density_dpi` INTEGER NOT NULL,
                    `accessibility_status` TEXT NOT NULL,
                    `privileged_bridge_status` TEXT NOT NULL,
                    `screen_interactive` INTEGER NOT NULL,
                    `keyguard_locked` INTEGER NOT NULL,
                    `battery_percent` INTEGER,
                    `charging` INTEGER,
                    `available_storage_bytes` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`session_id`) REFERENCES `execution_sessions`(`id`)
                        ON UPDATE RESTRICT ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_environment_snapshots_session_id`
                ON `environment_snapshots` (`session_id`)
                """.trimIndent(),
            )
        }

        fun create(context: Context): LenswakeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LenswakeDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2)
                .build()
    }
}
