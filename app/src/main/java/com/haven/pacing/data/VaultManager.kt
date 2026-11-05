package com.haven.pacing.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VaultManager(private val database: HavenDatabase) {

    suspend fun exportVault(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val records = database.havenDao().getExcursionsSince(0)
            val jsonArray = JSONArray()

            for (r in records) {
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("timestamp", r.timestamp)
                    put("durationSeconds", r.durationSeconds)
                    put("peakHr", r.peakHr)
                    put("settledHr", r.settledHr)
                    put("strainPoints", r.strainPoints)
                }
                jsonArray.put(obj)
            }

            val rootObj = JSONObject().apply {
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())
                put("recordCount", records.size)
                put("excursions", jsonArray)
            }

            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(downloadDir, "haven_vault_$timeStamp.json")
            outFile.writeText(rootObj.toString(2))
            outFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun restoreVault(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val files = downloadDir.listFiles { file ->
                file.name.startsWith("haven_vault_") && file.name.endsWith(".json")
            } ?: return@withContext -1

            val latestFile = files.maxByOrNull { it.lastModified() } ?: return@withContext -1
            val content = latestFile.readText()
            val rootObj = JSONObject(content)
            val excursions = rootObj.getJSONArray("excursions")

            var count = 0
            for (i in 0 until excursions.length()) {
                val obj = excursions.getJSONObject(i)
                val record = ExcursionRecord(
                    id = if (obj.has("id")) obj.getLong("id") else 0,
                    timestamp = obj.getLong("timestamp"),
                    durationSeconds = obj.getInt("durationSeconds"),
                    peakHr = obj.getInt("peakHr"),
                    settledHr = obj.getInt("settledHr"),
                    strainPoints = obj.getDouble("strainPoints")
                )
                database.havenDao().insert(record)
                count++
            }
            count
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
}
