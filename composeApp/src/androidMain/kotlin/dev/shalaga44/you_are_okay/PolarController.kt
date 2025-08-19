
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
        fun onMessage(msg: String)
    }

    private val app = context.applicationContext
    private val disposables = CompositeDisposable()
    private var ppgDisposable: Disposable? = null

    /**
     * Feature bitmask (from BDBleApiImpl):
     * 1=HR, 2=DIS, 4=BATTERY, 8=PMD(sensor streaming), 16=PSFTP
     */
    private val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(app, 1 or 2 or 4 or 8 or 16)

    private var deviceId: String? = null

    init {
        api.setApiCallback(this)
    }

    fun connect(id: String) {
        deviceId = id
        try {
            api.connectToDevice(id)
        } catch (e: PolarInvalidArgument) {
            cb.onMessage("Connect error: ${e.message}")
        }
    }

    fun disconnect() {
        val id = deviceId ?: return
        try {
            api.disconnectFromDevice(id)
        } catch (_: PolarInvalidArgument) {}
    }

    fun foregroundEntered() = api.foregroundEntered()
    fun backgroundEntered() = api.backgroundEntered()

    fun startStreaming() {
        val id = deviceId ?: run { cb.onMessage("No device ID set"); return }
        if (ppgDisposable?.isDisposed == false) return

        ppgDisposable = api.requestPpgSettings(id)
            .toFlowable()
            .flatMap { settings: PolarSensorSetting -> api.startOhrPPGStreaming(id, settings) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ frame: PolarOhrPPGData ->

                frame.samples.forEach { s ->

                    val ch = s.ppgDataSamples
                    val p0 = ch.getOrNull(0)
                    val p1 = ch.getOrNull(1)
                    val p2 = ch.getOrNull(2)
                    cb.onPpg(p0, p1, p2, null, null, null)
                }
            }, { e ->
                cb.onMessage("PPG stream error: ${e.message}")
            })
        ppgDisposable?.let { disposables.add(it) }
    }

    fun stopStreaming() {
        ppgDisposable?.dispose()
        ppgDisposable = null
        cb.onMessage("Streams stopped")
    }

    fun shutdown() {
        stopStreaming()
        api.shutDown()
    }



    override fun blePowerStateChanged(powered: Boolean) {
        log("BLE power: $powered")
    }

    override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
        cb.onMessage("Connecting ${polarDeviceInfo.deviceId}")
    }

    override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
        val name = polarDeviceInfo.name ?: polarDeviceInfo.deviceId
        cb.onConnected(name, polarDeviceInfo.deviceId)
    }

    override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
        cb.onDisconnected()
    }

    override fun batteryLevelReceived(identifier: String, level: Int) {
        cb.onBattery(level)
    }

    override fun hrFeatureReady(identifier: String) {
        log("HR feature ready on $identifier")
    }

    override fun hrNotificationReceived(identifier: String, data: PolarHrData) {
        cb.onHr(data.hr)
    }

    override fun ecgFeatureReady(identifier: String) {}
    override fun accelerometerFeatureReady(identifier: String) {}
    override fun ppgFeatureReady(identifier: String) {}
    override fun ppiFeatureReady(identifier: String) {}
    override fun biozFeatureReady(identifier: String) {}
    override fun polarFtpFeatureReady(identifier: String) {}
    override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {}

    private fun log(msg: String) = Log.d("PolarController", msg)
}