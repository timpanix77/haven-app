package com.haven.pacing.engine

import java.util.Calendar

enum class CockpitMode {
    COCKPIT_LIVE,
    COCKPIT_DARK
}

sealed class TelemetryEvent {
    data class Breach(val hr: Int) : TelemetryEvent()
    data class CadenceUpdate(val hr: Int, val excursionSec: Int, val debtSec: Int) : TelemetryEvent()
    data class Settled(val hr: Int, val excursionSec: Int, val dailyTotalSec: Int, val budgetSpent: Boolean) : TelemetryEvent()
    data class ModeChanged(val newMode: CockpitMode) : TelemetryEvent()
}

class AutonomicStateMachine(
    var activeCeiling: Int = 88,
    var recoveryFloor: Int = 75,
    private val onEvent: (TelemetryEvent) -> Unit
) {
    var mode: CockpitMode = CockpitMode.COCKPIT_LIVE
        private set

    var currentHr: Int = 0
        private set

    var excursionActive: Boolean = false
        private set

    var excursionSeconds: Int = 0
        private set

    var dailyTotalSeconds: Int = 0
        private set

    private var timeBelowFloorSec: Int = 0
    private var timeAboveWakeSec: Int = 0
    
    // Calibrated to Visible's 18-month baseline: 20 minutes (1,200 seconds) cumulative
    private val budgetSec: Int = 1200 
    
    private var currentPacingDay: Int = getCurrentPacingDay()

    var excursionPeakHr: Int = 0
        private set

    var currentExcursionStrain: Double = 0.0
        private set

    private val pemModel = PemPredictiveModel(activeCeiling, recoveryFloor)

    fun onTick() {
        if (currentHr <= 0) return

        // 4:00 AM Visible-style Day Rollover Check
        val todayPacingDay = getCurrentPacingDay()
        if (todayPacingDay != currentPacingDay) {
            currentPacingDay = todayPacingDay
            dailyTotalSeconds = 0 // Clean daily reset at 4 AM
        }

        // Autonomic Rest Transition: 10 continuous minutes (600s) <= recoveryFloor
        if (currentHr <= recoveryFloor) {
            timeBelowFloorSec++
            timeAboveWakeSec = 0
            if (timeBelowFloorSec >= 600 && mode != CockpitMode.COCKPIT_DARK) {
                mode = CockpitMode.COCKPIT_DARK
                onEvent(TelemetryEvent.ModeChanged(mode))
            }
        } else if (currentHr > 80) {
            // Sustained orthostatic rise > 80 held for 30s re-arms live telemetry
            timeAboveWakeSec++
            timeBelowFloorSec = 0
            if (timeAboveWakeSec >= 30 && mode != CockpitMode.COCKPIT_LIVE) {
                mode = CockpitMode.COCKPIT_LIVE
                onEvent(TelemetryEvent.ModeChanged(mode))
            }
        }

        // Excursion handling
        if (currentHr > activeCeiling) {
            if (!excursionActive) {
                excursionActive = true
                excursionSeconds = 0
                excursionPeakHr = currentHr
                currentExcursionStrain = 0.0
                if (mode == CockpitMode.COCKPIT_LIVE) {
                    onEvent(TelemetryEvent.Breach(currentHr))
                }
            }

            if (currentHr > excursionPeakHr) excursionPeakHr = currentHr
            excursionSeconds++
            dailyTotalSeconds++

            // Accumulate instantaneous quadratic strain
            currentExcursionStrain += pemModel.calculateInstantaneousStrain(currentHr, activeCeiling, recoveryFloor)

            // Cadence update every 30s while in breach
            if (excursionSeconds % 30 == 0) {
                val debt = maxOf(0, dailyTotalSeconds - budgetSec)
                if (mode == CockpitMode.COCKPIT_LIVE) {
                    onEvent(TelemetryEvent.CadenceUpdate(currentHr, excursionSeconds, debt))
                }
            }
        } else if (excursionActive && currentHr <= recoveryFloor) {
            // Settlement transition
            val budgetSpent = dailyTotalSeconds >= budgetSec
            if (mode == CockpitMode.COCKPIT_LIVE) {
                onEvent(TelemetryEvent.Settled(currentHr, excursionSeconds, dailyTotalSeconds, budgetSpent))
            }
            excursionActive = false
            excursionSeconds = 0
            currentExcursionStrain = 0.0
        }
    }

    fun updateHeartRate(newHr: Int) {
        currentHr = newHr
    }

    fun setDailyStartingTotal(seconds: Int) {
        dailyTotalSeconds = seconds
    }

    /**
     * Visible-style 4:00 AM Pacing Day partition.
     * Hours 00:00 to 03:59 belong to the previous calendar day's pacing ledger.
     */
    private fun getCurrentPacingDay(): Int {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.get(Calendar.DAY_OF_YEAR)
    }
}
