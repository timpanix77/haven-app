package com.haven.pacing.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HavenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ExcursionRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExcursion(record: ExcursionRecord): Long

    @Query("SELECT * FROM excursion_records WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getExcursionsSince(sinceTimestamp: Long): List<ExcursionRecord>

    @Query("SELECT * FROM excursion_records ORDER BY timestamp DESC")
    suspend fun getAllExcursions(): List<ExcursionRecord>

    @Query("DELETE FROM excursion_records")
    suspend fun clearAll()
}
