package dev.shalaga44.you_are_okay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject
import java.util.UUID

class DataCollector : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra(DataService.EXTRA_DEVICE_ID)
        val deviceName = intent.getStringExtra(DataService.EXTRA_DEVICE_NAME)
        Intent(this, DataService::class.java).apply {
            deviceId?.let { putExtra(DataService.EXTRA_DEVICE_ID, it) }
            deviceName?.let { putExtra(DataService.EXTRA_DEVICE_NAME, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(this) else startService(this)
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    DataCollectorScreen()
                }
            }
        }
    }
}

@Composable
private fun DataCollectorScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sp = remember { ctx.getSharedPreferences("you_are_okay_prefs", Context.MODE_PRIVATE) }

    var deviceLabel by remember { mutableStateOf("--") }
    var battery by remember { mutableStateOf("--") }
    var ppg by remember { mutableStateOf("--") }
    var hr by remember { mutableStateOf("--") }
    var message by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var sessionUuid by remember { mutableStateOf("") }

    var status by remember { mutableStateOf<String?>(null) }
    var statusBasic by remember { mutableStateOf<String?>(null) }
    var statusSliding by remember { mutableStateOf<String?>(null) }

    var rmssd by remember { mutableStateOf<Double?>(null) }
    var pnn50 by remember { mutableStateOf<Double?>(null) }
    var mlScore by remember { mutableStateOf<Double?>(null) }
    var mlLabel by remember { mutableStateOf<String?>(null) }

    val requestQueue = remember { Volley.newRequestQueue(ctx.applicationContext) }

    LaunchedEffect(Unit) {
        ctx.sendBroadcast(Intent(DataService.ACTION_REQUEST_UPDATE))
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply { addAction(DataService.ACTION_UPDATING_STATE) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != DataService.ACTION_UPDATING_STATE) return

                intent.getStringExtra("name")?.let { deviceLabel = it }
                intent.getStringExtra("battery")?.let { battery = it }
                intent.getStringExtra("ppg")?.let { ppg = it }
                intent.getStringExtra("message")?.let { message = it }
                intent.getStringExtra("buttonState")?.let { isRecording = it.equals("true", true) }

                val hrVal = intent.getIntExtra("hr", -1)
                hr = if (hrVal >= 0) hrVal.toString() else "--"

                status = intent.getStringExtra("status")
                statusBasic = intent.getStringExtra("status_basic")
                statusSliding = intent.getStringExtra("status_sliding")

                val rm = intent.getDoubleExtra("hrv_rmssd", Double.NaN)
                rmssd = if (rm.isNaN()) null else rm

                val p50 = intent.getDoubleExtra("hrv_pnn50", Double.NaN)
                pnn50 = if (p50.isNaN()) null else p50

                val score = intent.getDoubleExtra("ml_score", Double.NaN)
                mlScore = if (score.isNaN()) null else score

                mlLabel = intent.getStringExtra("ml_label")
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> ctx.sendBroadcast(Intent("onResume"))
                Lifecycle.Event.ON_PAUSE  -> ctx.sendBroadcast(Intent("onPause"))
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Collector", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        StatRow("Device", deviceLabel)
        StatRow("Battery", if (battery == "--") "--" else "$battery%")
        StatRow("HR", hr)
        StatRow("PPG", ppg)
        if (message.isNotBlank()) Text("Message: $message")

        Spacer(Modifier.height(12.dp))

        Text("HRV / Stress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        StatRow("RMSSD (ms)", rmssd?.let { String.format("%.1f", it) } ?: "--")
        StatRow("pNN50 (%)", pnn50?.let { String.format("%.1f", it) } ?: "--")
        Text(
            "Status: ${status ?: "--"} (basic=${statusBasic ?: "--"}, sliding=${statusSliding ?: "--"})",
            style = MaterialTheme.typography.bodyMedium
        )
        StatRow("ML score", mlScore?.let { String.format("%.3f", it) } ?: "--")
        StatRow("ML label", mlLabel ?: "--")

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                if (!isRecording) {
                    val savedUserId = sp.getString(DataService.EXTRA_USER_ID, "") ?: ""
                    if (savedUserId.isBlank()) {
                        Toast.makeText(ctx, "Set User ID first on the home screen", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    sessionUuid = sessionUuid.ifBlank { UUID.randomUUID().toString() }

                    registerUuid(requestQueue, sessionUuid)

                    Intent(DataService.ACTION_SENDING_STATE).apply {
                        putExtra(DataService.EXTRA_SENT, true)
                        putExtra(DataService.EXTRA_UUID, sessionUuid)
                        putExtra(DataService.EXTRA_USER_ID, savedUserId)
                        ctx.sendBroadcast(this)
                    }
                } else {
                    Intent(DataService.ACTION_SENDING_STATE).apply {
                        putExtra(DataService.EXTRA_SENT, false)
                        ctx.sendBroadcast(this)
                    }
                }
            }) {
                Text(if (isRecording) "Stop Recording" else "Start Recording")
            }

            OutlinedButton(onClick = { ctx.sendBroadcast(Intent(DataService.ACTION_TURNOFF)) }) {
                Text("Stop Service")
            }
        }
    }
}

private fun registerUuid(queue: RequestQueue, uuid: String, attempt: Int = 1) {
    val url = DataService.UUID_ENDPOINT
    val body = JSONObject().put("uuid", uuid)
    val req = object : JsonObjectRequest(Method.POST, url, body, { resp ->
        if (resp.optString("status", "success").equals("error", true) && attempt < 3) {
            registerUuid(queue, UUID.randomUUID().toString(), attempt + 1)
        }
    }, { err ->
        Log.e("DataCollector", "UUID register error: $err")
    }) {}
    req.tag = "uuid_register"
    queue.add(req)
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}