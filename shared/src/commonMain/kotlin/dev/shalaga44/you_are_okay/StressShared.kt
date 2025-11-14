package dev.shalaga44.you_are_okay

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

// Shared constants (used by server + app)
const val HEART_RATE_THRESHOLD_RATIO: Double = 1.05
const val RMSSD_THRESHOLD_RATIO: Double = 1.09
const val PNN50_THRESHOLD_RATIO: Double = 1.09
const val DEFAULT_BASELINE_FREQUENCY_HZ: Int = 12
const val MIN_HEART_RATE_BPM: Double = 30.0
const val MAX_HEART_RATE_BPM: Double = 220.0

/**
 * Common representation of a sample row that both server and Android can map into.
 * Map server's SampleRow and Android's internal sample objects into this.
 *
 * NOTE: ibiMsList is optional. When present, we treat it as NN intervals in ms
 *       and compute HRV directly from it (preferred over PPG-derived peaks).
 */
data class SampleRowCommon(
    val device: String,
    val timeDate: String? = null,
    val time: String? = null,
    val ppg: Double? = null,
    val hr: Double? = null,
    val uuid: String,
    val userId: String? = null,
    val accX: Double? = null,
    val accY: Double? = null,
    val accZ: Double? = null,
    val ppg0: Double? = null,
    val ppg2: Double? = null,
    val ibiMsList: List<Double>? = null
)

/**
 * Result of one HRV/stress calculation window.
 * This is what you return to the UI (Android) and/or persist (server).
 */
data class StressResult(
    val status: String,
    val statusBasic: String,
    val statusSliding: String,
    val hrMean: Double?,
    val rmssd: Double?,
    val pnn50: Double?,
    val ppgMean: Double?,
    val mlScore: Double?,
    val mlLabel: String?
)

fun zScoreNormalize(values: List<Double>): List<Double> {
    if (values.isEmpty()) return values
    val mean = values.average()
    val sd = sqrt(values.fold(0.0) { acc, x -> acc + (x - mean).pow(2) } / values.size)
    if (sd == 0.0) return values.map { 0.0 }
    return values.map { (it - mean) / sd }
}

fun findPeaksSimple(
    z: List<Double>,
    minDistance: Int = 25,
    minHeight: Double = 0.5
): List<Int> {
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

fun nnIntervalsFromPeaks(peaks: List<Int>, samplingHz: Int): List<Double> {
    if (peaks.size < 2) return emptyList()
    val msPerSample = 1000.0 / samplingHz
    return peaks.zipWithNext().map { (a, b) -> (b - a) * msPerSample }
}

/**
 * nnMs = list of NN intervals (ms) – can be:
 *  - derived from peaks (PPG)
 *  - directly from IBI / PPI (preferred)
 */
fun computeRmssdAndPnn50(nnMs: List<Double>): Pair<Double, Double> {
    if (nnMs.size < 3) return 0.0 to 0.0
    val diff = nnMs.zipWithNext().map { (a, b) -> b - a }
    val rmssd = sqrt(diff.map { it * it }.average())
    val pnn50 = if (diff.isEmpty()) 0.0 else diff.count { abs(it) > 50.0 }.toDouble() * 100.0 / diff.size
    return rmssd to pnn50
}

/**
 * Logistic regression model – same weights as on the server.
 */
fun mlStressProbability(
    hr: Double?,
    rmssd: Double?,
    pnn50: Double?
): Double? {
    if (hr == null || rmssd == null || pnn50 == null) return null

    val w0 = -44.24435565553951      // bias
    val wHr = 0.5014126733435821
    val wRmssd = 0.008600895019301714
    val wPnn50 = -0.03421459783035022

    val z = w0 + wHr * hr + wRmssd * rmssd + wPnn50 * pnn50
    return 1.0 / (1.0 + exp(-z))
}

fun mlLabelFromScore(score: Double?): String? =
    when {
        score == null -> null
        score >= 0.7 -> "ml_stress"
        score <= 0.3 -> "ml_relaxed"
        else -> "ml_uncertain"
    }

fun Iterable<Double>.averageOrNull(): Double? {
    val it = iterator()
    if (!it.hasNext()) return null
    var sum = 0.0
    var count = 0
    while (it.hasNext()) {
        sum += it.next()
        count++
    }
    return sum / count
}

/**
 * Baseline rows = roughly 10 minutes worth of points,
 * where freqHz is "HRV points per second", not raw PPG Hz.
 */
fun baselineRowCountForFrequency(freqHz: Int): Int = (10 * 60) / freqHz

/* -------------------------------------------------------------
 *  Stateful engine (baseline + sliding window logic)
 *  Used on Android for offline stress detection.
 *  Server can also use this.
 *
 *  Now supports IBI-first HRV:
 *   - If any ibiMsList is present in the chunk, HRV is computed
 *     directly from those IBI values.
 *   - If no IBI is present, falls back to PPG → peaks → NN.
 * ------------------------------------------------------------- */

private data class HrvPoint(
    val hrMean: Double?,
    val rmssd: Double?,
    val pnn50: Double?
)

/**
 * - Takes chunks of samples (like each POST / FLUSH window)
 * - Computes hrMean, rmssd, pnn50, ppgMean
 * - Prefers IBI/PPI-based HRV if available
 * - Maintains:
 *    - baseline window (~10 minutes) for "basic" detection
 *    - sliding window for "sliding" detection
 * - Applies the same threshold ratios and ML model
 */
class StressEngine(
    freqHz: Int = DEFAULT_BASELINE_FREQUENCY_HZ,
    private val hrRatio: Double = HEART_RATE_THRESHOLD_RATIO,
    private val rmssdRatio: Double = RMSSD_THRESHOLD_RATIO,
    private val pnn50Ratio: Double = PNN50_THRESHOLD_RATIO
) {
    private val baselineRows: Int = baselineRowCountForFrequency(freqHz)

    private val baselineWindow: MutableList<HrvPoint> = mutableListOf()
    private val slidingWindow: ArrayDeque<HrvPoint> = ArrayDeque()
    private var totalCount: Int = 0
    private var lastValidHr: Double? = null

    fun reset() {
        baselineWindow.clear()
        slidingWindow.clear()
        totalCount = 0
        lastValidHr = null
    }

    /**
     * Process one chunk of rows and return a StressResult.
     *
     * @param rows       Chunk of samples for this window.
     * @param samplingHz Sampling rate of the PPG data in Hz (e.g., 130).
     *                   Only used if we have to derive HRV from PPG.
     */
    fun processChunk(
        rows: List<SampleRowCommon>,
        samplingHz: Int = 130
    ): StressResult {
        // 1) HR from HR characteristic (if present)
        val hrRaw = rows.mapNotNull { it.hr }
        val hrVals = hrRaw.filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
        var hrMean: Double? = when {
            hrVals.isNotEmpty() -> hrVals.average()
            else -> lastValidHr
        }

        // 2) IBI values (ms) from PPI / IBI-driven samples
        val ibiAll: List<Double> = rows
            .flatMap { it.ibiMsList ?: emptyList() }
            .filter { it.isFinite() && it > 250.0 && it < 2000.0 } // basic sanity bounds

        // 3) If HR not available but we have IBI, derive mean HR from IBI
        if (hrMean == null && ibiAll.isNotEmpty()) {
            val avgIbi = ibiAll.average()
            if (avgIbi > 0.0) {
                val hrFromIbi = 60000.0 / avgIbi
                if (hrFromIbi in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM) {
                    hrMean = hrFromIbi
                }
            }
        }

        // Track last valid HR
        if (hrMean != null && hrMean in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM) {
            lastValidHr = hrMean
        }

        // 4) PPG
        val ppgVals = rows.mapNotNull { it.ppg }
        val ppgMean: Double? = ppgVals.averageOrNull()

        // 5) HRV (RMSSD, pNN50)
        var rmssd: Double? = null
        var pnn50: Double? = null

        if (ibiAll.isNotEmpty()) {
            // PPI / IBI is the primary HRV source
            val (rm, p50) = computeRmssdAndPnn50(ibiAll)
            rmssd = rm
            pnn50 = p50
        } else if (ppgVals.isNotEmpty()) {
            // Fallback: derive NN intervals from PPG peaks
            val norm = zScoreNormalize(ppgVals)
            val peaks = findPeaksSimple(norm, minDistance = 50, minHeight = 0.2)
            val nn = nnIntervalsFromPeaks(peaks, samplingHz)
            val (rm, p50) = computeRmssdAndPnn50(nn)
            rmssd = rm
            pnn50 = p50
        }

        val point = HrvPoint(hrMean = hrMean, rmssd = rmssd, pnn50 = pnn50)
        totalCount++

        // 6) Baseline window (first ~10 minutes)
        if (baselineWindow.size < baselineRows) {
            baselineWindow += point
        }

        // 7) Sliding window (always last baselineRows points)
        slidingWindow += point
        if (slidingWindow.size > baselineRows) {
            slidingWindow.removeFirst()
        }

        val baseRmssd: Double? =
            baselineWindow.mapNotNull { it.rmssd }.averageOrNull()

        val baseHr: Double? =
            baselineWindow.mapNotNull { it.hrMean }
                .filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
                .averageOrNull()

        val basePnn: Double? =
            baselineWindow.mapNotNull { it.pnn50 }.averageOrNull()

        // 8) Basic (baseline) detection
        var statusBasic = "success"
        if (baselineWindow.size == baselineRows) {
            val basicHrvDrop = rmssd != null && baseRmssd != null && rmssd * rmssdRatio < baseRmssd
            val basicHrRise  = hrMean != null && baseHr != null && hrMean > baseHr * hrRatio
            val basicPnnDrop = pnn50 != null && basePnn != null && pnn50 * pnn50Ratio < basePnn
            if (basicHrvDrop && basicHrRise && basicPnnDrop) {
                statusBasic = "basic_warning"
            }
        }

        // 9) Sliding window detection
        var statusSliding = "success"
        if (slidingWindow.size == baselineRows) {
            val winRmssd: Double? =
                slidingWindow.mapNotNull { it.rmssd }.averageOrNull()
            val winHr: Double? =
                slidingWindow.mapNotNull { it.hrMean }
                    .filter { it in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM }
                    .averageOrNull()
            val winPnn: Double? =
                slidingWindow.mapNotNull { it.pnn50 }.averageOrNull()

            val slideHrvDrop = rmssd != null && winRmssd != null && rmssd * rmssdRatio < winRmssd
            val slideHrRise  = hrMean != null && winHr != null && hrMean > winHr * hrRatio
            val slidePnnDrop = pnn50 != null && winPnn != null && pnn50 * pnn50Ratio < winPnn
            if (slideHrvDrop && slideHrRise && slidePnnDrop) {
                statusSliding = "sliding_warning"
            }
        }

        val status = if (
            statusBasic == "basic_warning" ||
            statusSliding == "sliding_warning"
        ) {
            "warning"
        } else {
            "success"
        }

        val mlScore = mlStressProbability(hrMean, rmssd, pnn50)
        val mlLabel = mlLabelFromScore(mlScore)

        return StressResult(
            status = status,
            statusBasic = statusBasic,
            statusSliding = statusSliding,
            hrMean = hrMean,
            rmssd = rmssd,
            pnn50 = pnn50,
            ppgMean = ppgMean,
            mlScore = mlScore,
            mlLabel = mlLabel
        )
    }
}