package dev.shalaga44.you_are_okay

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import polar.com.sdk.api.PolarBleApi
import polar.com.sdk.api.PolarBleApiCallbackProvider
import polar.com.sdk.api.PolarBleApiDefaultImpl
import polar.com.sdk.api.errors.PolarInvalidArgument
import polar.com.sdk.api.model.PolarDeviceInfo
import polar.com.sdk.api.model.PolarHrData
import polar.com.sdk.api.model.PolarOhrPPGData
import polar.com.sdk.api.model.PolarOhrPPIData
import polar.com.sdk.api.model.PolarSensorSetting
import java.util.UUID

class PolarController(
    context: Context,
    private val cb: Callbacks
) : PolarBleApiCallbackProvider {

    interface Callbacks {
        fun onConnected(name: String, id: String)
        fun onDisconnected()
        fun onBattery(percent: Int)
        fun onHr(bpm: Int)
        fun onPpg(ppg0: Int?, ppg1: Int?, ppg2: Int?, accX: Float?, accY: Float?, accZ: Float?)
        fun onIbi(ibiMsList: List<Int>)
        fun onMessage(msg: String)
    }

    companion object {
        private const val TAG = "PolarController"
    }

    private val app = context.applicationContext
    private val disposables = CompositeDisposable()
    private var ppgDisposable: Disposable? = null
    private var ppiDisposable: Disposable? = null


    private val api: PolarBleApi =
        PolarBleApiDefaultImpl.defaultImplementation(
            app,
            PolarBleApi.FEATURE_HR or
                    PolarBleApi.FEATURE_DEVICE_INFO or
                    PolarBleApi.FEATURE_BATTERY_INFO or
                    PolarBleApi.FEATURE_POLAR_SENSOR_STREAMING
        )

    private var deviceId: String? = null

    init {
        log("init: setting API callback")
        api.setApiCallback(this)
    }

    fun connect(id: String) {
        deviceId = id
        log("connect() called with id=$id")
        try {
            api.connectToDevice(id)
            log("connect(): connectToDevice invoked")
        } catch (e: PolarInvalidArgument) {
            val msg = "Connect error: ${e.message}"
            log("connect() error: $msg")
            cb.onMessage(msg)
        }
    }

    fun disconnect() {
        val id = deviceId ?: run {
            log("disconnect(): deviceId is null, nothing to disconnect")
            return
        }
        log("disconnect() called with id=$id")
        try {
            api.disconnectFromDevice(id)
            log("disconnect(): disconnectFromDevice invoked")
        } catch (e: PolarInvalidArgument) {
            log("disconnect() PolarInvalidArgument: ${e.message}")
        }
    }

    fun foregroundEntered() {
        log("foregroundEntered()")
        api.foregroundEntered()
    }

    fun backgroundEntered() {
        log("backgroundEntered()")
        api.backgroundEntered()
    }

    fun startStreaming() {
        val id = deviceId ?: run {
            cb.onMessage("No device ID set")
            log("startStreaming(): deviceId is null, abort")
            return
        }

        if (ppgDisposable?.isDisposed == false || ppiDisposable?.isDisposed == false) {
            log("startStreaming(): already streaming, skip")
            return
        }

        // -------- PPG STREAM --------
        log("startStreaming(): requesting PPG settings for id=$id")
        ppgDisposable = api.requestPpgSettings(id)
            .toFlowable()
            .flatMap { settings: PolarSensorSetting ->
                log("startStreaming(): got PPG settings: ${toJson(settings.settings)}")
                log("startStreaming(): starting OhrPPG streaming")
                api.startOhrPPGStreaming(id, settings)
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ frame: PolarOhrPPGData ->
                return@subscribe
                log("PPG frame: ts=${frame.timeStamp}, samples=${frame.samples.size}, type=${frame.type}")
                frame.samples.forEachIndexed { idx, s ->
                    val ch = s.ppgDataSamples
                    val p0 = ch.getOrNull(0)
                    val p1 = ch.getOrNull(1)
                    val p2 = ch.getOrNull(2)
                    Log.d(
                        TAG,
                        "PPG sample[$idx]: " +
                                "ppg0=${s.ppg0}, ppg1=${s.ppg1}, ppg2=${s.ppg2}, " +
                                "ambient=${s.ambient}, ambient2=${s.ambient2}, status=${s.status}, " +
                                "ppgDataSamplesSize=${ch.size}, ch0=$p0, ch1=$p1, ch2=$p2"
                    )
                    cb.onPpg(p0, p1, p2, null, null, null)
                }
            }, { e ->
                Log.e(TAG, "PPG stream error", e)
                cb.onMessage("PPG stream error: ${e.message}")
            }).also { disposables.add(it) }

        // -------- PPI STREAM (IBIs) --------
        log("startStreaming(): starting OhrPPI streaming for id=$id")
        ppiDisposable = api.startOhrPPIStreaming(id)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ ppiData: PolarOhrPPIData ->
                val ibiList = ppiData.samples.map { it.ppi }
                val hr = hrFromIbiList(ibiList)

                log(
                    "PPI frame: ts=${ppiData.timeStamp}, " +
                            "samples=${ppiData.samples.size}, ibiMsList=${toJson(ibiList)}"
                )
                if (ibiList.isNotEmpty()) {
                    if (hr != null) {
                        cb.onHr(hr.toInt())
                    }
                    cb.onIbi(ibiList)
                } else {
                    log("PPI frame: empty IBI list, nothing forwarded")
                }
            }, { e ->
                Log.e(TAG, "PPI stream error", e)
                cb.onMessage("PPI stream error: ${e.message}")
            }).also { disposables.add(it) }

        log("startStreaming(): PPG + PPI disposables added")
    }

    fun stopStreaming() {
        log("stopStreaming() called")

        ppgDisposable?.dispose()
        ppgDisposable = null
        log("stopStreaming(): PPG disposable disposed")

        ppiDisposable?.dispose()
        ppiDisposable = null
        log("stopStreaming(): PPI disposable disposed")

        cb.onMessage("Streams stopped")
    }

    fun shutdown() {
        log("shutdown() called")
        stopStreaming()
        api.shutDown()
        log("shutdown(): api.shutDown() called")
    }

    // ---- PolarBleApiCallbackProvider implementation ----

    override fun blePowerStateChanged(powered: Boolean) {
        log("blePowerStateChanged: powered=$powered")
    }

    override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
        log(
            "deviceConnecting: id=${polarDeviceInfo.deviceId}, " +
                    "name=${polarDeviceInfo.name}, address=${polarDeviceInfo.address}"
        )
        cb.onMessage("Connecting ${polarDeviceInfo.deviceId}")
    }

    override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
        log(
            "deviceConnected: id=${polarDeviceInfo.deviceId}, " +
                    "name=${polarDeviceInfo.name}, address=${polarDeviceInfo.address}"
        )
        val name = polarDeviceInfo.name ?: polarDeviceInfo.deviceId
        cb.onConnected(name, polarDeviceInfo.deviceId)
    }

    override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
        log(
            "deviceDisconnected: id=${polarDeviceInfo.deviceId}, " +
                    "name=${polarDeviceInfo.name}, address=${polarDeviceInfo.address}"
        )
        cb.onDisconnected()
    }

    override fun batteryLevelReceived(identifier: String, level: Int) {
        log("batteryLevelReceived: identifier=$identifier, level=$level")
        cb.onBattery(level)
    }

    override fun hrFeatureReady(identifier: String) {
        log("hrFeatureReady on $identifier")
    }

    override fun hrNotificationReceived(identifier: String, data: PolarHrData) {
//        return
        log(
            "hrNotificationReceived: identifier=$identifier, " +
                    "hr=${data.hr}, contactStatus=${data.contactStatus}, " +
                    "rrsMs=${toJson(data.rrsMs)}, rrAvailable=${data.rrAvailable}"
        )

        cb.onHr(data.hr)
        return
        val rrList = data.rrsMs
        if (!rrList.isNullOrEmpty()) {
            log(
                "hrNotificationReceived: forwarding rrsMs as IBIs, size=${rrList.size}, values=${
                    toJson(
                        rrList
                    )
                }"
            )
            cb.onIbi(rrList)
        } else {
            log("hrNotificationReceived: rrsMs empty, no IBIs forwarded from HR characteristic (PPI is main source)")
        }
    }

    fun hrFromIbiList(ibiMsList: List<Int>): Double? {
        if (ibiMsList.isEmpty()) return null

        val filtered = ibiMsList
            .map { it.toDouble() }
            .filter { it in 300.0..2000.0 }  // 30–200 bpm

        if (filtered.isEmpty()) return null

        val meanIbi = filtered.average()          // ms
        return 60000.0 / meanIbi                 // bpm
    }

    override fun ecgFeatureReady(identifier: String) {
        log("ecgFeatureReady on $identifier")
    }

    override fun accelerometerFeatureReady(identifier: String) {
        log("accelerometerFeatureReady on $identifier")
    }

    override fun ppgFeatureReady(identifier: String) {
        log("ppgFeatureReady on $identifier")
    }

    override fun ppiFeatureReady(identifier: String) {
        log("ppiFeatureReady on $identifier")
    }

    override fun biozFeatureReady(identifier: String) {
        log("biozFeatureReady on $identifier")
    }

    override fun polarFtpFeatureReady(identifier: String) {
        log("polarFtpFeatureReady on $identifier")
    }

    override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
        log("disInformationReceived: identifier=$identifier, uuid=$uuid, value=$value")
    }

    // ---- helpers ----

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    /* private fun toJson(any: Any?): String = try {
         toJson(any)
     } catch (e: Exception) {
         "json_error:${e.message}"
     }*/
}