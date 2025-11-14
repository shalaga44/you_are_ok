package dev.shalaga44.you_are_okay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private var lastIbiMsList: List<Int> = emptyList()
    private var lastBattery: Int? = null
    private var lastAccX: Float? = null
    private var lastAccY: Float? = null
    private var lastAccZ: Float? = null

    // Shared HRV/stress engine (from commonMain)
    private val stressEngine = StressEngine()

    companion object {

        const val BASE_URL = "http://192.168.44.100:8080/api/v1"
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

            override fun onDisconnected() {
                pushUiUpdate("Disconnected")
            }

            override fun onBattery(percent: Int) {
                lastBattery = percent
                pushUiUpdate("Battery $percent%")
            }

            override fun onHr(bpm: Int) {
                onHrUpdate(bpm)
            }

            // PPG is now only used as context (waveform / last value),
            // samples are NOT pushed from here anymore.
            override fun onPpg(
                ppg0: Int?,
                ppg1: Int?,
                ppg2: Int?,
                accX: Float?,
                accY: Float?,
                accZ: Float?
            ) {
                onPpgSample(ppg0, ppg1, ppg2, accX, accY, accZ)
            }

            // IBI-driven samples: each PPI batch becomes one JSON row
            override fun onIbi(ibiMsList: List<Int>) {
                onIbiSample(ibiMsList)
            }

            override fun onMessage(msg: String) {
                pushUiUpdate(msg)
            }
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

    // ---------------- HR / PPG / IBI HANDLERS ----------------

    private fun onHrUpdate(hr: Int) {
        lastHr = hr
        if (recording) maybeFlush()
    }

    /**
     * PPG handler is now "state only":
     * - update lastPpg, lastAccX/Y/Z
     * - DO NOT push samples; main samples are IBI-driven
     */
    private fun onPpgSample(
        ppg0: Int?,
        ppg1: Int?,
        ppg2: Int?,
        accX: Float?,
        accY: Float?,
        accZ: Float?
    ) {
        lastAccX = accX ?: lastAccX
        lastAccY = accY ?: lastAccY
        lastAccZ = accZ ?: lastAccZ
        lastPpg = ppg1 ?: lastPpg
        // No JSON row here. IBI is the driver.
    }

    /**
     * IBI-driven sample creation:
     * - one JSON row per PPI frame
     * - attaches latest HR / PPG / ACC
     * - includes ibi_ms_list array
     */
    private fun onIbiSample(ibiMsList: List<Int>) {
        if (!recording) return
        if (ibiMsList.isEmpty()) return

        lastIbiMsList = ibiMsList

        val now = System.currentTimeMillis()
        val elapsed = max(0L, now - startAtMs)

        val ibiJson = JSONArray().apply {
            ibiMsList.forEach { put(it) }
        }

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
            put("ppg0", JSONObject.NULL)   // optional, you can store last ppg0/ppg2 if you want
            put("ppg2", JSONObject.NULL)
            put("ibi_ms_list", ibiJson)
            put("sample_type", "ibi")
        }

        Log.d(
            "DataService",
            "Sending IBI sample uuid=$sessionUuid userId=$userId hr=$lastHr ibi_ms_list=${ibiMsList.joinToString(",")}"
        )

        sampleBuffer += row
        maybeFlush()
    }

    // ---------------- RECORDING CONTROL ----------------

    private fun startSending(uuid: String, user: String) {
        if (recording && uuid == sessionUuid) return
        sessionUuid = uuid
        userId = user

        getSharedPreferences("you_are_okay_prefs", MODE_PRIVATE)
            .edit().putString(EXTRA_USER_ID, userId).apply()

        startAtMs = System.currentTimeMillis()
        lastFlushAt = startAtMs
        sampleBuffer.clear()
        lastIbiMsList = emptyList()
        recording = true
        runCatching { polar.startStreaming() }

        // Register UUID on server (optional, requires network)
        RequestSender.postObject(UUID_ENDPOINT, JSONObject().put("uuid", sessionUuid), "UUID")
        updateNotification("Recording…")
        Log.d("DataService", "startSending $sessionUuid for $userId")

        // Reset engine for new session
        stressEngine.reset()
    }

    private fun stopSending() {
        if (!recording) return
        flushSamples(force = true)
        recording = false
        runCatching { polar.stopStreaming() }
        updateNotification("Idle")
        Log.d("DataService", "stopSending $sessionUuid")
        sessionUuid = ""
        lastIbiMsList = emptyList()
    }

    // ---------------- BUFFER / FLUSH ----------------

    private fun maybeFlush() {
        val now = System.currentTimeMillis()
        if (sampleBuffer.size >= MAX_BATCH || (now - lastFlushAt) >= FLUSH_EVERY_MS) {
            flushSamples()
            lastFlushAt = now
        }
    }

    private fun flushSamples(force: Boolean = false) {
        if (sampleBuffer.isEmpty()) return

        // Copy and clear buffer
        val chunk = sampleBuffer.toList()
        sampleBuffer.clear()

        val rows = chunk.mapNotNull { obj ->
            try {
                SampleRowCommon(
                    device = obj.optString("Device"),
                    timeDate = obj.optString("TimeDate", null),
                    time = obj.optString("Time", null),
                    ppg = if (obj.isNull("PPG")) null else obj.optDouble("PPG"),
                    hr = if (obj.isNull("HR")) null else obj.optDouble("HR"),
                    uuid = obj.optString("uuid"),
                    userId = obj.optString("User_ID", null),
                    accX = if (obj.isNull("AccX")) null else obj.optDouble("AccX"),
                    accY = if (obj.isNull("AccY")) null else obj.optDouble("AccY"),
                    accZ = if (obj.isNull("AccZ")) null else obj.optDouble("AccZ"),
                    ppg0 = if (obj.isNull("ppg0")) null else obj.optDouble("ppg0"),
                    ppg2 = if (obj.isNull("ppg2")) null else obj.optDouble("ppg2")
                )
            } catch (e: Exception) {
                Log.w("DataService", "Failed to map JSON to SampleRowCommon: ${e.message}")
                null
            }
        }

        // Local HRV / stress evaluation (still PPG-based unless you extend it for IBI)
        val stressResult = stressEngine.processChunk(rows, samplingHz = 130)

        lastHr = stressResult.hrMean?.toInt() ?: lastHr
        lastPpg = stressResult.ppgMean?.toInt() ?: lastPpg
        pushUiStress(stressResult)

        if (isNetworkAvailable()) {
            val payload = JSONArray().apply { chunk.forEach { put(it) } }
            RequestSender.postArray(STRESS_ENDPOINT, payload, "FLUSH")
            Log.d("DataService", "Flushed ${payload.length()} IBI-driven samples (online)")
        } else {
            Log.d("DataService", "Flushed ${rows.size} IBI-driven samples (offline, not sent to server)")
        }
    }

    // ---------------- UI / NOTIFICATION ----------------

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

    private fun pushUiStress(res: StressResult) {
        val i = Intent(ACTION_UPDATING_STATE).apply {
            putExtra("message", "HRV updated")
            putExtra("name", deviceName)
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_DEVICE_NAME, deviceName)

            putExtra("hr", res.hrMean?.toInt() ?: lastHr ?: -1)
            putExtra("battery", lastBattery?.toString())
            putExtra("ppg", res.ppgMean?.toString() ?: lastPpg?.toString())

            putExtra("buttonState", recording.toString())
            putExtra("recording", recording)

            putExtra("status", res.status)
            putExtra("status_basic", res.statusBasic)
            putExtra("status_sliding", res.statusSliding)

            putExtra("hrv_rmssd", res.rmssd ?: 0.0)
            putExtra("hrv_pnn50", res.pnn50 ?: 0.0)

            putExtra("ml_score", res.mlScore ?: Double.NaN)
            putExtra("ml_label", res.mlLabel)
        }
        sendBroadcast(i)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
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
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}