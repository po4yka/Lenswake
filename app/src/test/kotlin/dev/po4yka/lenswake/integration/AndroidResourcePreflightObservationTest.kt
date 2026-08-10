package dev.po4yka.lenswake.integration

import android.os.BatteryManager
import dev.po4yka.lenswake.core.PreflightStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AndroidResourcePreflightObservationTest {
    @Test
    fun batteryUsesDocumentedThirtyPercentMinimumAndRejectsInvalidReadings() {
        assertEquals(PreflightStatus.FAILED, batteryObservation(29).status)
        assertEquals(PreflightStatus.PASSED, batteryObservation(30).status)
        assertEquals(PreflightStatus.PASSED, batteryObservation(100).status)
        assertEquals(PreflightStatus.UNKNOWN, batteryObservation(-1).status)
        assertEquals(PreflightStatus.UNKNOWN, batteryObservation(null).status)
    }

    @Test
    fun chargingDistinguishesConnectedDisconnectedAndUnknownStates() {
        assertEquals(
            PreflightStatus.PASSED,
            chargingObservation(BatteryManager.BATTERY_STATUS_CHARGING).status,
        )
        assertEquals(
            PreflightStatus.PASSED,
            chargingObservation(BatteryManager.BATTERY_STATUS_FULL).status,
        )
        assertEquals(
            PreflightStatus.FAILED,
            chargingObservation(BatteryManager.BATTERY_STATUS_DISCHARGING).status,
        )
        assertEquals(
            PreflightStatus.FAILED,
            chargingObservation(BatteryManager.BATTERY_STATUS_NOT_CHARGING).status,
        )
        assertEquals(
            PreflightStatus.UNKNOWN,
            chargingObservation(BatteryManager.BATTERY_STATUS_UNKNOWN).status,
        )
        assertEquals(PreflightStatus.UNKNOWN, chargingObservation(null).status)
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
