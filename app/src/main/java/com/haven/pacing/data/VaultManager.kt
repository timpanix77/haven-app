package com.haven.pacing.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class HavenVaultPayload(
    val exportDate: String,
    val excursions: List<ExcursionRecord>,
    val dailyLedgers: List<DailyAutonomicLedger>
)

class VaultManager(private val database: HavenDatabase) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun exportVault(context: Context): File = withContext(Dispatchers.IO) {
        val excursions = database.havenDao().exportAllExcursions()
        val ledgers = database.havenDao().exportAllLedgers()
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())

        val payload = HavenVaultPayload(
            exportDate = dateStr,
            excursions = excursions,
            dailyLedgers = ledgers
        )

        val json = gson.toJson(payload)
        val file = File(context.getExternalFilesDir(null), "haven_vault_$dateStr.json")
        file.writeText(json)
        file
    }

    suspend fun importVault(jsonString: String) = withContext(Dispatchers.IO) {
        val payload = gson.fromJson(jsonString, HavenVaultPayload::class.java)
        database.havenDao().importExcursions(payload.excursions)
        database.havenDao().importLedgers(payload.dailyLedgers)
    }
}
