package com.example.notifyforwarder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

// ---- CONFIGURATION CONSTANTS ----
const val SERVER_URL = "https://upi-notify-app.onrender.com"   // MUST be https
const val API_KEY = "long-random-secret-for-the-android-app"

// Only listen to your bank / business UPI app.
val ALLOWED_PACKAGES = setOf("com.your.bank.app", "com.your.upi.business.app")

const val ACTION_UPDATE_UI = "com.example.notifyforwarder.ACTION_UPDATE_UI"

class NotifyService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        // Only process notifications from ALLOWED_PACKAGES
        if (packageName !in ALLOWED_PACKAGES) return

        val e = sbn.notification?.extras ?: return
        val title = e.getCharSequence("android.title")?.toString() ?: ""
        val text = listOf("android.title", "android.text", "android.bigText", "android.subText", "android.summaryText")
            .mapNotNull { e.getCharSequence(it)?.toString() }
            .joinToString(" ")
            .trim()

        if (text.isEmpty()) return

        // Log high-level notification detection without printing full sensitive contents
        Log.d("NotifyFwd", "Detected notification from pkg=$packageName")

        val repository = TransactionRepository.getInstance(applicationContext)

        // Parse notification
        val record = NotificationParser.parseNotification(
            packageName = packageName,
            title = title,
            text = text,
            postTime = sbn.postTime
        )

        // Prevent duplicate notifications from being stored or forwarded repeatedly
        if (repository.isDuplicate(record.id)) {
            Log.d("NotifyFwd", "Duplicate notification ignored: id=${record.id}")
            return
        }

        // Only treat a notification as a payment notification when existing payment parsing logic determines
        // that it represents an incoming/received payment. Do not forward unrelated notifications.
        if (!record.isPayment) {
            Log.i("NotifyFwd", "Non-incoming payment notification ignored (pkg=$packageName)")
            record.forwardingStatus = "IGNORED"
            record.serverStatus = "Ignored: Not credit payment"
            repository.addOrUpdateRecord(record)
            sendBroadcast(Intent(ACTION_UPDATE_UI))
            return
        }

        // Enforce HTTPS-only transmission
        if (!SERVER_URL.startsWith("https://", ignoreCase = true)) {
            Log.e("NotifyFwd", "SERVER_URL must use https! Notification forwarding aborted.")
            record.forwardingStatus = "NO"
            record.serverStatus = "Failed: HTTPS required"
            repository.addOrUpdateRecord(record)
            sendBroadcast(Intent(ACTION_UPDATE_UI))
            return
        }

        // Save initially as pending payment transaction
        repository.addOrUpdateRecord(record)
        sendBroadcast(Intent(ACTION_UPDATE_UI))

        val body = JSONObject().put("text", text).put("ts", sbn.postTime).toString()

        thread {
            var success = false
            var lastResponseStatus = "Failed"

            repeat(4) { attempt ->
                try {
                    val url = URL(SERVER_URL)
                    val conn = url.openConnection() as HttpsURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("x-api-key", API_KEY)
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    conn.outputStream.use { os ->
                        os.write(body.toByteArray(Charsets.UTF_8))
                    }

                    val code = conn.responseCode
                    conn.disconnect()

                    if (code in 200..299) {
                        Log.i("NotifyFwd", "Successfully forwarded notification (HTTP $code)")
                        success = true
                        lastResponseStatus = "HTTP $code"
                        return@repeat
                    } else {
                        Log.w("NotifyFwd", "Server returned HTTP $code on attempt ${attempt + 1}")
                        lastResponseStatus = "HTTP $code"
                    }
                } catch (ex: Exception) {
                    Log.w("NotifyFwd", "Send failed on attempt ${attempt + 1}/4: ${ex.message}")
                    lastResponseStatus = "Network Error"
                }

                try {
                    Thread.sleep(2000L * (attempt + 1))
                } catch (ignored: InterruptedException) {}
            }

            if (success) {
                record.forwardingStatus = "YES"
                record.serverStatus = lastResponseStatus
                repository.setLastServerSuccessTime(System.currentTimeMillis())
            } else {
                record.forwardingStatus = "NO"
                record.serverStatus = "Failed ($lastResponseStatus)"
            }

            repository.addOrUpdateRecord(record)
            sendBroadcast(Intent(ACTION_UPDATE_UI))
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var repository: TransactionRepository
    private lateinit var adapter: TransactionAdapter
    private val dateFormatter = SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = TransactionRepository.getInstance(applicationContext)

        val rvRecent = findViewById<RecyclerView>(R.id.rvRecentNotifications)
        rvRecent.layoutManager = LinearLayoutManager(this)
        adapter = TransactionAdapter(emptyList())
        rvRecent.adapter = adapter

        findViewById<Button>(R.id.btnGrantAccess).setOnClickListener {
            openNotificationSettings()
        }

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            openNotificationSettings()
        }

        findViewById<Button>(R.id.btnSimulateTest).setOnClickListener {
            simulateTestNotification()
        }

        updateDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiUpdateReceiver, IntentFilter(ACTION_UPDATE_UI))
        }
        updateDashboard()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(uiUpdateReceiver)
        } catch (e: Exception) {}
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun updateDashboard() {
        // 1. Connection & Configuration Status
        val accessEnabled = isNotificationServiceEnabled()
        val cardAccessWarning = findViewById<CardView>(R.id.cardAccessWarning)
        val tvServiceStatusBadge = findViewById<TextView>(R.id.tvServiceStatusBadge)
        val tvConfigAccessStatus = findViewById<TextView>(R.id.tvConfigAccessStatus)

        if (accessEnabled) {
            cardAccessWarning.visibility = View.GONE
            tvServiceStatusBadge.text = "Active"
            tvServiceStatusBadge.setBackgroundColor(getColor(R.color.success_bg))
            tvServiceStatusBadge.setTextColor(getColor(R.color.success_green))
            tvConfigAccessStatus.text = "Enabled"
            tvConfigAccessStatus.setTextColor(getColor(R.color.success_green))
        } else {
            cardAccessWarning.visibility = View.VISIBLE
            tvServiceStatusBadge.text = "Access Required"
            tvServiceStatusBadge.setBackgroundColor(getColor(R.color.warning_bg))
            tvServiceStatusBadge.setTextColor(getColor(R.color.warning_red))
            tvConfigAccessStatus.text = "Disabled"
            tvConfigAccessStatus.setTextColor(getColor(R.color.warning_red))
        }

        findViewById<TextView>(R.id.tvConfigServerUrl).text = if (SERVER_URL.isNotBlank()) "Yes ($SERVER_URL)" else "No"
        
        val maskedApiKey = if (API_KEY.length > 4) "••••••••" + API_KEY.takeLast(4) else "Yes (Masked)"
        findViewById<TextView>(R.id.tvConfigApiKey).text = if (API_KEY.isNotBlank()) "Yes ($maskedApiKey)" else "No"

        findViewById<TextView>(R.id.tvConfigAllowedPackages).text = ALLOWED_PACKAGES.joinToString(", ")

        val lastSuccessTime = repository.getLastServerSuccessTime()
        findViewById<TextView>(R.id.tvConfigLastSuccess).text = if (lastSuccessTime > 0) {
            dateFormatter.format(Date(lastSuccessTime))
        } else {
            "None yet"
        }

        // 2. Latest Transaction Section
        val latestRecord = repository.getLatestPaymentRecord()
        val layoutLatestDetails = findViewById<View>(R.id.layoutLatestDetails)
        val tvLatestAmount = findViewById<TextView>(R.id.tvLatestAmount)

        if (latestRecord != null) {
            layoutLatestDetails.visibility = View.VISIBLE
            tvLatestAmount.text = "${latestRecord.amount} received"
            findViewById<TextView>(R.id.tvLatestType).text = latestRecord.classification
            findViewById<TextView>(R.id.tvLatestSender).text = latestRecord.sender
            findViewById<TextView>(R.id.tvLatestUtr).text = latestRecord.utr
            findViewById<TextView>(R.id.tvLatestSource).text = latestRecord.packageName
            findViewById<TextView>(R.id.tvLatestTime).text = timeFormatter.format(Date(latestRecord.timestamp))
            findViewById<TextView>(R.id.tvLatestForwarded).text = "${latestRecord.forwardingStatus} (${latestRecord.serverStatus})"
        } else {
            tvLatestAmount.text = "No transactions detected yet"
            layoutLatestDetails.visibility = View.GONE
        }

        // 3. Recent Notifications List
        val allRecords = repository.getAllRecords()
        val tvEmptyHistory = findViewById<TextView>(R.id.tvEmptyHistory)
        val rvRecent = findViewById<RecyclerView>(R.id.rvRecentNotifications)

        if (allRecords.isEmpty()) {
            tvEmptyHistory.visibility = View.VISIBLE
            rvRecent.visibility = View.GONE
        } else {
            tvEmptyHistory.visibility = View.GONE
            rvRecent.visibility = View.VISIBLE
            adapter.updateData(allRecords)
        }
    }

    private fun simulateTestNotification() {
        val testPkg = ALLOWED_PACKAGES.firstOrNull() ?: "com.your.bank.app"
        val testTs = System.currentTimeMillis()
        val testText = "Received ₹10.37 from ABC (UPI Ref 123456789012)"

        val record = NotificationParser.parseNotification(
            packageName = testPkg,
            title = "Bank Alert",
            text = testText,
            postTime = testTs
        )

        // Simulate forwarding flow in background thread
        record.forwardingStatus = "YES"
        record.serverStatus = "HTTP 200 (Simulated)"
        repository.addOrUpdateRecord(record)
        repository.setLastServerSuccessTime(testTs)
        updateDashboard()
    }
}
