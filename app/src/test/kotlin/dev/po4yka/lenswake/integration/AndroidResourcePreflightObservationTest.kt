package dev.po4yka.lenswake.integration

import android.os.BatteryManager
import dev.po4yka.lenswake.core.PreflightStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AndroidResourcePreflightObservationTest {
    @Test
    fun externalPowerRemainsAvailableWhenBatteryChargingIsPaused() {
        listOf(
            BatteryManager.BATTERY_PLUGGED_AC,
            BatteryManager.BATTERY_PLUGGED_USB,
            BatteryManager.BATTERY_PLUGGED_WIRELESS,
            BatteryManager.BATTERY_PLUGGED_DOCK,
        ).forEach { plugged ->
            assertEquals(PreflightStatus.PASSED, externalPowerObservation(plugged).status)
        }
        assertEquals(PreflightStatus.FAILED, externalPowerObservation(0).status)
    }

    @Test
    fun batteryUsesDocumentedThirtyPercentMinimumAndRejectsInvalidReadings() {
        assertEquals(PreflightStatus.FAILED, batteryObservation(29).status)
        assertEquals(PreflightStatus.PASSED, batteryObservation(30).status)
        assertEquals(PreflightStatus.PASSED, batteryObservation(100).status)
        assertEquals(PreflightStatus.UNKNOWN, batteryObservation(-1).status)
        assertEquals(PreflightStatus.UNKNOWN, batteryObservation(null).status)
    }

    @Test
    fun externalPowerRejectsMissingAndInvalidReadings() {
        assertEquals(PreflightStatus.UNKNOWN, externalPowerObservation(null).status)
        assertEquals(PreflightStatus.UNKNOWN, externalPowerObservation(-1).status)
        assertEquals(PreflightStatus.UNKNOWN, externalPowerObservation(16).status)
    }

    @Test
    fun storageUsesConservativeSafetyFloorAndFailsClosedOnInvalidReadings() {
        assertEquals(
            PreflightStatus.PASSED,
            storageObservation(MINIMUM_AVAILABLE_STORAGE_BYTES).status,
        )
        assertEquals(
            PreflightStatus.FAILED,
            storageObservation(MINIMUM_AVAILABLE_STORAGE_BYTES - 1).status,
        )
        assertEquals(PreflightStatus.UNKNOWN, storageObservation(null).status)
        assertEquals(PreflightStatus.UNKNOWN, storageObservation(-1).status)
    }
}
