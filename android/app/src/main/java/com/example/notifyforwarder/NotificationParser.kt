package com.example.notifyforwarder

import java.security.MessageDigest

object NotificationParser {

    private val amountRegex = Regex("""(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    
    private val utrRegex = Regex("""(?:upi\s*ref(?:erence)?(?:\s*no\.?)?|utr(?:\s*no\.?)?|ref(?:\s*no\.?)?|txn\s*id|transaction\s*id)[\s:-]*([a-z0-9]{8,22})""", RegexOption.IGNORE_CASE)
    private val standaloneUtrRegex = Regex("""\b(\d{12})\b""")

    private val senderFromRegex = Regex("""from\s+([A-Za-z0-9\s.]{2,30}?)(?=\s*(?:\(|via|by|upi|ref|utr|a\/c|on|\.|$))""", RegexOption.IGNORE_CASE)
    private val senderByRegex = Regex("""by\s+([A-Za-z0-9\s.]{2,30}?)(?=\s*(?:\(|via|upi|ref|utr|a\/c|on|\.|$))""", RegexOption.IGNORE_CASE)

    fun generateNotificationId(packageName: String, text: String, postTime: Long): String {
        val input = "$packageName:$text:$postTime"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun parseNotification(
        packageName: String,
        title: String,
        text: String,
        postTime: Long
    ): TransactionRecord {
        val textLower = text.lowercase()
        val isCredit = textLower.contains("received") || textLower.contains("credited")
        val isDebit = textLower.contains("debit") || textLower.contains("debited") ||
                textLower.contains("sent to") || textLower.contains("paid to") || textLower.contains("spent")

        val classification = when {
            isDebit -> "DEBIT"
            isCredit -> "RECEIVED"
            else -> "UNRELATED"
        }

        // Amount parsing
        val amountMatch = amountRegex.find(text)
        val amount = if (amountMatch != null) {
            val numStr = amountMatch.groupValues[1]
            "₹$numStr"
        } else {
            "Not available in notification"
        }

        // Is Payment condition: Must be credit, not debit, and contain amount
        val isPayment = (classification == "RECEIVED") && (amount != "Not available in notification")

        // UTR / Reference parsing
        var utr = "Not available in notification"
        val utrMatch = utrRegex.find(text)
        if (utrMatch != null && utrMatch.groupValues[1].isNotBlank()) {
            utr = utrMatch.groupValues[1].trim()
        } else {
            val standaloneMatch = standaloneUtrRegex.find(text)
            if (standaloneMatch != null && standaloneMatch.groupValues[1].isNotBlank()) {
                utr = standaloneMatch.groupValues[1].trim()
            }
        }

        // Sender parsing
        var sender = "Not available in notification"
        val fromMatch = senderFromRegex.find(text)
        if (fromMatch != null && fromMatch.groupValues[1].isNotBlank()) {
            sender = fromMatch.groupValues[1].trim()
        } else {
            val byMatch = senderByRegex.find(text)
            if (byMatch != null && byMatch.groupValues[1].isNotBlank()) {
                val candidate = byMatch.groupValues[1].trim()
                if (!candidate.contains("bank", ignoreCase = true)) {
                    sender = candidate
                }
            }
        }

        val id = generateNotificationId(packageName, text, postTime)

        return TransactionRecord(
            id = id,
            packageName = packageName,
            title = title,
            text = text,
            amount = amount,
            utr = utr,
            sender = sender,
            timestamp = postTime,
            classification = classification,
            isPayment = isPayment,
            forwardingStatus = if (isPayment) "NO" else "IGNORED",
            serverStatus = if (isPayment) "Pending" else "Ignored: Not payment"
        )
    }
}
