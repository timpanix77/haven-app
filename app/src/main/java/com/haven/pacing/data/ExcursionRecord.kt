package com.haven.pacing.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "excursion_records")
data class ExcursionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Int,
    val peakHr: Int,
    val settledHr: Int,
    val exertionPoints: Double,
    val synced: Boolean = false
)
