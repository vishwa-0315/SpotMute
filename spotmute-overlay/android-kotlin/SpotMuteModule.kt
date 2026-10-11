package com.spotmute

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/** JS-facing API: `NativeModules.SpotMute`. Emits the event "SpotMuteStatus". */
class SpotMuteModule(private val rc: ReactApplicationContext) : ReactContextBaseJavaModule(rc) {

    override fun getName() = "SpotMute"

    override fun initialize() {
        super.initialize()
        SpotMuteBus.listener = { emitStatus() }
    }

    override fun invalidate() {
        SpotMuteBus.listener = null
        super.invalidate()
    }

    private fun emitStatus() {
        if (!rc.hasActiveReactInstance()) return
        rc.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("SpotMuteStatus", statusMap())
    }

    /** True when the user has switched on Notification access for this app. */
    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(rc.contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == rc.packageName }
    }

    private fun statusMap(): WritableMap {
        val s = SpotMuteBus.status
        return Arguments.createMap().apply {
            putBoolean("listenerEnabled", listenerEnabled())
            putBoolean("listenerConnected", s.listenerConnected)
            putBoolean("spotifyFound", s.spotifyFound)
            putString("state", s.state)
            putBoolean("playing", s.playing)
            putBoolean("mutedByUs", s.mutedByUs)
            putString("title", s.title)
            putString("artist", s.artist)
            putString("album", s.album)
            putInt("score", s.score)
            putArray("signals", Arguments.fromList(s.signals))
        }
    }

    @ReactMethod
    fun getStatus(promise: Promise) = promise.resolve(statusMap())

    @ReactMethod
    fun getConfig(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putBoolean("enabled", Prefs.isEnabled(rc))
            putInt("adVolumePercent", Prefs.adVolumePercent(rc))
            putString("extraTitles", Prefs.extraTitles(rc))
        })
    }

    @ReactMethod
    fun getLog(promise: Promise) = promise.resolve(Arguments.fromList(SpotMuteBus.logLines()))

    @ReactMethod
    fun setEnabled(value: Boolean) = Prefs.setEnabled(rc, value)

    @ReactMethod
    fun setAdVolumePercent(value: Int) = Prefs.setAdVolumePercent(rc, value)

    @ReactMethod
    fun setExtraTitles(value: String) = Prefs.setExtraTitles(rc, value)

    /** Panic button: put the volume back right now, whatever the service thinks. */
    @ReactMethod
    fun restoreVolumeNow(promise: Promise) {
        val out = VolumeController(rc).restoreAfterAd()
        SpotMuteBus.addLog("Manual restore: ${out.message}")
        promise.resolve(out.message)
    }

    @ReactMethod
    fun openListenerSettings() {
        rc.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** App info page - needed on Android 13+ to allow "restricted settings" for sideloaded apps. */
    @ReactMethod
    fun openAppInfo() {
        rc.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", rc.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Nudge Android to reconnect the listener if access is granted but the service is not connected. */
    @ReactMethod
    fun requestRebind() {
        NotificationListenerService.requestRebind(ComponentName(rc, SpotMuteListenerService::class.java))
    }

    // Required by NativeEventEmitter on the JS side.
    @ReactMethod
    fun addListener(eventName: String) {
    }

    @ReactMethod
    fun removeListeners(count: Int) {
    }
}
