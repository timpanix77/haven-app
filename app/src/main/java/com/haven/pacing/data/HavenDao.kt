package com.haven.pacing.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HavenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExcursion(record: ExcursionRecord): Long

    @Query("SELECT * FROM excursion_records ORDER BY startTime DESC")
    fun getAllExcursions(): Flow<List<ExcursionRecord>>

    @Query("SELECT * FROM excursion_records WHERE startTime >= :sinceTimestamp")
    suspend fun getExcursionsSince(sinceTimestamp: Long): List<ExcursionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyLedger(ledger: DailyAutonomicLedger)

    @Query("SELECT * FROM daily_autonomic_ledgers WHERE date = :date")
    suspend fun getDailyLedger(date: String): DailyAutonomicLedger?

    @Query("SELECT * FROM daily_autonomic_ledgers ORDER BY date DESC LIMIT :days")
    suspend fun getRecentLedgers(days: Int): List<DailyAutonomicLedger>

    @Query("SELECT * FROM excursion_records")
    suspend fun exportAllExcursions(): List<ExcursionRecord>

    @Query("SELECT * FROM daily_autonomic_ledgers")
    suspend fun exportAllLedgers(): List<DailyAutonomicLedger>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importExcursions(records: List<ExcursionRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importLedgers(ledgers: List<DailyAutonomicLedger>)
}
