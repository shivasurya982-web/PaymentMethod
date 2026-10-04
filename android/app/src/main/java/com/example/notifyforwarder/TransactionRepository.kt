package com.example.notifyforwarder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class TransactionRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val recordsList = mutableListOf<TransactionRecord>()
    private val seenIds = ConcurrentHashMap.newKeySet<String>()

    init {
        loadFromPrefs()
    }

    @Synchronized
    private fun loadFromPrefs() {
        recordsList.clear()
        seenIds.clear()
        val jsonString = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val record = TransactionRecord.fromJsonObject(jsonArray.getJSONObject(i))
                recordsList.add(record)
                seenIds.add(record.id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun saveToPrefs() {
        try {
            val jsonArray = JSONArray()
            for (record in recordsList) {
                jsonArray.put(record.toJsonObject())
            }
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isDuplicate(id: String): Boolean {
        return seenIds.contains(id)
    }

    @Synchronized
    fun addOrUpdateRecord(record: TransactionRecord) {
        val existingIndex = recordsList.indexOfFirst { it.id == record.id }
        if (existingIndex >= 0) {
            recordsList[existingIndex] = record
        } else {
            seenIds.add(record.id)
            recordsList.add(0, record) // Add latest at index 0
            // Limit stored history to latest 100 records
            while (recordsList.size > 100) {
                val removed = recordsList.removeAt(recordsList.lastIndex)
                seenIds.remove(removed.id)
            }
        }
        saveToPrefs()
    }

    @Synchronized
    fun getAllRecords(): List<TransactionRecord> {
        return ArrayList(recordsList)
    }

    @Synchronized
    fun getLatestPaymentRecord(): TransactionRecord? {
        return recordsList.firstOrNull { it.isPayment }
    }

    fun setLastServerSuccessTime(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SERVER_SUCCESS, timestamp).apply()
    }

    fun getLastServerSuccessTime(): Long {
        return prefs.getLong(KEY_LAST_SERVER_SUCCESS, 0L)
    }

    companion object {
        private const val PREFS_NAME = "upi_notify_forwarder_prefs"
        private const val KEY_HISTORY = "history_records"
        private const val KEY_LAST_SERVER_SUCCESS = "last_server_success"

        @Volatile
        private var INSTANCE: TransactionRepository? = null

        fun getInstance(context: Context): TransactionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransactionRepository(context).also { INSTANCE = it }
            }
        }
    }
}
