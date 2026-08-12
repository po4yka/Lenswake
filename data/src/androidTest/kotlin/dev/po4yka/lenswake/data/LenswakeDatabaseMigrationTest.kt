package dev.po4yka.lenswake.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.TimeLapseSpeed
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LenswakeDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LenswakeDatabase::class.java,
    )

    private val databaseName: String
        get() = File(
            InstrumentationRegistry.getInstrumentation().context.cacheDir,
            DATABASE_FILE_NAME,
        ).absolutePath

    @Test
    fun migratesVersionOneToCurrentAndKeepsLegacyProfileReadable() {
        createVersionOneDatabase()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            LenswakeDatabase.MIGRATION_1_2,
            LenswakeDatabase.MIGRATION_2_3,
            LenswakeDatabase.MIGRATION_3_4,
            LenswakeDatabase.MIGRATION_4_5,
            LenswakeDatabase.MIGRATION_5_6,
            LenswakeDatabase.MIGRATION_6_7,
        )
        migrated.query(
            "SELECT id FROM automation_profiles WHERE id = 'profile-migration'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("profile-migration", cursor.getString(0))
        }
        migrated.close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, LenswakeDatabase::class.java, databaseName)
            .addMigrations(
                LenswakeDatabase.MIGRATION_1_2,
                LenswakeDatabase.MIGRATION_2_3,
                LenswakeDatabase.MIGRATION_3_4,
                LenswakeDatabase.MIGRATION_4_5,
                LenswakeDatabase.MIGRATION_5_6,
                LenswakeDatabase.MIGRATION_6_7,
            )
            .build()
        val (rawProfile, schedule) = runBlocking {
            RoomAutomationProfileRepository(database).get(ProfileId("profile-migration")) to
                RoomScheduleRepository(database).get(ScheduleId("schedule-migration"))
        }
        database.close()

        checkNotNull(rawProfile)
        assertEquals("profile-migration", rawProfile.id.value)
        val rawSelector = checkNotNull(rawProfile.targets[AutomationAction.SELECT_VIDEO])
            .selectors
            .single()
        assertNull(rawSelector.expectedSelected)
        assertNull(rawSelector.expectedChecked)
        assertEquals(emptyMap<Any, Any>(), rawProfile.speedTargets)
        assertEquals(emptyMap<Any, Any>(), rawProfile.stateSignals)
        checkNotNull(schedule)
        assertEquals("schedule-migration", schedule.id.value)
        assertEquals(rawProfile.id, schedule.profileId)
    }

    @Test
    fun migratesVersionTwoToThreeWithExistingExecutionOwnershipUnreleased() {
        migrationHelper.createDatabase(databaseName, 2).apply {
            execSQL(
                """
                INSERT INTO execution_sessions (
                    id, execution_key, kind, profile_id, capture_type,
                    time_lapse_speed, lens_selection,
                    expected_start_at_epoch_ms, expected_stop_at_epoch_ms,
                    status, revision, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (
                    'execution-migration', 'schedule/migration/1000', 'SCHEDULED',
                    'profile-migration', 'TIME_LAPSE', 'X120', 'REAR_MAIN',
                    1000, 2000, 'RECORDING', 3, 500, 1500
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            LenswakeDatabase.MIGRATION_2_3,
        )
        migrated.query(
            """
            SELECT status, camera_ownership_released_at_epoch_ms
            FROM execution_sessions
            WHERE id = 'execution-migration'
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("RECORDING", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
        }
        migrated.close()
    }

    @Test
    fun migratesVersionThreeProfileJsonWithoutContentLoss() {
        createVersionThreeDatabase()

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            LenswakeDatabase.MIGRATION_3_4,
        ).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, LenswakeDatabase::class.java, databaseName)
            .addMigrations(
                LenswakeDatabase.MIGRATION_3_4,
                LenswakeDatabase.MIGRATION_4_5,
                LenswakeDatabase.MIGRATION_5_6,
                LenswakeDatabase.MIGRATION_6_7,
            )
            .build()
        val (profile, schemaOneProfile) = runBlocking {
            val repository = RoomAutomationProfileRepository(database)
            repository.get(ProfileId("profile-current-json")) to
                repository.get(ProfileId("profile-schema-one"))
        }
        database.close()

        val selector = checkNotNull(profile)
            .targets
            .getValue(AutomationAction.SELECT_VIDEO)
            .selectors
            .single()
        assertEquals(true, selector.expectedSelected)
        assertEquals(true, selector.expectedChecked)

        checkNotNull(schemaOneProfile)
        val actionTarget = schemaOneProfile.targets.getValue(AutomationAction.SELECT_VIDEO)
        assertEquals(80, actionTarget.minimumScore)
        assertEquals(false, actionTarget.selectors.single().expectedSelected)
        assertNull(actionTarget.selectors.single().expectedChecked)
        val speedTarget = schemaOneProfile.speedTargets.getValue(TimeLapseSpeed.X120)
        assertEquals(81, speedTarget.minimumScore)
        assertEquals(true, speedTarget.selectors.single().expectedSelected)
        assertNull(speedTarget.selectors.single().expectedChecked)
        val stateSignal = schemaOneProfile.stateSignals.getValue(
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
        )
        assertEquals(82, stateSignal.minimumScore)
        assertEquals(false, stateSignal.selectors.single().requiresClickable)
        assertNull(stateSignal.selectors.single().expectedChecked)
    }

    @Test
    fun migratesVersionFourWithNullableMediaSaveProofColumns() {
        createVersionFourDatabase()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            LenswakeDatabase.MIGRATION_4_5,
        )
        migrated.query(
            """
            SELECT media_baseline_generation, media_store_version, media_verification_required,
                   media_saved_verified_at_epoch_ms, saved_media_generation
            FROM execution_sessions
            WHERE id = 'execution-media-migration'
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
            assertEquals(true, cursor.isNull(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(true, cursor.isNull(3))
            assertEquals(true, cursor.isNull(4))
        }
        migrated.query(
            """
            SELECT media_verification_required
            FROM execution_sessions
            WHERE id = 'execution-active-migration'
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migratesVersionFiveWithNullableRehearsalVerificationReceipt() {
        migrationHelper.createDatabase(databaseName, 5).apply {
            execSQL(
                """
                INSERT INTO execution_sessions (
                    id, execution_key, kind, profile_id, capture_type,
                    time_lapse_speed, lens_selection,
                    expected_start_at_epoch_ms, expected_stop_at_epoch_ms,
                    status, revision, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (
                    'execution-rehearsal-migration', 'rehearsal/receipt-migration', 'REHEARSAL',
                    'profile-migration', 'TIME_LAPSE', 'X120', 'REAR_MAIN',
                    1000, 2000, 'COMPLETED', 3, 500, 1500
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            LenswakeDatabase.MIGRATION_5_6,
        )
        migrated.query(
            """
            SELECT status, rehearsal_verified_at_epoch_ms
            FROM execution_sessions
            WHERE id = 'execution-rehearsal-migration'
            """.trimIndent(),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("COMPLETED", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
        }
        migrated.close()
    }

    @Test
    fun migratesVersionSixWithReadableEmptyDialogProfiles() {
        createVersionOneDatabase()
        migrationHelper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            LenswakeDatabase.MIGRATION_1_2,
            LenswakeDatabase.MIGRATION_2_3,
            LenswakeDatabase.MIGRATION_3_4,
            LenswakeDatabase.MIGRATION_4_5,
            LenswakeDatabase.MIGRATION_5_6,
        ).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            LenswakeDatabase.MIGRATION_6_7,
        )
        migrated.query(
            "SELECT dialog_profiles_json FROM automation_profiles WHERE id = 'profile-migration'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("{\"schemaVersion\":2,\"dialogs\":[]}", cursor.getString(0))
        }
        migrated.close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, LenswakeDatabase::class.java, databaseName)
            .addMigrations(LenswakeDatabase.MIGRATION_6_7)
            .build()
        val profile = runBlocking {
            RoomAutomationProfileRepository(database).get(ProfileId("profile-migration"))
        }
        database.close()

        assertEquals(emptyMap<Any, Any>(), checkNotNull(profile).dialogProfiles)
    }

    private fun createVersionOneDatabase() {
        migrationHelper.createDatabase(databaseName, 1).apply {
            insertVersionOneProfile()
            insertVersionOneSchedule()
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertVersionOneProfile() {
        execSQL(
            """
            INSERT INTO automation_profiles (
                id, device_manufacturer, device_model, android_sdk,
                android_build_fingerprint, camera_package, camera_version_code,
                locale_tag, display_width_px, display_height_px, density_dpi,
                selector_schema_version, targets_json,
                fallback_gestures_json, compatibility,
                verified_at_epoch_ms
            ) VALUES (
                'profile-migration', 'Google', 'Pixel 8 Pro', 37,
                'google/husky/test', 'com.google.android.GoogleCamera', 1,
                'en-US', 1344, 2992, 480,
                1,
                '[{"action":"SELECT_VIDEO","minimumScore":75,"selectors":[{"packageName":"com.google.android.GoogleCamera","resourceId":"camera:id/video","role":"android.widget.Button","contentDescription":"Video","text":null,"expectedRegion":null,"requiresClickable":true,"requiresVisible":true}]}]',
                '[]', 'VERIFIED', 1000
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertVersionOneSchedule() {
        execSQL(
            """
            INSERT INTO schedules (
                id, name, start_at_epoch_ms, stop_at_epoch_ms, zone_id,
                capture_type, time_lapse_speed, lens_selection, zoom_factor,
                profile_id, enabled, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (
                'schedule-migration', 'Migrated schedule', 1000, 2000, 'UTC',
                'TIME_LAPSE', 'X120', 'REAR_MAIN', NULL,
                'profile-migration', 1, 500, 600
            )
            """.trimIndent(),
        )
    }

    private fun createVersionThreeDatabase() {
        migrationHelper.createDatabase(databaseName, 3).apply {
            insertCurrentProfileJson()
            insertSchemaOneProfileJson()
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertCurrentProfileJson() {
        execSQL(
            """
            INSERT INTO automation_profiles (
                id, device_manufacturer, device_model, android_sdk,
                android_build_fingerprint, camera_package, camera_version_code,
                locale_tag, display_width_px, display_height_px, density_dpi,
                selector_schema_version, targets_json, speed_targets_json,
                state_signals_json, fallback_gestures_json, compatibility,
                verified_at_epoch_ms
            ) VALUES (
                'profile-current-json', 'Google', 'Pixel 8 Pro', 37,
                'google/husky/test', 'com.google.android.GoogleCamera', 1,
                'en-US', 1344, 2992, 480,
                3,
                '{"schemaVersion":2,"targets":[{"action":"SELECT_VIDEO","minimumScore":90,"selectors":[{"packageName":"com.google.android.GoogleCamera","resourceId":"camera:id/video","role":"android.widget.Button","contentDescription":"Video","text":null,"expectedSelected":true,"expectedChecked":true,"expectedRegion":null,"requiresClickable":true,"requiresVisible":true}]}]}',
                '{"schemaVersion":2,"targets":[]}',
                '{"schemaVersion":2,"signals":[]}',
                '[]', 'NEEDS_REHEARSAL', NULL
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertSchemaOneProfileJson() {
        execSQL(
            """
            INSERT INTO automation_profiles (
                id, device_manufacturer, device_model, android_sdk,
                android_build_fingerprint, camera_package, camera_version_code,
                locale_tag, display_width_px, display_height_px, density_dpi,
                selector_schema_version, targets_json, speed_targets_json,
                state_signals_json, fallback_gestures_json, compatibility,
                verified_at_epoch_ms
            ) VALUES (
                'profile-schema-one', 'Google', 'Pixel 8 Pro', 37,
                'google/husky/test', 'com.google.android.GoogleCamera', 1,
                'en-US', 1344, 2992, 480,
                1,
                '{"schemaVersion":1,"targets":[{"action":"SELECT_VIDEO","minimumScore":80,"selectors":[{"packageName":"com.google.android.GoogleCamera","resourceId":"camera:id/video","role":"android.widget.Button","contentDescription":"Video","text":null,"expectedSelected":false,"expectedRegion":null,"requiresClickable":true,"requiresVisible":true}]}]}',
                '{"schemaVersion":1,"targets":[{"speed":"X120","minimumScore":81,"selectors":[{"packageName":"com.google.android.GoogleCamera","resourceId":"camera:id/x120","role":"android.widget.Button","contentDescription":"120x","text":null,"expectedSelected":true,"expectedRegion":null,"requiresClickable":true,"requiresVisible":true}]}]}',
                '{"schemaVersion":1,"signals":[{"signal":"VIDEO_MODE_ACTIVE","minimumScore":82,"selectors":[{"packageName":"com.google.android.GoogleCamera","resourceId":"camera:id/video","role":"android.widget.Button","contentDescription":"Video","text":null,"expectedSelected":true,"expectedRegion":null,"requiresClickable":false,"requiresVisible":true}]}]}',
                '[]', 'NEEDS_REHEARSAL', NULL
            )
            """.trimIndent(),
        )
    }

    private fun createVersionFourDatabase() {
        migrationHelper.createDatabase(databaseName, 4).apply {
            insertCompletedExecution()
            insertActiveExecution()
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertCompletedExecution() {
        execSQL(
            """
            INSERT INTO execution_sessions (
                id, execution_key, kind, profile_id, capture_type,
                time_lapse_speed, lens_selection,
                expected_start_at_epoch_ms, expected_stop_at_epoch_ms,
                status, revision, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (
                'execution-media-migration', 'rehearsal/media-migration', 'REHEARSAL',
                'profile-migration', 'TIME_LAPSE', 'X120', 'REAR_MAIN',
                1000, 2000, 'COMPLETED', 3, 500, 1500
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertActiveExecution() {
        execSQL(
            """
            INSERT INTO execution_sessions (
                id, execution_key, kind, profile_id, capture_type,
                time_lapse_speed, lens_selection,
                expected_start_at_epoch_ms, expected_stop_at_epoch_ms,
                status, record_action_at_epoch_ms, revision,
                created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (
                'execution-active-migration', 'schedule/active-migration', 'SCHEDULED',
                'profile-migration', 'TIME_LAPSE', 'X120', 'REAR_MAIN',
                1000, 2000, 'RECORDING', 1200, 3, 500, 1500
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val DATABASE_FILE_NAME = "lenswake-migration-test"
    }
}
