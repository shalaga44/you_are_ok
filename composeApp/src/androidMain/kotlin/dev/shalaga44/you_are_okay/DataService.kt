
package dev.shalaga44.you_are_okay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class DataService : Service() {


    private lateinit var polar: PolarController


    private var recording = false
    private var startAtMs = 0L
    private var lastFlushAt = 0L
    private var sessionUuid: String = ""
    private var userId: String = ""
    private var deviceName: String = "Unknown"
    private var deviceId: String = "Unknown"

    private val sampleBuffer = mutableListOf<JSONObject>()
    private val timeFmt = SimpleDateFormat("MMM dd,yyyy HH:mm:ss:SSS", Locale.US)

    private var lastHr: Int? = null
    private var lastPpg: Int? = null
    private var lastBattery: Int? = null
    private var lastAccX: Float? = null
    private var lastAccY: Float? = null
    private var lastAccZ: Float? = null

    companion object {

        const val BASE_URL = "http://192.168.44.103:8080/api/v1"
        private const val STRESS_ENDPOINT = "$BASE_URL/stress?mode=hrv"
        const val UUID_ENDPOINT = "$BASE_URL/uuid"


        private const val FLUSH_EVERY_MS = 12_000L
        private const val MAX_BATCH = 300


        private const val NOTIF_ID = 44
        private const val NOTIF_CHANNEL_ID = "you_are_okay_channel"
        private const val NOTIF_CHANNEL_NAME = "You Are Okay (Streaming)"


        const val ACTION_SENDING_STATE = "sendingState"
        const val ACTION_TURNOFF = "turnoffService"
        const val ACTION_REQUEST_UPDATE = "requestUpdate"
        const val ACTION_UPDATING_STATE = "updatingState"


        const val EXTRA_SENT = "sent"
        const val EXTRA_UUID = "gotUuid"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_DEVICE_ID = "deviceID"
        const val EXTRA_DEVICE_NAME = "deviceName"
    }

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SENDING_STATE -> {
                    val wantSending = intent.getBooleanExtra(EXTRA_SENT, false)
                    val uuid = intent.getStringExtra(EXTRA_UUID) ?: ""
                    val uid = intent.getStringExtra(EXTRA_USER_ID) ?: userId
                    if (wantSending && uuid.isNotBlank()) {
                        startSending(uuid, uid)
                    } else {
                        stopSending()
                    }
                    pushUiUpdate("State toggled")
                }
                ACTION_TURNOFF -> {
                    stopSending()
                    stopSelf()
                }
                ACTION_REQUEST_UPDATE -> pushUiUpdate("Snapshot")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Idle"))


        val sp = getSharedPreferences("you_are_okay_prefs", MODE_PRIVATE)
        deviceId = sp.getString(EXTRA_DEVICE_ID, deviceId) ?: deviceId
        userId = sp.getString(EXTRA_USER_ID, userId) ?: userId
        deviceName = sp.getString(EXTRA_DEVICE_NAME, deviceName) ?: deviceName


        polar = PolarController(applicationContext, object : PolarController.Callbacks {
            override fun onConnected(name: String, id: String) {
                deviceName = name
                deviceId = id
                pushUiUpdate("Connected to $name")
            }
            override fun onDisconnected() { pushUiUpdate("Disconnected") }
            override fun onBattery(percent: Int) {
                lastBattery = percent
                pushUiUpdate("Battery $percent%")
            }
            override fun onHr(bpm: Int) { onHrUpdate(bpm) }
            override fun onPpg(ppg0: Int?, ppg1: Int?, ppg2: Int?, accX: Float?, accY: Float?, accZ: Float?) {
                onPpgSample(ppg0, ppg1, ppg2, accX, accY, accZ)
            }
            override fun onMessage(msg: String) { pushUiUpdate(msg) }
        })


        val filter = IntentFilter().apply {
            addAction(ACTION_SENDING_STATE)
            addAction(ACTION_TURNOFF)
            addAction(ACTION_REQUEST_UPDATE)
        }
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        Log.d("DataService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_DEVICE_ID)?.let { deviceId = it }
        intent?.getStringExtra(EXTRA_USER_ID)?.let { userId = it }
        intent?.getStringExtra(EXTRA_DEVICE_NAME)?.let { deviceName = it }

        if (deviceId.isNotBlank()) {
            runCatching { polar.connect(deviceId) }
        }
        pushUiUpdate("Service running")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(controlReceiver) }
        stopSending()
        runCatching { polar.shutdown() }
        RequestSender.shutdown()
        Log.d("DataService", "Service destroyed")
    }


    private fun onHrUpdate(hr: Int) {
        lastHr = hr
        if (recording) maybeFlush()
    }

    private fun onPpgSample(ppg0: Int?, ppg1: Int?, ppg2: Int?, accX: Float?, accY: Float?, accZ: Float?) {
        lastAccX = accX ?: lastAccX
        lastAccY = accY ?: lastAccY
        lastAccZ = accZ ?: lastAccZ
        lastPpg  = ppg1 ?: lastPpg
        if (!recording) return

        val now = System.currentTimeMillis()
        val elapsed = max(0L, now - startAtMs)

        val row = JSONObject().apply {
            put("Device", deviceName)
            put("TimeDate", timeFmt.format(Date(now)))
            put("Time", formatElapsed(elapsed))
            put("PPG", lastPpg ?: JSONObject.NULL)
            put("HR", lastHr ?: JSONObject.NULL)
            put("uuid", sessionUuid)
            put("User_ID", userId) 
            put("AccX", lastAccX ?: JSONObject.NULL)
            put("AccY", lastAccY ?: JSONObject.NULL)
            put("AccZ", lastAccZ ?: JSONObject.NULL)
            put("ppg0", ppg0 ?: JSONObject.NULL)
            put("ppg2", ppg2 ?: JSONObject.NULL)
        }
        Log.d("DataService", "Sending sample uuid=$sessionUuid userId=$userId hr=$lastHr ppg=$lastPpg")
        sampleBuffer += row
        maybeFlush()
    }


    private fun startSending(uuid: String, user: String) {
        if (recording && uuid == sessionUuid) return
        sessionUuid = uuid
        userId = user

        getSharedPreferences("you_are_okay_prefs", MODE_PRIVATE)
            .edit().putString(EXTRA_USER_ID, userId).apply()

        startAtMs = System.currentTimeMillis()
        lastFlushAt = startAtMs
        sampleBuffer.clear()
        recording = true
        runCatching { polar.startStreaming() }


        RequestSender.postObject(UUID_ENDPOINT, JSONObject().put("uuid", sessionUuid), "UUID")
        updateNotification("Recording…")
        Log.d("DataService", "startSending $sessionUuid for $userId")
    }

    private fun stopSending() {
        if (!recording) return
        flushSamples(force = true)
        recording = false
        runCatching { polar.stopStreaming() }
        updateNotification("Idle")
        Log.d("DataService", "stopSending $sessionUuid")
        sessionUuid = ""
    }


    private fun maybeFlush() {
        val now = System.currentTimeMillis()
        if (sampleBuffer.size >= MAX_BATCH || (now - lastFlushAt) >= FLUSH_EVERY_MS) {
            flushSamples()
            lastFlushAt = now
        }
    }

    private fun flushSamples(force: Boolean = false) {
        if (sampleBuffer.isEmpty()) return
        val payload = JSONArray().apply { sampleBuffer.forEach { put(it) } }
        sampleBuffer.clear()
        RequestSender.postArray(STRESS_ENDPOINT, payload, "FLUSH")
        Log.d("DataService", "Flushed ${payload.length()} samples")
    }


    private fun pushUiUpdate(msg: String) {
        val i = Intent(ACTION_UPDATING_STATE).apply {
            putExtra("message", msg)
            putExtra("name", deviceName)
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra("hr", lastHr ?: -1)
            putExtra("battery", lastBattery?.toString())
            putExtra("ppg", lastPpg?.toString())
            putExtra("buttonState", recording.toString())
            putExtra("recording", recording)
        }
        sendBroadcast(i)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, NOTIF_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("nav_route", "collector")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("You Are Okay")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun formatElapsed(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1_000) % 60
        val msR = ms % 1_000
        return "$h:$m:$s:$msR"
    }
}