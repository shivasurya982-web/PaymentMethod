package com.example.notifyforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    @Test
    fun testParseIncomingPaymentWithFullInfo() {
        val pkg = "com.your.bank.app"
        val title = "Account Credited"
        val text = "Received ₹10.37 from ABC (UPI Ref 123456789012)"
        val postTime = 1600000000000L

        val record = NotificationParser.parseNotification(pkg, title, text, postTime)

        assertTrue(record.isPayment)
        assertEquals("RECEIVED", record.classification)
        assertEquals("₹10.37", record.amount)
        assertEquals("123456789012", record.utr)
        assertEquals("ABC", record.sender)
        assertEquals("com.your.bank.app", record.packageName)
    }

    @Test
    fun testParseIncomingPaymentMissingFields() {
        val pkg = "com.your.upi.business.app"
        val title = "Payment Alert"
        val text = "Rs 25.50 credited into your account"
        val postTime = 1600000001000L

        val record = NotificationParser.parseNotification(pkg, title, text, postTime)

        assertTrue(record.isPayment)
        assertEquals("RECEIVED", record.classification)
        assertEquals("₹25.50", record.amount)
        assertEquals("Not available in notification", record.utr)
        assertEquals("Not available in notification", record.sender)
    }

    @Test
    fun testParseDebitNotificationIgnored() {
        val pkg = "com.your.bank.app"
        val title = "Account Debited"
        val text = "Debited ₹100.00 sent to Shop Merchant Ref 987654321098"
        val postTime = 1600000002000L

        val record = NotificationParser.parseNotification(pkg, title, text, postTime)

        assertFalse(record.isPayment)
        assertEquals("DEBIT", record.classification)
        assertEquals("₹100.00", record.amount)
    }

    @Test
    fun testParseUnrelatedNotificationIgnored() {
        val pkg = "com.your.bank.app"
        val title = "Security Notice"
        val text = "Your monthly e-statement is ready for download"
        val postTime = 1600000003000L

        val record = NotificationParser.parseNotification(pkg, title, text, postTime)

        assertFalse(record.isPayment)
        assertEquals("UNRELATED", record.classification)
        assertEquals("Not available in notification", record.amount)
    }

    @Test
    fun testDeduplicationIdGeneration() {
        val id1 = NotificationParser.generateNotificationId("com.bank", "Received ₹10.37", 1000L)
        val id2 = NotificationParser.generateNotificationId("com.bank", "Received ₹10.37", 1000L)
        val id3 = NotificationParser.generateNotificationId("com.bank", "Received ₹10.37", 1001L)

        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
    }
}
