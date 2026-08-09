package dev.po4yka.lenswake.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LenswakeDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LenswakeDatabase::class.java,
    )

    @Test
    fun migratesVersionOneToVersionTwoWithoutDataDestruction() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).apply {
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
                    'profile-migration', 'Google', 'Pixel 8 Pro', 37,
                    'google/husky/test', 'com.google.android.GoogleCamera', 1,
                    'en-US', 1344, 2992, 480,
                    1, '[]', '[]', '[]', '[]', 'VERIFIED', 1000
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            LenswakeDatabase.MIGRATION_1_2,
        )
        migrated.query(
            "SELECT id FROM automation_profiles WHERE id = 'profile-migration'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("profile-migration", cursor.getString(0))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "lenswake-migration-test"
    }
}
