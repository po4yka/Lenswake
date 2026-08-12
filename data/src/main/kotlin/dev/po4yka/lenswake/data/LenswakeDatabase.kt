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
import dev.po4yka.lenswake.data.internal.mapping.ProfileJsonMigration

@Database(
    entities = [
        ScheduleEntity::class,
        AutomationProfileEntity::class,
        ExecutionSessionEntity::class,
        ExecutionEventEntity::class,
        EnvironmentSnapshotEntity::class,
    ],
    version = 8,
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
            val profileColumns = buildSet {
                database.query("PRAGMA table_info(`automation_profiles`)").use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }
            if ("speed_targets_json" !in profileColumns) {
                database.execSQL(
                    "ALTER TABLE `automation_profiles` " +
                        "ADD COLUMN `speed_targets_json` TEXT NOT NULL DEFAULT '[]'",
                )
            }
            if ("state_signals_json" !in profileColumns) {
                database.execSQL(
                    "ALTER TABLE `automation_profiles` " +
                        "ADD COLUMN `state_signals_json` TEXT NOT NULL DEFAULT '[]'",
                )
            }
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

        val MIGRATION_2_3: Migration = Migration(2, 3) { database ->
            database.execSQL(
                "ALTER TABLE `execution_sessions` " +
                    "ADD COLUMN `camera_ownership_released_at_epoch_ms` INTEGER DEFAULT NULL",
            )
        }

        val MIGRATION_3_4: Migration = Migration(3, 4) { database ->
            database.query(
                """
                SELECT id, targets_json, speed_targets_json, state_signals_json
                FROM automation_profiles
                """.trimIndent(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    database.execSQL(
                        """
                        UPDATE automation_profiles
                        SET targets_json = ?, speed_targets_json = ?, state_signals_json = ?
                        WHERE id = ?
                        """.trimIndent(),
                        arrayOf(
                            ProfileJsonMigration.targets(cursor.getString(1)),
                            ProfileJsonMigration.speedTargets(cursor.getString(2)),
                            ProfileJsonMigration.stateSignals(cursor.getString(3)),
                            cursor.getString(0),
                        ),
                    )
                }
            }
        }

        val MIGRATION_4_5: Migration = Migration(4, 5) { database ->
            database.execSQL(
                "ALTER TABLE `execution_sessions` " +
                    "ADD COLUMN `media_baseline_generation` INTEGER DEFAULT NULL",
            )
            database.execSQL(
                "ALTER TABLE `execution_sessions` " +
                    "ADD COLUMN `media_store_version` TEXT DEFAULT NULL",
            )
            database.execSQL(
                "ALTER TABLE `execution_sessions` " +
                    "ADD COLUMN `media_verification_required` INTEGER NOT NULL DEFAULT 1",
            )
            database.execSQL(
                "UPDATE `execution_sessions` SET `media_verification_required` = 0 " +
                    "WHERE `record_action_at_epoch_ms` IS NOT NULL",
            )
            database.execSQL(
                "ALTER TABLE `execution_sessions` " +
                    "ADD COLUMN `media_saved_verified_at_epoch_ms` INTEGER DEFAULT NULL",
            )
            database.execSQL(
                "ALTER TABLE `execution_sessions` " +
                    "ADD COLUMN `saved_media_generation` INTEGER DEFAULT NULL",
            )
        }

        val MIGRATION_5_6: Migration = Migration(5, 6) { database ->
            database.execSQL(
                "ALTER TABLE `execution_sessions` " +
                    "ADD COLUMN `rehearsal_verified_at_epoch_ms` INTEGER DEFAULT NULL",
            )
        }

        val MIGRATION_6_7: Migration = Migration(6, 7) { database ->
            database.execSQL(
                "ALTER TABLE `automation_profiles` " +
                    "ADD COLUMN `dialog_profiles_json` TEXT NOT NULL " +
                    "DEFAULT '{\"schemaVersion\":2,\"dialogs\":[]}'",
            )
        }

        val MIGRATION_7_8: Migration = Migration(7, 8) { database ->
            database.execSQL(
                "ALTER TABLE `automation_profiles` ADD COLUMN `device_codename` " +
                    "TEXT NOT NULL DEFAULT 'legacy-unknown'",
            )
            database.execSQL(
                "ALTER TABLE `automation_profiles` ADD COLUMN `font_scale` REAL NOT NULL DEFAULT -1.0",
            )
            database.execSQL(
                "ALTER TABLE `automation_profiles` ADD COLUMN `display_orientation` " +
                    "TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
            )
            database.execSQL(
                "ALTER TABLE `automation_profiles` ADD COLUMN `support_tier` " +
                    "TEXT NOT NULL DEFAULT 'EXPERIMENTAL'",
            )
            database.execSQL(
                "ALTER TABLE `automation_profiles` ADD COLUMN `profile_source` " +
                    "TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
            )
            database.execSQL(
                "ALTER TABLE `automation_profiles` ADD COLUMN `selector_template_id` " +
                    "TEXT NOT NULL DEFAULT 'legacy'",
            )
            database.execSQL(
                "ALTER TABLE `automation_profiles` ADD COLUMN `selector_template_version` " +
                    "INTEGER NOT NULL DEFAULT 1",
            )
            addEnvironmentV5Columns(database, "automation_profiles")
            addCaptureV5Columns(database, "schedules")
            addCaptureV5Columns(database, "execution_sessions")
            addProfileProvenanceColumns(database, "schedules")
            addProfileProvenanceColumns(database, "execution_sessions")
            database.execSQL(
                "ALTER TABLE `schedules` ADD COLUMN `experimental_risk_accepted` " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "ALTER TABLE `environment_snapshots` ADD COLUMN `device_codename` " +
                    "TEXT NOT NULL DEFAULT 'legacy-unknown'",
            )
            database.execSQL(
                "ALTER TABLE `environment_snapshots` ADD COLUMN `font_scale` " +
                    "REAL NOT NULL DEFAULT -1.0",
            )
            database.execSQL(
                "ALTER TABLE `environment_snapshots` ADD COLUMN `display_orientation` " +
                    "TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
            )
            addEnvironmentV5Columns(database, "environment_snapshots")
            addProfileProvenanceColumns(database, "environment_snapshots")
            database.execSQL(
                "UPDATE `automation_profiles` SET `compatibility` = 'INCOMPATIBLE' " +
                    "WHERE `selector_schema_version` < 5",
            )
            database.execSQL(
                "UPDATE `schedules` SET `enabled` = 0 WHERE `profile_id` IN " +
                    "(SELECT `id` FROM `automation_profiles` WHERE `selector_schema_version` < 5)",
            )
        }

        private fun addCaptureV5Columns(
            database: androidx.sqlite.db.SupportSQLiteDatabase,
            table: String,
        ) {
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `video_resolution` " +
                    "TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
            )
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `video_frame_rate` " +
                    "TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
            )
        }

        private fun addEnvironmentV5Columns(
            database: androidx.sqlite.db.SupportSQLiteDatabase,
            table: String,
        ) {
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `camera_signing_certificate_sha256` " +
                    "TEXT NOT NULL DEFAULT 'legacy-unknown'",
            )
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `default_display_configuration` " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
        }

        private fun addProfileProvenanceColumns(
            database: androidx.sqlite.db.SupportSQLiteDatabase,
            table: String,
        ) {
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `profile_support_tier` " +
                    "TEXT NOT NULL DEFAULT 'EXPERIMENTAL'",
            )
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `profile_source` " +
                    "TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
            )
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `profile_template_id` " +
                    "TEXT NOT NULL DEFAULT 'legacy'",
            )
            database.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `profile_template_version` " +
                    "INTEGER NOT NULL DEFAULT 1",
            )
        }

        fun create(context: Context): LenswakeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LenswakeDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )
                .build()
    }
}
