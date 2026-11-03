package com.haven.pacing.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ExcursionRecord::class, DailyAutonomicLedger::class], version = 1, exportSchema = false)
abstract class HavenDatabase : RoomDatabase() {
    abstract fun havenDao(): HavenDao

    companion object {
        @Volatile
        private var INSTANCE: HavenDatabase? = null

        fun getInstance(context: Context): HavenDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HavenDatabase::class.java,
                    "haven_autonomic_vault.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
