package dev.shalaga44.you_are_okay

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.event.Level
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

private const val SERVER_PORT = 8080
private const val HEART_RATE_THRESHOLD_RATIO = 1.05
private const val RMSSD_THRESHOLD_RATIO = 1.09
private const val PNN50_THRESHOLD_RATIO = 1.09
private const val DEFAULT_BASELINE_FREQUENCY_HZ = 12
private const val MIN_HEART_RATE_BPM = 30.0
private const val MAX_HEART_RATE_BPM = 220.0

data class UuidReq(val uuid: String?)
data class UuidResp(val status: String, val message: String)

data class SampleRow(
    @SerializedName("Device") val device: String,
    @SerializedName("TimeDate") val timeDate: String? = null,
    @SerializedName("Time") val time: String? = null,
    @SerializedName("PPG") val ppg: Number? = null,
    @SerializedName("HR") val hr: Number? = null,
    val uuid: String,
    @SerializedName("User_ID") val userId: String? = null,
    val AccX: Number? = null, val AccY: Number? = null, val AccZ: Number? = null,
    val ppg0: Number? = null, val ppg2: Number? = null
)

data class StressResp(
    val status: String,
    val message: String,
    val mode: String,
    val device: String,
    val uuid: UUID,
    val hr_mean: Double?,
    val HRV: Map<String, Map<String, Double>>? = null
)

data class LivePoint(
    val idx: UUID,
    val t: Long,
    val hr_mean: Double?,
    val rmssd: Double?,
    val pnn50: Double?,
    val ppg_mean: Double?,
    val status: String?,
    val status_basic: String?,
    val status_sliding: String?
)

data class TestInfo(
    val recordCount: Int,
    val startTime: String,
    val endTime: String,
    val frequencyHz: Int,
    val baselineRowCount: Int,
    val heartRateThresholdRatio: Double,
    val rmssdThresholdRatio: Double
)

object Uuids : UUIDTable("uuid") {
    val uuid = uuid("uuid")
}

object Jobs : UUIDTable("job") {
    val device = text("device")
    val uuid = uuid("uuid").index()
    val frequency = integer("frequency")
    val baselineSize = integer("baseline_size")
    val hrThreshold = double("hr_threshold")
    val hrvThreshold = double("hrv_threshold")
}

object Requests : UUIDTable("request") {
    val device = text("device")
    val uuid = uuid("uuid").index()
    val hr = double("hr")
    val ppg = double("ppg")
    val time = integer("time").default(0)
    val timedate = integer("timedate").default(0)
    val userId = text("user_id").nullable()
    val createdAt = datetime("created_at").clientDefault { java.time.LocalDateTime.now() }
}

object Responses : UUIDTable("response") {
    val deviceCode = text("device_code").index()
    val uuid = uuid("uuid").index()
    val hrMean = double("hr_mean").nullable()
    val hrvRmssd = double("hrv_rmssd").nullable()
    val hrvPnn50 = double("hrv_pnn50").nullable()
    val statusBasic = text("status_basic").nullable()
    val statusSliding = text("status_sliding").nullable()
    val responseBody = text("response_body").nullable()
    val createdAt = datetime("created_at").clientDefault { java.time.LocalDateTime.now() }
}

object EventLabels : UUIDTable("eventlabel") {
    val deviceCode = text("device_code").index()
    val uuid = uuid("uuid").index()
    val name = text("name")
    val shortHand = text("short_hand")
    val value = text("value")
}

object Db {
    fun init(env: ApplicationEnvironment) {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/you_are_okay?sslmode=disable"
        val user = System.getenv("DB_USER") ?: "root"
        val pass = System.getenv("DB_PASS") ?: "postgres"
        val cfg = com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = url
            username = user
            password = pass
            maximumPoolSize = 8
            driverClassName = "org.postgresql.Driver"
        }
        Database.connect(HikariDataSource(cfg))
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Uuids, Jobs, Requests, Responses, EventLabels)
        }
    }
}

private fun zScoreNormalize(values: List<Double>): List<Double> {
    if (values.isEmpty()) return values
    val mean = values.average()
    val sd = sqrt(values.fold(0.0) { acc, x -> acc + (x - mean).pow(2) } / values.size)
    return if (sd == 0.0) values.map { 0.0 } else values.map { (it - mean) / sd }
}

private fun findPeaksSimple(z: List<Double>, minDistance: Int = 25, minHeight: Double = 0.5): List<Int> {
    val out = mutableListOf<Int>()
    var last = -minDistance
    for (i in 1 until z.size - 1) {
        if (z[i] > minHeight && z[i] > z[i - 1] && z[i] >= z[i + 1]) {
            if (i - last >= minDistance) {
                out += i
                last = i
            }
        }
    }
    return out
}

private fun nnIntervalsFromPeaks(peaks: List<Int>, samplingHz: Int): List<Double> {
    if (peaks.size < 2) return emptyList()
    val msPerSample = 1000.0 / samplingHz
    return peaks.zipWithNext().map { (a, b) -> (b - a) * msPerSample }
}

private fun computeRmssdAndPnn50(nnMs: List<Double>): Pair<Double, Double> {
    if (nnMs.size < 3) return 0.0 to 0.0
    val diff = nnMs.zipWithNext().map { (a, b) -> b - a }
    val rmssd = sqrt(diff.map { it * it }.average())
    val pnn50 = if (diff.isEmpty()) 0.0 else diff.count { abs(it) > 50.0 }.toDouble() * 100.0 / diff.size
    return rmssd to pnn50
}

private fun Iterable<Double>.averageOrNull(): Double? = if (!this.iterator().hasNext()) null else this.average()

private fun baselineRowCountForFrequency(freqHz: Int): Int = (10 * 60) / freqHz

private fun lastValidHeartRate(device: String, uuid: UUID): Double? = transaction {
    Responses
        .slice(Responses.hrMean)
        .select { (Responses.deviceCode eq device) and (Responses.uuid eq uuid) }
        .orderBy(Responses.id to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.get(Responses.hrMean)
        ?.takeIf { it != null && it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
}

object LiveBus {
    private val gson = Gson()
    val streams = java.util.concurrent.ConcurrentHashMap<String, MutableSharedFlow<LivePoint>>()
    fun flowFor(device: String, uuid: UUID): MutableSharedFlow<LivePoint> =
        streams.computeIfAbsent("$device|$uuid") {
            MutableSharedFlow(replay = 64, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    suspend fun emit(device: String, uuid: UUID, point: LivePoint) {
        flowFor(device, uuid).emit(point)
    }
    fun toJson(any: Any): String = gson.toJson(any)
}

fun Application.module() {
    install(DefaultHeaders)
    install(CallLogging) { level = Level.INFO }
    install(CORS) { anyHost(); allowNonSimpleContentTypes = true }
    install(ContentNegotiation) { gson { setPrettyPrinting(); disableHtmlEscaping() } }
    install(WebSockets)

    Db.init(environment)
    val gson = Gson()

    routing {
        get("/report") {
            val device = call.request.queryParameters["device"] ?: ""
            val uuidStr = call.request.queryParameters["uuid"] ?: ""
            val uuid = uuidStr.toUuidOrOrThrow()

            val info: TestInfo = transaction {
                val list = Responses
                    .slice(Responses.createdAt)
                    .select { (Responses.deviceCode eq device) and (Responses.uuid eq uuid) }
                    .orderBy(Responses.createdAt to SortOrder.ASC)
                    .toList()
                val count = list.size
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val startAt = list.firstOrNull()?.get(Responses.createdAt)?.format(fmt) ?: "-"
                val endAt = list.lastOrNull()?.get(Responses.createdAt)?.format(fmt) ?: "-"
                val job = Jobs.select { (Jobs.device eq device) and (Jobs.uuid eq uuid) }.firstOrNull()
                val freqHz = job?.get(Jobs.frequency) ?: DEFAULT_BASELINE_FREQUENCY_HZ
                val baselineRows = job?.get(Jobs.baselineSize) ?: baselineRowCountForFrequency(freqHz)
                val hrRatio = job?.get(Jobs.hrThreshold) ?: HEART_RATE_THRESHOLD_RATIO
                val rmssdRatio = job?.get(Jobs.hrvThreshold) ?: RMSSD_THRESHOLD_RATIO
                TestInfo(count, startAt, endAt, freqHz, baselineRows, hrRatio, rmssdRatio)
            }

            val html = """
                <!doctype html>
                <html>
                <head>
                    <meta charset="utf-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1"/>
                    <title>Result — $device / $uuidStr</title>
                    <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/css/bootstrap.min.css">
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/3.3.2/chart.min.js"></script>
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/chartjs-plugin-annotation/1.0.1/chartjs-plugin-annotation.min.js"></script>
                    <style>
                      body { background:#f8fafc; }
                      canvas { background:#fff; border:1px solid #e5e7eb; border-radius:12px; padding:8px; }
                      .spacer { height: 28px; }
                    </style>
                </head>
                <body>
                  <div class="container pt-5">
                    <h1>Result</h1>
                    <div class="pb-3">
                      <h2>Test Info</h2>
                      <ul>
                        <li><b>Device Code:</b>&nbsp;$device</li>
                        <li><b>UUID:</b>&nbsp;$uuidStr</li>
                        <li><b>Records:</b>&nbsp;${info.recordCount}</li>
                        <li><b>Frequency:</b>&nbsp;${info.frequencyHz}</li>
                        <li><b>HR_threshold:</b>&nbsp;${info.heartRateThresholdRatio}</li>
                        <li><b>HRV_threshold:</b>&nbsp;${info.rmssdThresholdRatio}</li>
                        <li><b>Base Line Size (Window Size):</b>&nbsp;${info.baselineRowCount}</li>
                        <li><b>Started at:</b>&nbsp;${info.startTime}</li>
                        <li><b>Ended at:</b>&nbsp;${info.endTime}</li>
                      </ul>
                    </div>

                    <h2>Content</h2>
                <div class="mb-2">
                  <span class="badge" style="background:#ff9f40">SD</span>
                  <small class="text-muted ml-1">= Stress Detection (baseline)</small>
                  &nbsp;&nbsp;
                  <span class="badge" style="background:purple">SW</span>
                  <small class="text-muted ml-1">= Sliding Window</small>
                </div>
                    <div class="row">
                      <div class="col-12">
                        <h4>RMSSD vs Time (live) + detections</h4>
                        <canvas id="rmssd-time-chart"></canvas>
                      </div>
                    </div>

                    <div class="spacer"></div>
                   
                    
                    <div class="row">
                      <div class="col-12">
                        <h4>PPG Raw Data vs Heart Rate vs Row Number (live)</h4>
                        <canvas id="ppg-row-chart"></canvas>
                      </div>
                    </div>
                   <div class="pb-5 pt-3">
                      <h2>Summary</h2>
                      <p>For the current test:</p>
                      <ul>
                        <li>There are <b id="basicCount">0</b> stress events detected by <strong class="text-capitalize">stress detection algorithm</strong></li>
                        <li>There are <b id="slidingCount">0</b> stress events detected by <strong class="text-capitalize">sliding window algorithm</strong></li>
                      </ul>
                    </div>
                
                  </div>

                  <script>
                    const device = ${Gson().toJson(device)};
                    const uuid = ${Gson().toJson(uuid)};

                    const wsProto = location.protocol === 'https:' ? 'wss' : 'ws';
                    const ws = new WebSocket(`${'$'}{wsProto}://${'$'}{location.host}/api/v1/ws?device=${'$'}{encodeURIComponent(device)}&uuid=${'$'}{encodeURIComponent(uuid)}`);

                    const MAX_POINTS = 1200;
                    let basicCount = 0, slidingCount = 0, annIdx = 0;
                    let prevBasic = false, prevSliding = false;

                    function updateEventCounters(basic, sliding) {
                      if (basic && !prevBasic) {
                        basicCount++;
                        document.getElementById('basicCount').textContent = basicCount;
                      }
                      if (sliding && !prevSliding) {
                        slidingCount++;
                        document.getElementById('slidingCount').textContent = slidingCount;
                      }
                      prevBasic = basic;
                      prevSliding = sliding;
                    }

                    function addLabeledDot(cfg, idPrefix, labelText, xScaleID, yScaleID, xValue, yValue, dotColor) {
                      if (yValue == null || xValue == null) return;
                      cfg.options.plugins.annotation.annotations[idPrefix + (annIdx++)] = {
                        type: 'point',
                        xScaleID: xScaleID,
                        yScaleID: yScaleID,
                        xValue: xValue,
                        yValue: yValue,
                        backgroundColor: dotColor,
                        radius: 5,
                        borderWidth: 0,
                        label: {
                          display: true,
                          content: labelText,
                          position: 'end',
                          yAdjust: -8,
                          backgroundColor: 'rgba(255,255,255,0.9)',
                          color: '#111',
                          padding: 2,
                          borderRadius: 4,
                          font: { size: 10, weight: 'bold' }
                        }
                      };
                    }

                    const rmssdCtx = document.getElementById('rmssd-time-chart').getContext('2d');
                    const rmssdCfg = {
                      type: 'line',
                      data: {
                        labels: [],
                        datasets: [
                          { label: 'RMSSD (ms)', data: [], borderColor: '#1EAEDB', backgroundColor: '#1EAEDB20', tension: 0.2, pointRadius: 0, yAxisID: 'y2' },
                          { label: 'Heart Rate (bpm)', data: [], borderColor: '#FF6384', backgroundColor: '#FF638420', tension: 0.2, pointRadius: 0, yAxisID: 'y1' }
                        ]
                      },
                      options: {
                        responsive: true,
                        interaction: { mode: 'nearest', intersect: false },
                        plugins: {
                          title: { display: true, text: 'RMSSD vs Time (with stress detections)' },
                          legend: { display: true },
                          annotation: { drawTime: 'afterDatasetsDraw', annotations: {} }
                        },
                        scales: {
                          x: { title: { display: true, text: 'Time' } },
                          y1: { type: 'linear', position: 'left', title: { display: true, text: 'Heart Rate (bpm)' } },
                          y2: { type: 'linear', position: 'right', title: { display: true, text: 'RMSSD (ms)' }, grid: { drawOnChartArea: false } }
                        }
                      }
                    };
                    const rmssdChart = new Chart(rmssdCtx, rmssdCfg);

                    function pushRmssdPoint(ts, hr, rmssd, basicWarn, slidingWarn) {
                      const label = new Date(ts).toLocaleTimeString();
                      rmssdChart.data.labels.push(label);
                      rmssdChart.data.datasets[0].data.push(rmssd ?? null);
                      rmssdChart.data.datasets[1].data.push(hr ?? null);

                      if (rmssd != null && (basicWarn || slidingWarn)) {
                        const both = basicWarn && slidingWarn;
                        const tag = both ? 'SD+SW' : (basicWarn ? 'SD' : 'SW');
                        const color = both ? 'rgba(60,60,60,0.95)' : (basicWarn ? 'rgba(255,159,64,0.9)' : 'rgba(128,0,128,0.9)');
                        addLabeledDot(rmssdCfg, 'evt_', tag, 'x', 'y2', label, rmssd, color);
                      }

                      if (rmssdChart.data.labels.length > MAX_POINTS) {
                        rmssdChart.data.labels.shift();
                        rmssdChart.data.datasets.forEach(ds => ds.data.shift());
                      }
                      rmssdChart.update('none');
                    }

                    const ppgCtx = document.getElementById('ppg-row-chart').getContext('2d');
                    const ppgCfg = {
                      type: 'line',
                      data: {
                        labels: [],
                        datasets: [
                          { label: 'PPG (mean/normalized)', data: [], borderColor: '#1EAEDB', backgroundColor: '#1EAEDB20', tension: 0.2, pointRadius: 0, yAxisID: 'py' },
                          { label: 'Heart Rate (bpm)', data: [], borderColor: '#FF6384', backgroundColor: '#FF638420', tension: 0.2, pointRadius: 0, yAxisID: 'hy' }
                        ]
                      },
                      options: {
                        responsive: true,
                        interaction: { mode: 'nearest', intersect: false },
                        plugins: {
                          title: { display: true, text: 'PPG Raw Data vs Heart Rate vs Row Number (live)' }
                        },
                        scales: {
                          x: { title: { display: true, text: 'Row Number' } },
                          py: { type: 'linear', position: 'left', title: { display: true, text: 'PPG' } },
                          hy: { type: 'linear', position: 'right', title: { display: true, text: 'Heart Rate (bpm)' }, grid: { drawOnChartArea: false } }
                        }
                      }
                    };
                    const ppgChart = new Chart(ppgCtx, ppgCfg);
                    let rowCounter = 0;

                    function pushPpgRowPoint(hr, ppgMean) {
                      rowCounter += 1;
                      ppgChart.data.labels.push(String(rowCounter));
                      ppgChart.data.datasets[0].data.push(ppgMean ?? null);
                      ppgChart.data.datasets[1].data.push(hr ?? null);

                      if (ppgChart.data.labels.length > MAX_POINTS) {
                        ppgChart.data.labels.shift();
                        ppgChart.data.datasets.forEach(ds => ds.data.shift());
                      }
                      ppgChart.update('none');
                    }

                    ws.onmessage = (evt) => {
                      const p = JSON.parse(evt.data);
                      const basic = p.status_basic === 'basic_warning';
                      const sliding = p.status_sliding === 'sliding_warning';
                      if (typeof p.t === 'number') {
                        pushRmssdPoint(p.t, p.hr_mean ?? null, p.rmssd ?? null, basic, sliding);
                        pushPpgRowPoint(p.hr_mean ?? null, p.ppg_mean ?? null);
                        updateEventCounters(basic, sliding);
                      }
                    };
                    ws.onopen = () => console.log('WS open');
                    ws.onclose = (e) => console.log('WS closed', e.code, e.reason);
                    ws.onerror = (e) => console.error('WS error', e);
                  </script>
                </body>
                </html>
            """.trimIndent()

            call.respondText(html, ContentType.Text.Html)
        }

        route("/api/v1") {
            post("/uuid") {
                val body = runCatching { call.receive<UuidReq>() }.getOrNull()
                val uuid = body?.uuid?.toUuidOrNull()
                if (uuid == null) {
                    call.respond(HttpStatusCode.BadRequest, UuidResp("error", "uuid required"))
                    return@post
                }
                val inserted = transaction {
                    if (Uuids.select { Uuids.uuid eq uuid }.empty()) {
                        Uuids.insert { it[Uuids.uuid] = uuid }
                        true
                    } else false
                }
                call.respond(
                    HttpStatusCode.OK,
                    UuidResp(
                        status = if (inserted) "success" else "error",
                        message = if (inserted) "The UUID is valid." else "The UUID already existed."
                    )
                )
            }

            post("/stress") {
                val mode = call.request.queryParameters["mode"] ?: "hrv"
                val rows = runCatching { call.receive<List<SampleRow>>() }.getOrNull()
                if (rows.isNullOrEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("status" to "error", "message" to "Empty payload"))
                    return@post
                }

                val first = rows.first()
                val device = first.device
                val uuid = first.uuid.toUuidOrOrThrow()
                val userId = first.userId?.trim().takeUnless { it.isNullOrBlank() }

                val hrRaw = rows.mapNotNull { it.hr?.toDouble() }
                val hrVals = hrRaw.filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
                val hrMean: Double? = if (hrVals.isNotEmpty()) hrVals.averageOrNull() else lastValidHeartRate(device, uuid)

                val ppgVals = rows.mapNotNull { it.ppg?.toDouble() }
                val ppgMean = ppgVals.averageOrNull()

                val (frequencyHz, baselineRows, hrRatio, rmssdRatio) = transaction {
                    val existing = Jobs.select { (Jobs.device eq device) and (Jobs.uuid eq uuid) }.firstOrNull()
                    if (existing == null) {
                        val f = DEFAULT_BASELINE_FREQUENCY_HZ
                        val b = baselineRowCountForFrequency(f)
                        Jobs.insert {
                            it[Jobs.device] = device
                            it[Jobs.uuid] = uuid
                            it[Jobs.frequency] = f
                            it[Jobs.baselineSize] = b
                            it[Jobs.hrThreshold] = HEART_RATE_THRESHOLD_RATIO
                            it[Jobs.hrvThreshold] = RMSSD_THRESHOLD_RATIO
                        }
                        Quadruple(f, b, HEART_RATE_THRESHOLD_RATIO, RMSSD_THRESHOLD_RATIO)
                    } else {
                        Quadruple(
                            existing[Jobs.frequency],
                            existing[Jobs.baselineSize],
                            existing[Jobs.hrThreshold],
                            existing[Jobs.hrvThreshold]
                        )
                    }
                }

                transaction {
                    Requests.insert {
                        it[Requests.device] = device
                        it[Requests.uuid] = uuid
                        it[Requests.hr] = (hrMean ?: 0.0)
                        it[Requests.ppg] = (ppgMean ?: 0.0)
                        it[Requests.userId] = userId
                    }
                }

                var message = ""
                var status = "success"
                var rmssd: Double? = null
                var pnn50: Double? = null
                var statusBasic = "success"
                var statusSliding = "success"

                if (mode == "hrv") {
                    val norm = zScoreNormalize(ppgVals)
                    val peaks = findPeaksSimple(norm, minDistance = 50, minHeight = 0.2)
                    val nn = nnIntervalsFromPeaks(peaks, samplingHz = 50)
                    val (rm, p50) = computeRmssdAndPnn50(nn)
                    rmssd = rm
                    pnn50 = p50

                    val (baseRmssd, baseHr, basePnn, totalCount) = transaction {
                        val total = Responses
                            .select { (Responses.deviceCode eq device) and (Responses.uuid eq uuid) }
                            .count()
                        val baseSlice = Responses
                            .slice(Responses.hrvRmssd, Responses.hrMean, Responses.hrvPnn50)
                            .select { (Responses.deviceCode eq device) and (Responses.uuid eq uuid) }
                            .orderBy(Responses.id to SortOrder.ASC)
                            .limit(baselineRows, offset = 1)
                            .toList()
                        Quadruple(
                            baseSlice.mapNotNull { it[Responses.hrvRmssd] }.averageOrNull(),
                            baseSlice.mapNotNull { it[Responses.hrMean] }.filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }.averageOrNull(),
                            baseSlice.mapNotNull { it[Responses.hrvPnn50] }.averageOrNull(),
                            total.toInt()
                        )
                    }

                    if (totalCount > baselineRows) {
                        val basicHrvDrop = (rmssd != null && baseRmssd != null && rmssd!! * rmssdRatio < baseRmssd)
                        val basicHrRise  = (hrMean != null && baseHr != null && hrMean!! > baseHr * hrRatio)
                        val basicPnnDrop = (pnn50 != null && basePnn != null && pnn50!! * PNN50_THRESHOLD_RATIO < basePnn)
                        if (basicHrvDrop && basicHrRise && basicPnnDrop) statusBasic = "basic_warning"
                    }

                    if (totalCount > baselineRows) {
                        val entry = totalCount - baselineRows
                        val (winRmssd, winHr, winPnn) = transaction {
                            val slice = Responses
                                .slice(Responses.hrvRmssd, Responses.hrMean, Responses.hrvPnn50)
                                .select { (Responses.deviceCode eq device) and (Responses.uuid eq uuid) }
                                .orderBy(Responses.id to SortOrder.ASC)
                                .limit(baselineRows, offset = entry.toLong())
                                .toList()
                            Triple(
                                slice.mapNotNull { it[Responses.hrvRmssd] }.averageOrNull(),
                                slice.mapNotNull { it[Responses.hrMean] }.filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }.averageOrNull(),
                                slice.mapNotNull { it[Responses.hrvPnn50] }.averageOrNull()
                            )
                        }
                        val slideHrvDrop = (rmssd != null && winRmssd != null && rmssd!! * rmssdRatio < winRmssd)
                        val slideHrRise  = (hrMean != null && winHr != null && hrMean!! > winHr * hrRatio)
                        val slidePnnDrop = (pnn50 != null && winPnn != null && pnn50!! * PNN50_THRESHOLD_RATIO < winPnn)
                        if (slideHrvDrop && slideHrRise && slidePnnDrop) statusSliding = "sliding_warning"
                    }

                    if (statusBasic == "basic_warning" || statusSliding == "sliding_warning") {
                        status = "warning"
                        message = "HRV RMSSD changed significantly. You probably under stress."
                    }

                    val hrvJson = mapOf(
                        "HRV_RMSSD" to mapOf("0" to (rmssd ?: 0.0)),
                        "HRV_pNN50" to mapOf("0" to (pnn50 ?: 0.0))
                    )
                    val respBodyStr = gson.toJson(hrvJson)

                    val insertedId = transaction {
                        Responses.insert {
                            it[Responses.deviceCode] = device
                            it[Responses.uuid] = uuid
                            it[Responses.hrMean] = hrMean
                            it[Responses.hrvRmssd] = rmssd
                            it[Responses.hrvPnn50] = pnn50
                            it[Responses.statusBasic] = statusBasic
                            it[Responses.statusSliding] = statusSliding
                            it[Responses.responseBody] = respBodyStr
                        } get Responses.id
                    }.value

                    launch {
                        LiveBus.emit(
                            device, uuid,
                            LivePoint(
                                idx = insertedId,
                                t = System.currentTimeMillis(),
                                hr_mean = hrMean,
                                rmssd = rmssd,
                                pnn50 = pnn50,
                                ppg_mean = ppgMean,
                                status = status,
                                status_basic = statusBasic,
                                status_sliding = statusSliding
                            )
                        )
                    }

                    call.respond(
                        StressResp(
                            status = status,
                            message = message,
                            mode = mode,
                            device = device,
                            uuid = uuid,
                            hr_mean = hrMean,
                            HRV = hrvJson
                        )
                    )
                } else {
                    val insertedId = transaction {
                        Responses.insert {
                            it[Responses.deviceCode] = device
                            it[Responses.uuid] = uuid
                            it[Responses.hrMean] = hrMean
                            it[Responses.statusBasic] = "success"
                            it[Responses.statusSliding] = "success"
                            it[Responses.responseBody] = null
                        } get Responses.id
                    }.value
                    launch {
                        LiveBus.emit(
                            device, uuid,
                            LivePoint(
                                idx = insertedId,
                                t = System.currentTimeMillis(),
                                hr_mean = hrMean,
                                rmssd = null,
                                pnn50 = null,
                                ppg_mean = ppgMean,
                                status = "success",
                                status_basic = "success",
                                status_sliding = "success"
                            )
                        )
                    }
                    call.respond(
                        StressResp(
                            status = "success",
                            message = "",
                            mode = "hr",
                            device = device,
                            uuid = uuid,
                            hr_mean = hrMean,
                            HRV = null
                        )
                    )
                }
            }

            webSocket("/ws") {
                val device = call.request.queryParameters["device"] ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "device required")); return@webSocket
                }
                val uuid = call.request.queryParameters["uuid"]?.toUuidOrNull() ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "uuid required")); return@webSocket
                }
                val flow = LiveBus.flowFor(device, uuid)
                val job = launch {
                    flow.collect { point ->
                        send(Frame.Text(LiveBus.toJson(point)))
                    }
                }
                try { for (@Suppress("UNUSED_VARIABLE") f in incoming) {} } finally { job.cancel() }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

fun String.toUuidOrNull(): UUID? = runCatching { toUuidOrOrThrow() }.getOrNull()
fun String.toUuidOrOrThrow(): UUID = UUID.fromString(this@toUuidOrOrThrow.trim())

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module).start(wait = true)
}