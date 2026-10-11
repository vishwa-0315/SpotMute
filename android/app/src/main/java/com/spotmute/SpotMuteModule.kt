package com.spotmute

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.File

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

    @ReactMethod
    fun getAppVersion(promise: Promise) {
        try {
            val pInfo = rc.packageManager.getPackageInfo(rc.packageName, 0)
            promise.resolve(pInfo.versionName ?: "1.0.0")
        } catch (e: Exception) {
            promise.resolve("1.0.0")
        }
    }

    @ReactMethod
    fun installApk(downloadUrl: String, promise: Promise) {
        try {
            val dm = rc.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(downloadUrl)
            val fileName = "spotmute-update.apk"
            val destFile = File(rc.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (destFile.exists()) destFile.delete()

            val request = DownloadManager.Request(uri).apply {
                setTitle("SpotMute Update")
                setDescription("Downloading latest release...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(rc, Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val downloadId = dm.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == downloadId) {
                        try {
                            rc.unregisterReceiver(this)
                            if (destFile.exists()) {
                                val apkUri = FileProvider.getUriForFile(
                                    rc,
                                    "${rc.packageName}.fileprovider",
                                    destFile
                                )
                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                rc.startActivity(installIntent)
                            }
                        } catch (e: Exception) {
                            SpotMuteBus.addLog("Install trigger error: ${e.message}")
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                rc.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
            } else {
                rc.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            promise.resolve("Download started")
        } catch (e: Exception) {
            promise.reject("ERR_DOWNLOAD", e.message)
        }
    }

    // Required by NativeEventEmitter on the JS side.
    @ReactMethod
    fun addListener(eventName: String) {
    }

    @ReactMethod
    fun removeListeners(count: Int) {
    }
}
