package com.haven.pacing.engine

import java.util.Calendar

enum class CockpitMode {
    COCKPIT_LIVE,
    COCKPIT_DARK
}

sealed class TelemetryEvent {
    data class Breach(val hr: Int) : TelemetryEvent()
    data class CadenceUpdate(val hr: Int, val excursionSec: Int, val debtSec: Int) : TelemetryEvent()
    data class Settled(
        val hr: Int, 
        val excursionSec: Int, 
        val peakHr: Int, 
        val strain: Double, 
        val dailyTotalSec: Int, 
        val budgetSpent: Boolean, 
        val voiceAlerted: Boolean
    ) : TelemetryEvent()
    data class ModeChanged(val newMode: CockpitMode) : TelemetryEvent()
    data class DayRollover(val newDay: Int) : TelemetryEvent()
}

class AutonomicStateMachine(
    var activeCeiling: Int = 88,
    var recoveryFloor: Int = 75,
    var budgetSec: Int = 1200, // Default 20 mins; dynamically tuned at 4 AM
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
    
    // 2-Minute Silent Buffer
    private val gracePeriodSec: Int = 120
    private var alertArmed: Boolean = false

    private var currentPacingDay: Int = getCurrentPacingDay()

    var excursionPeakHr: Int = 0
        private set

    var currentExcursionStrain: Double = 0.0
        private set

    var stabilityScore: Int = 75 // 0-100 baseline
        private set

    private val pemModel = PemPredictiveModel(activeCeiling, recoveryFloor)

    fun onTick() {
        if (currentHr <= 0) return

        // 4:00 AM Pacing Day Rollover Check
        val todayPacingDay = getCurrentPacingDay()
        if (todayPacingDay != currentPacingDay) {
            currentPacingDay = todayPacingDay
            dailyTotalSeconds = 0
            onEvent(TelemetryEvent.DayRollover(todayPacingDay))
        }

        // Autonomic Rest Transition: 10 minutes (600s) continuous <= recoveryFloor
        if (currentHr <= recoveryFloor) {
            timeBelowFloorSec++
            timeAboveWakeSec = 0
            if (timeBelowFloorSec >= 600 && mode != CockpitMode.COCKPIT_DARK) {
                mode = CockpitMode.COCKPIT_DARK
                onEvent(TelemetryEvent.ModeChanged(mode))
            }
        } else if (currentHr > 80) {
            // Sustained rise > 80 for 30s re-arms Cockpit Live
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

            // Accumulate quadratic strain
            currentExcursionStrain += pemModel.calculateInstantaneousStrain(currentHr, activeCeiling, recoveryFloor)

            // Audio trigger ONLY after 2-minute grace has elapsed
            if (excursionSeconds >= gracePeriodSec) {
                if (!alertArmed) {
                    alertArmed = true
                    if (mode == CockpitMode.COCKPIT_LIVE) {
                        onEvent(TelemetryEvent.Breach(currentHr))
                    }
                } else if ((excursionSeconds - gracePeriodSec) % 60 == 0) {
                    val debt = maxOf(0, dailyTotalSeconds - budgetSec)
                    if (mode == CockpitMode.COCKPIT_LIVE) {
                        onEvent(TelemetryEvent.CadenceUpdate(currentHr, excursionSeconds, debt))
                    }
                }
            }
        } else if (excursionActive && currentHr <= recoveryFloor) {
            // Settlement transition: ALWAYS fire event so Room DB records the spike
            val budgetSpent = dailyTotalSeconds >= budgetSec
            onEvent(
                TelemetryEvent.Settled(
                    hr = currentHr,
                    excursionSec = excursionSeconds,
                    peakHr = excursionPeakHr,
                    strain = currentExcursionStrain,
                    dailyTotalSec = dailyTotalSeconds,
                    budgetSpent = budgetSpent,
                    voiceAlerted = alertArmed
                )
            )
            excursionActive = false
            excursionSeconds = 0
            currentExcursionStrain = 0.0
            alertArmed = false
        }
    }

    fun applyDynamicCalibration(score: Int, newCeiling: Int, newBudgetMinutes: Int) {
        stabilityScore = score.coerceIn(0, 100)
        activeCeiling = newCeiling
        budgetSec = newBudgetMinutes * 60
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
