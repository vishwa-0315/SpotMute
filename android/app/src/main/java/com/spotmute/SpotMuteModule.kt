package com.spotmute

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.content.FileProvider
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.File
import java.util.UUID

/** JS-facing API: `NativeModules.SpotMute`. Emits the event "SpotMuteStatus". */
class SpotMuteModule(private val rc: ReactApplicationContext) :
    ReactContextBaseJavaModule(rc), ActivityEventListener {

    companion object {
        private const val REQUEST_PICK_AUDIO = 9021
    }

    private var pickFilesPromise: Promise? = null

    override fun getName() = "SpotMute"

    override fun initialize() {
        super.initialize()
        rc.addActivityEventListener(this)
        SpotMuteBus.listener = { emitStatus() }
    }

    override fun invalidate() {
        rc.removeActivityEventListener(this)
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
            putInt("adsMutedCount", Prefs.adsMutedCount(rc))
            putDouble("timeSavedSeconds", Prefs.timeSavedSec(rc).toDouble())
        }
    }

    @ReactMethod
    fun getStatus(promise: Promise) = promise.resolve(statusMap())

    @ReactMethod
    fun resetStats(promise: Promise) {
        Prefs.resetStats(rc)
        emitStatus()
        promise.resolve("Stats reset")
    }

    @ReactMethod
    fun getConfig(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putBoolean("enabled", Prefs.isEnabled(rc))
            putInt("adVolumePercent", Prefs.adVolumePercent(rc))
            putString("extraTitles", Prefs.extraTitles(rc))
            putBoolean("substituteMusicEnabled", Prefs.isSubstituteMusicEnabled(rc))
            putBoolean("muteSystemVolume", Prefs.isMuteSystemVolume(rc))
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

    @ReactMethod
    fun setSubstituteMusicEnabled(value: Boolean) = Prefs.setSubstituteMusicEnabled(rc, value)

    @ReactMethod
    fun setMuteSystemVolume(value: Boolean) = Prefs.setMuteSystemVolume(rc, value)

    private fun tracksToWritableArray(tracks: List<SubstituteTrack>): WritableArray {
        val arr = Arguments.createArray()
        for (t in tracks) {
            val map = Arguments.createMap().apply {
                putString("id", t.id)
                putString("name", t.name)
                putString("path", t.path)
                putDouble("sizeBytes", t.sizeBytes.toDouble())
            }
            arr.pushMap(map)
        }
        return arr
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                rc.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1 && cursor.moveToFirst()) {
                        return cursor.getString(index)
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }

    override fun onNewIntent(intent: Intent?) {}

    override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK_AUDIO) return
        val promise = pickFilesPromise ?: return
        pickFilesPromise = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            promise.resolve(tracksToWritableArray(Prefs.getSubstituteTracks(rc)))
            return
        }

        try {
            val dir = File(rc.filesDir, "substitute_tracks")
            if (!dir.exists()) dir.mkdirs()

            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }

            for (uri in uris) {
                val name = getFileName(uri) ?: "track_${System.currentTimeMillis()}.mp3"
                val dest = File(dir, "${System.currentTimeMillis()}_$name")
                rc.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Prefs.addSubstituteTrack(
                    rc,
                    SubstituteTrack(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        path = dest.absolutePath,
                        sizeBytes = dest.length()
                    )
                )
            }
            promise.resolve(tracksToWritableArray(Prefs.getSubstituteTracks(rc)))
        } catch (e: Exception) {
            promise.reject("ERR_PICK_AUDIO", e.message)
        }
    }

    @ReactMethod
    fun pickAudioFiles(promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("ERR_NO_ACTIVITY", "Current activity is null")
            return
        }
        pickFilesPromise = promise
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            activity.startActivityForResult(intent, REQUEST_PICK_AUDIO)
        } catch (e: Exception) {
            pickFilesPromise = null
            promise.reject("ERR_LAUNCH_PICKER", e.message)
        }
    }

    @ReactMethod
    fun getSubstituteTracks(promise: Promise) {
        promise.resolve(tracksToWritableArray(Prefs.getSubstituteTracks(rc)))
    }

    @ReactMethod
    fun removeSubstituteTrack(id: String, promise: Promise) {
        val updated = Prefs.removeSubstituteTrack(rc, id)
        promise.resolve(tracksToWritableArray(updated))
    }

    @ReactMethod
    fun clearSubstituteTracks(promise: Promise) {
        Prefs.clearSubstituteTracks(rc)
        promise.resolve(Arguments.createArray())
    }

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

    /** Nudge Android to reconnect the listener and ensure foreground service is running. */
    @ReactMethod
    fun requestRebind() {
        try {
            val intent = Intent(rc, SpotMuteListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                rc.startForegroundService(intent)
            } else {
                rc.startService(intent)
            }
        } catch (_: Exception) {}
        NotificationListenerService.requestRebind(ComponentName(rc, SpotMuteListenerService::class.java))
    }

    @ReactMethod
    fun isBatteryOptimizationIgnored(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = rc.getSystemService(Context.POWER_SERVICE) as PowerManager
                promise.resolve(pm.isIgnoringBatteryOptimizations(rc.packageName))
            } else {
                promise.resolve(true)
            }
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun requestIgnoreBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${rc.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                rc.startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                rc.startActivity(intent)
            } catch (_: Exception) {}
        }
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
