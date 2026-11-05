package com.haven.pacing.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VaultManager(private val database: HavenDatabase) {

    suspend fun exportVault(context: Context): Boolean = withContext(Dispatchers.IO) {
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

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "haven_vault_$timeStamp.json"
            val content = rootObj.toString(2)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri: Uri? = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                    }
                    true
                } else {
                    false
                }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(downloadDir, fileName)
                outFile.writeText(content)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
