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
    
    // Calibrated baseline: 20 minutes (1,200 seconds) cumulative
    private val budgetSec: Int = 1200 
    
    // 2-Minute Grace Period
    private val gracePeriodSec: Int = 120
    private var alertArmed: Boolean = false

    private var currentPacingDay: Int = getCurrentPacingDay()

    var excursionPeakHr: Int = 0
        private set

    var currentExcursionStrain: Double = 0.0
        private set

    private val pemModel = PemPredictiveModel(activeCeiling, recoveryFloor)

    fun onTick() {
        if (currentHr <= 0) return

        // 4:00 AM Visible-style Day Rollover
        val todayPacingDay = getCurrentPacingDay()
        if (todayPacingDay != currentPacingDay) {
            currentPacingDay = todayPacingDay
            dailyTotalSeconds = 0
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
                alertArmed = false
            }

            if (currentHr > excursionPeakHr) excursionPeakHr = currentHr
            excursionSeconds++
            dailyTotalSeconds++

            // Accumulate instantaneous quadratic strain
            currentExcursionStrain += pemModel.calculateInstantaneousStrain(currentHr, activeCeiling, recoveryFloor)

            // Trigger voice alert ONLY after grace period (120s) has elapsed
            if (excursionSeconds >= gracePeriodSec) {
                if (!alertArmed) {
                    alertArmed = true
                    if (mode == CockpitMode.COCKPIT_LIVE) {
                        onEvent(TelemetryEvent.Breach(currentHr))
                    }
                // Calm 60-second pacing cadence
                } else if ((excursionSeconds - gracePeriodSec) % 60 == 0) {
                    val debt = maxOf(0, dailyTotalSeconds - budgetSec)
                    if (mode == CockpitMode.COCKPIT_LIVE) {
                        onEvent(TelemetryEvent.CadenceUpdate(currentHr, excursionSeconds, debt))
                    }
                }
            }
        } else if (excursionActive && currentHr <= recoveryFloor) {
            // Settlement transition
            val budgetSpent = dailyTotalSeconds >= budgetSec
            if (alertArmed && mode == CockpitMode.COCKPIT_LIVE) {
                onEvent(TelemetryEvent.Settled(currentHr, excursionSeconds, dailyTotalSeconds, budgetSpent))
            }
            excursionActive = false
            excursionSeconds = 0
            currentExcursionStrain = 0.0
            alertArmed = false
        }
    }

    fun updateHeartRate(newHr: Int) {
        currentHr = newHr
    }

    fun setDailyStartingTotal(seconds: Int) {
        dailyTotalSeconds = seconds
    }

    private fun getCurrentPacingDay(): Int {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.get(Calendar.DAY_OF_YEAR)
    }
}
