package com.haven.pacing.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "excursion_records")
data class ExcursionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int,
    val peakHr: Int,
    val settledHr: Int,
    val strainPoints: Double
)
