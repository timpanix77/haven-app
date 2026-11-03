package com.haven.pacing.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class DynamicPacingProfile(
    val activeCeiling: Int,
    val recoveryFloor: Int,
    val acsScore: Int,
    val riskLevel: String,
    val cumulative72hDebt: Double
)

class PemPredictiveModel(
    private val nominalCeiling: Int = 88,
    private val nominalFloor: Int = 75
) {
    /**
     * Layer 1: Quadratic Exertion Intensity
     * deltaE = ((HR - HR_ceiling) / (HR_ceiling - HR_floor))^2 * delta_t_seconds
     */
    fun calculateInstantaneousStrain(hr: Int, currentCeiling: Int, currentFloor: Int): Double {
        if (hr <= currentCeiling) return 0.0
        val numerator = (hr - currentCeiling).toDouble()
        val denominator = max(1.0, (currentCeiling - currentFloor).toDouble())
        return (numerator / denominator).pow(2.0)
    }

    /**
     * Layer 2: 72-Hour Convolution PEM Kernel
     * D_net = 1.0 * E_0 + 1.6 * E_1 + 2.2 * E_2 + 0.8 * E_3
     * where E_0 is today, E_1 is yesterday, E_2 is day before (peak crash window), E_3 is trailing.
     */
    fun calculate72HourDebt(
        todayExertion: Double,
        yesterdayExertion: Double,
        twoDaysAgoExertion: Double,
        threeDaysAgoExertion: Double
    ): Double {
        return (1.0 * todayExertion) +
               (1.6 * yesterdayExertion) +
               (2.2 * twoDaysAgoExertion) +
               (0.8 * threeDaysAgoExertion)
    }

    /**
     * Layer 3 & 4: Baseline Waking RHR Delta & Ceiling Constriction
     */
    fun computeDailyProfile(
        dNet: Double,
        wakingRhrDelta: Int = 0 // rhrWaking - baselineRhr
    ): DynamicPacingProfile {
        // Base penalty from multi-day convolution debt
        val debtPenalty = (dNet / 60.0) * 5.0 // normalized scaling factor
        // Waking tachycardia penalty: 3 pts per beat above baseline
        val rhrPenalty = max(0, wakingRhrDelta) * 3.0

        val totalPenalty = debtPenalty + rhrPenalty
        val acs = max(0, min(100, (100.0 - totalPenalty).toInt()))

        return when {
            acs >= 70 -> DynamicPacingProfile(
                activeCeiling = nominalCeiling,
                recoveryFloor = nominalFloor,
                acsScore = acs,
                riskLevel = "NOMINAL",
                cumulative72hDebt = dNet
            )
            acs >= 50 -> DynamicPacingProfile(
                activeCeiling = nominalCeiling - 6, // 82 bpm
                recoveryFloor = nominalFloor - 3,   // 72 bpm
                acsScore = acs,
                riskLevel = "GUARDED",
                cumulative72hDebt = dNet
            )
            else -> DynamicPacingProfile(
                activeCeiling = nominalCeiling - 10, // 78 bpm
                recoveryFloor = nominalFloor - 5,    // 70 bpm
                acsScore = acs,
                riskLevel = "VETO",
                cumulative72hDebt = dNet
            )
        }
    }
}
