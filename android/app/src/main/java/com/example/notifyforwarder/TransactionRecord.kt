package com.example.notifyforwarder

import org.json.JSONObject

data class TransactionRecord(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val amount: String,
    val utr: String,
    val sender: String,
    val timestamp: Long,
    val classification: String, // RECEIVED, DEBIT, UNRELATED
    val isPayment: Boolean,
    var forwardingStatus: String, // YES, NO, IGNORED
    var serverStatus: String // e.g. "HTTP 200", "Failed (HTTP 500)", "Ignored: Not credit"
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("packageName", packageName)
            put("title", title)
            put("text", text)
            put("amount", amount)
            put("utr", utr)
            put("sender", sender)
            put("timestamp", timestamp)
            put("classification", classification)
            put("isPayment", isPayment)
            put("forwardingStatus", forwardingStatus)
            put("serverStatus", serverStatus)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): TransactionRecord {
            return TransactionRecord(
                id = json.optString("id", ""),
                packageName = json.optString("packageName", "Unknown App"),
                title = json.optString("title", ""),
                text = json.optString("text", ""),
                amount = json.optString("amount", "Not available in notification"),
                utr = json.optString("utr", "Not available in notification"),
                sender = json.optString("sender", "Not available in notification"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                classification = json.optString("classification", "UNRELATED"),
                isPayment = json.optBoolean("isPayment", false),
                forwardingStatus = json.optString("forwardingStatus", "NO"),
                serverStatus = json.optString("serverStatus", "Unknown")
            )
        }
    }
}
