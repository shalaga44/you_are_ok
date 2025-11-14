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
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.event.Level
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val SERVER_PORT = 8080

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
    val ppg0: Number? = null, val ppg2: Number? = null,
    @SerializedName("ibi_ms_list")
    val ibiMsList: List<Double>? = null
)

data class StressResp(
    val status: String,
    val message: String,
    val mode: String,
    val device: String,
    val uuid: UUID,
    val hr_mean: Double?,
    val HRV: Map<String, Map<String, Double>>? = null,
    val ml_score: Double? = null,
    val ml_label: String? = null
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
    val status_sliding: String?,
    val ml_score: Double? = null,
    val ml_label: String? = null,
    val ibi_ms_list: List<Double>? = null
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
        val url = System.getenv("DB_URL")
            ?: "jdbc:postgresql://localhost:5432/you_are_okay?sslmode=disable"
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

// MIN_HEART_RATE_BPM, MAX_HEART_RATE_BPM, HEART_RATE_THRESHOLD_RATIO,
// RMSSD_THRESHOLD_RATIO, PNN50_THRESHOLD_RATIO, DEFAULT_BASELINE_FREQUENCY_HZ,
// zScoreNormalize, findPeaksSimple, nnIntervalsFromPeaks, computeRmssdAndPnn50,
// mlStressProbability, mlLabelFromScore, baselineRowCountForFrequency,
// averageOrNull are expected to be in shared code.

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
            MutableSharedFlow(
                replay = 64,
                extraBufferCapacity = 512,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
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
                val job =
                    Jobs.select { (Jobs.device eq device) and (Jobs.uuid eq uuid) }.firstOrNull()
                val freqHz = job?.get(Jobs.frequency) ?: DEFAULT_BASELINE_FREQUENCY_HZ
                val baselineRows =
                    job?.get(Jobs.baselineSize) ?: baselineRowCountForFrequency(freqHz)
                val hrRatio = job?.get(Jobs.hrThreshold) ?: HEART_RATE_THRESHOLD_RATIO
                val rmssdRatio = job?.get(Jobs.hrvThreshold) ?: RMSSD_THRESHOLD_RATIO
                TestInfo(count, startAt, endAt, freqHz, baselineRows, hrRatio, rmssdRatio)
            }

            @Language("HTML")
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

                    <div class="pb-3">
                      <h4>ML Status</h4>
                      <p>
                        Current ML stress probability:
                        <b id="mlScore">-</b>
                        &nbsp;&nbsp;
                        Label:
                        <span id="mlLabel" class="badge badge-secondary">-</span>
                      </p>
                    </div>

                    <div class="row">
                      <div class="col-12">
                        <h4>RMSSD vs Time (live) + detections</h4>
                        <canvas id="rmssd-time-chart"></canvas>
                      </div>
                    </div>

                    <div class="spacer"></div>

                    <!-- HR vs RMSSD scatter: same size dots, stress = red, normal = grey -->
                    <div class="row">
                      <div class="col-12">
                        <h4>HR vs RMSSD (stress points in red)</h4>
                        <canvas id="hr-rmssd-scatter"></canvas>
                      </div>
                    </div>

                    <div class="spacer"></div>

                    <!-- IBI vs Time (bubble size = HRV, 120s window, pale pink) -->
                    <div class="row">
                      <div class="col-12">
                        <h4>IBI vs Time (bubble size = HRV, 120s window)</h4>
                        <canvas id="ibi-time-bubble"></canvas>
                        <div class="mt-2 mb-3 text-center">
                          <span id="ibi-rmssd-label" style="font-weight:bold;">
                            RMSSD (last 120s from IBI): –
                          </span>
                        </div>
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

                    function updateMlStatus(score, label) {
                      const scoreEl = document.getElementById('mlScore');
                      const labelEl = document.getElementById('mlLabel');

                      if (score == null) {
                        scoreEl.textContent = '-';
                      } else {
                        scoreEl.textContent = score.toFixed(3);
                      }

                      let text = '-';
                      let cls = 'badge badge-secondary';

                      if (label === 'ml_stress') {
                        text = 'ML: Stress';
                        cls = 'badge badge-danger';
                      } else if (label === 'ml_relaxed') {
                        text = 'ML: Relaxed';
                        cls = 'badge badge-success';
                      } else if (label === 'ml_uncertain') {
                        text = 'ML: Uncertain';
                        cls = 'badge badge-warning';
                      }

                      labelEl.textContent = text;
                      labelEl.className = cls;
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

                    // RMSSD + HR vs time
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
                          y1: {
                            type: 'linear',
                            position: 'left',
                            title: { display: true, text: 'Heart Rate (bpm)', color: '#FF6384' },
                            ticks: { color: '#FF6384' },
                            grid: { color: 'rgba(255,99,132,0.15)' }
                          },
                          y2: {
                            type: 'linear',
                            position: 'right',
                            title: { display: true, text: 'RMSSD (ms)', color: '#1EAEDB' },
                            ticks: { color: '#1EAEDB' },
                            grid: { drawOnChartArea: false, color: 'rgba(30,174,219,0.4)' }
                          }
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

                    // HR vs RMSSD scatter (normal grey, stress red)
                    const hrRmssdCtx = document.getElementById('hr-rmssd-scatter').getContext('2d');
                    const hrRmssdCfg = {
                      type: 'scatter',
                      data: {
                        datasets: [
                          {
                            label: 'Normal',
                            data: [],
                            borderColor: '#6B7280',
                            backgroundColor: '#6B7280',
                            pointRadius: 5,
                            pointHoverRadius: 6
                          },
                          {
                            label: 'Stress',
                            data: [],
                            borderColor: '#EF4444',
                            backgroundColor: '#EF4444',
                            pointRadius: 5,
                            pointHoverRadius: 6
                          }
                        ]
                      },
                      options: {
                        responsive: true,
                        plugins: {
                          title: { display: true, text: 'HR vs RMSSD (stress points in red)' },
                          legend: { display: true }
                        },
                        scales: {
                          x: {
                            type: 'linear',
                            title: { display: true, text: 'Heart Rate (bpm)' }
                          },
                          y: {
                            type: 'linear',
                            title: { display: true, text: 'RMSSD (ms)' }
                          }
                        }
                      }
                    };
                    const hrRmssdChart = new Chart(hrRmssdCtx, hrRmssdCfg);

                    function pushHrRmssdPoint(hr, rmssd, isStress) {
                      if (hr == null || rmssd == null || !isFinite(hr) || !isFinite(rmssd)) return;
                      const dsIndex = isStress ? 1 : 0;
                      const ds = hrRmssdChart.data.datasets[dsIndex];
                      ds.data.push({ x: hr, y: rmssd });

                      let total = 0;
                      for (let i = 0; i < hrRmssdChart.data.datasets.length; i++) {
                        total += hrRmssdChart.data.datasets[i].data.length;
                      }
                      if (total > MAX_POINTS) {
                        for (let i = 0; i < hrRmssdChart.data.datasets.length; i++) {
                          const d = hrRmssdChart.data.datasets[i].data;
                          if (d.length > 0) d.shift();
                        }
                      }

                      hrRmssdChart.update('none');
                    }

                    // IBI vs Time bubble chart (x: seconds from start, y: IBI 400–1400ms, r = HRV (rmssd), pale pink, window=120s)
                    const ibiTimeCtx = document.getElementById('ibi-time-bubble').getContext('2d');
                    const ibiTimeCfg = {
                      type: 'bubble',
                      data: {
                        datasets: [
                          {
                            label: 'IBI vs Time',
                            data: [],
                            borderColor: '#F472B6',
                            backgroundColor: '#F9A8D4AA'
                          }
                        ]
                      },
                      options: {
                        responsive: true,
                        plugins: {
                          title: { display: true, text: 'IBI vs Time (bubble size = HRV, pale pink, 120s window)' },
                          legend: { display: false }
                        },
                        scales: {
                          x: {
                            type: 'linear',
                            title: { display: true, text: 'Time (HH:MM from start)' },
                            ticks: {
                              callback: function(value) {
                                const sec = Number(value) || 0;
                                const h = Math.floor(sec / 3600);
                                const m = Math.floor((sec % 3600) / 60);
                                const hh = h.toString().padStart(2,'0');
                                const mm = m.toString().padStart(2,'0');
                                return hh + ':' + mm;
                              }
                            }
                          },
                          y: {
                            type: 'linear',
                            title: { display: true, text: 'IBI (ms)' },
                            min: 400,
                            max: 1400
                          }
                        }
                      }
                    };
                    const ibiTimeChart = new Chart(ibiTimeCtx, ibiTimeCfg);

                    let ibiVirtualTimeSec = 0.0; // cumulative beat time in seconds
                    let ibiWindow = [];          // [{ t: seconds, ibi: ms }]

                    function computeRmssdFromIbi(ibiArrMs) {
                      if (!ibiArrMs || ibiArrMs.length < 3) return null;
                      const diffs = [];
                      for (let i = 0; i < ibiArrMs.length - 1; i++) {
                        const d = ibiArrMs[i + 1] - ibiArrMs[i];
                        diffs.push(d);
                      }
                      if (diffs.length === 0) return null;
                      let sumSq = 0;
                      for (let i = 0; i < diffs.length; i++) {
                        const d = diffs[i];
                        sumSq += d * d;
                      }
                      const meanSq = sumSq / diffs.length;
                      return Math.sqrt(meanSq);
                    }

                    function pushIbiPoints(ibiListMs, rmssdValue) {
                      if (!ibiListMs || ibiListMs.length === 0) return;
                      const ds = ibiTimeChart.data.datasets[0];

                      const maxRmssdForRadius = 200.0;
                      const minR = 3.0;
                      const maxR = 18.0;
                      const rNorm = (typeof rmssdValue === 'number')
                        ? Math.min(rmssdValue, maxRmssdForRadius) / maxRmssdForRadius
                        : 0.5;
                      const radius = minR + rNorm * (maxR - minR);

                      for (let i = 0; i < ibiListMs.length; i++) {
                        const ibiMs = ibiListMs[i];
                        if (typeof ibiMs !== 'number' || !isFinite(ibiMs)) continue;

                        ibiVirtualTimeSec += ibiMs / 1000.0;
                        const tSec = ibiVirtualTimeSec;

                        // For plotting, clamp to [400, 1400] on the axis, but keep real ibi in window for RMSSD
                        let ibiPlot = ibiMs;
                        if (ibiPlot < 400.0) ibiPlot = 400.0;
                        if (ibiPlot > 1400.0) ibiPlot = 1400.0;

                        ds.data.push({ x: tSec, y: ibiPlot, r: radius });
                        ibiWindow.push({ t: tSec, ibi: ibiMs }); // real IBI for RMSSD
                      }

                      const windowSizeSec = 120.0;
                      const windowStart = Math.max(0, ibiVirtualTimeSec - windowSizeSec);

                      ds.data = ds.data.filter(p => p.x >= windowStart);
                      ibiWindow = ibiWindow.filter(p => p.t >= windowStart);

                      const ibiArr = ibiWindow.map(p => p.ibi);
                      const rmssdWin = computeRmssdFromIbi(ibiArr);
                      const lbl = document.getElementById('ibi-rmssd-label');
                      if (lbl) {
                        if (rmssdWin == null) {
                          lbl.textContent = 'RMSSD (last 120s from IBI): –';
                        } else {
                          lbl.textContent = 'RMSSD (last 120s from IBI): ' + rmssdWin.toFixed(0) + ' ms';
                        }
                      }

                      ibiTimeChart.options.scales.x.min = windowStart;
                      ibiTimeChart.options.scales.x.max = windowStart + windowSizeSec;

                      ibiTimeChart.update('none');
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
                      const isStress = basic || sliding;

                      const mlScore = (typeof p.ml_score === 'number') ? p.ml_score : null;
                      const mlLabel = p.ml_label || null;
                      updateMlStatus(mlScore, mlLabel);

                      if (typeof p.t === 'number') {
                        const ts = p.t;
                        const hr = (typeof p.hr_mean === 'number') ? p.hr_mean : null;
                        const rmssd = (typeof p.rmssd === 'number') ? p.rmssd : null;
                        const ppgMean = (typeof p.ppg_mean === 'number') ? p.ppg_mean : null;
                        const ibiList = Array.isArray(p.ibi_ms_list)
                          ? p.ibi_ms_list.filter(x => typeof x === 'number' && isFinite(x))
                          : null;

                        pushRmssdPoint(ts, hr, rmssd, basic, sliding);
                        pushPpgRowPoint(hr, ppgMean);
                        updateEventCounters(basic, sliding);

                        if (hr != null && rmssd != null) {
                          pushHrRmssdPoint(hr, rmssd, isStress);
                        }

                        if (ibiList && ibiList.length > 0 && rmssd != null) {
                          pushIbiPoints(ibiList, rmssd);
                        }
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
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("status" to "error", "message" to "Empty payload")
                    )
                    return@post
                }

                val first = rows.first()
                val device = first.device
                val uuid = first.uuid.toUuidOrOrThrow()
                val userId = first.userId?.trim().takeUnless { it.isNullOrBlank() }

                val hrRaw = rows.mapNotNull { it.hr?.toDouble() }
                val hrVals = hrRaw.filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
                val hrMean: Double? =
                    if (hrVals.isNotEmpty()) hrVals.averageOrNull() else lastValidHeartRate(
                        device,
                        uuid
                    )
                val ibiMsList: List<Double> = rows
                    .flatMap { it.ibiMsList ?: emptyList() }

                val ppgVals = rows.mapNotNull { it.ppg?.toDouble() }
                val ppgMean = ppgVals.averageOrNull()

                val (frequencyHz, baselineRows, hrRatio, rmssdRatio) = transaction {
                    val existing = Jobs.select { (Jobs.device eq device) and (Jobs.uuid eq uuid) }
                        .firstOrNull()
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
                    // Fallback: PPG-based if no IBIs
                    val norm = zScoreNormalize(ppgVals)
                    val peaks = findPeaksSimple(norm, minDistance = 50, minHeight = 0.2)
                    val nnFromPeaks = nnIntervalsFromPeaks(peaks, samplingHz = 50)

                    // Choose REAL IBIs if present, otherwise keep old PPG-derived method
                    val nnMs: List<Double> = if (ibiMsList.isNotEmpty()) ibiMsList else nnFromPeaks

                    val (rm, p50) = computeRmssdAndPnn50(nnMs)
                    val rmssd = rm
                    val pnn50 = p50

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
                            baseSlice.mapNotNull { it[Responses.hrMean] }
                                .filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
                                .averageOrNull(),
                            baseSlice.mapNotNull { it[Responses.hrvPnn50] }.averageOrNull(),
                            total.toInt()
                        )
                    }

                    if (totalCount > baselineRows) {
                        val basicHrvDrop =
                            (rmssd != null && baseRmssd != null && rmssd!! * rmssdRatio < baseRmssd)
                        val basicHrRise =
                            (hrMean != null && baseHr != null && hrMean!! > baseHr * hrRatio)
                        val basicPnnDrop =
                            (pnn50 != null && basePnn != null && pnn50!! * PNN50_THRESHOLD_RATIO < basePnn)
                        if (basicHrvDrop && basicHrRise && basicPnnDrop) statusBasic =
                            "basic_warning"
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
                                slice.mapNotNull { it[Responses.hrMean] }
                                    .filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
                                    .averageOrNull(),
                                slice.mapNotNull { it[Responses.hrvPnn50] }.averageOrNull()
                            )
                        }
                        val slideHrvDrop =
                            (rmssd != null && winRmssd != null && rmssd!! * rmssdRatio < winRmssd)
                        val slideHrRise =
                            (hrMean != null && winHr != null && hrMean!! > winHr * hrRatio)
                        val slidePnnDrop =
                            (pnn50 != null && winPnn != null && pnn50!! * PNN50_THRESHOLD_RATIO < winPnn)
                        if (slideHrvDrop && slideHrRise && slidePnnDrop) statusSliding =
                            "sliding_warning"
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

                    val mlScore = mlStressProbability(hrMean, rmssd, pnn50)
                    val mlLabel = mlLabelFromScore(mlScore)

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
                                status_sliding = statusSliding,
                                ml_score = mlScore,
                                ml_label = mlLabel,
                                ibi_ms_list = ibiMsList.ifEmpty { null }
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
                            HRV = hrvJson,
                            ml_score = mlScore,
                            ml_label = mlLabel
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
                                status_sliding = "success",
                                ml_score = null,
                                ml_label = null,
                                ibi_ms_list = null
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
                            HRV = null,
                            ml_score = null,
                            ml_label = null
                        )
                    )
                }
            }

            webSocket("/ws") {
                val device = call.request.queryParameters["device"] ?: run {
                    close(
                        CloseReason(
                            CloseReason.Codes.CANNOT_ACCEPT,
                            "device required"
                        )
                    ); return@webSocket
                }
                val uuid = call.request.queryParameters["uuid"]?.toUuidOrNull() ?: run {
                    close(
                        CloseReason(
                            CloseReason.Codes.CANNOT_ACCEPT,
                            "uuid required"
                        )
                    ); return@webSocket
                }
                val flow = LiveBus.flowFor(device, uuid)
                val job = launch {
                    flow.collect { point ->
                        send(Frame.Text(LiveBus.toJson(point)))
                    }
                }
                try {
                    for (@Suppress("UNUSED_VARIABLE") f in incoming) {
                    }
                } finally {
                    job.cancel()
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

fun String.toUuidOrNull(): UUID? = runCatching { toUuidOrOrThrow() }.getOrNull()
fun String.toUuidOrOrThrow(): UUID = UUID.fromString(this@toUuidOrOrThrow.trim())

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module).start(
        wait = true
    )
}