package com.haven.pacing.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_autonomic_ledgers")
data class DailyAutonomicLedger(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val rhrWaking: Int,
    val baselineHrv: Double,
    val totalDebtUnits: Double,
    val acsScore: Int,
    val pemRiskLevel: String // "NOMINAL", "GUARDED", "VETO"
)
