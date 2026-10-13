package com.spotmute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Runs in the background once the user grants "Notification access".
 * Also runs as a persistent Foreground Service to prevent Android from freezing or killing it when minimized or swiped away.
 *
 * Inputs : Spotify's media session (metadata + playback state), Spotify broadcasts, and Spotify's notification text.
 * Output : lowers the music volume while the evidence says an ad is playing, restores it after.
 *
 * Everything runs on the main thread (callbacks are registered with a main-looper Handler),
 * so no locking is needed for the fields below.
 */
class SpotMuteListenerService : NotificationListenerService() {

    companion object {
        val SPOTIFY_PACKAGES = setOf("com.spotify.music", "com.spotify.lite")

        /** Wait this long before restoring, so a brief metadata flicker does not un-mute mid-ad. */
        private const val RESTORE_DELAY_MS = 300L

        private const val CHANNEL_ID = "spotmute_fg_service"
        private const val NOTIFICATION_ID = 2026
    }

    private val handler = Handler(Looper.getMainLooper())
    private val me by lazy { ComponentName(this, SpotMuteListenerService::class.java) }
    private val sessions by lazy { getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val volume by lazy { VolumeController(this) }
    private val audioPlayer by lazy { AdAudioPlayer(this) }

    private var connected = false
    private var controller: MediaController? = null
    private var notifTitle = ""
    private var notifText = ""
    private var adActive = false
    private var adMutedStartTime = 0L
    private var restoreTask: Runnable? = null

    // Safety watchdog: periodically poll while muted to ensure volume is restored even if callbacks are missed
    private val muteWatchdog = object : Runnable {
        override fun run() {
            if (adActive || volume.isMutedByUs()) {
                val elapsed = System.currentTimeMillis() - adMutedStartTime
                // Safety timeout: no Spotify audio ad lasts longer than 60 seconds
                if (adMutedStartTime > 0 && elapsed > 60_000L) {
                    SpotMuteBus.addLog("Safety timeout: muted for ${elapsed / 1000}s, auto-restoring volume")
                    restoreNow("safety timeout")
                    return
                }
                evaluate("watchdog poll")
                handler.postDelayed(this, 1000L)
            }
        }
    }

    // Direct Spotify BroadcastReceiver for instant track change detection
    private val spotifyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action ?: return
            when (action) {
                "com.spotify.music.metadatachanged",
                "com.spotify.music.playbackstatechanged",
                "com.spotify.music.queuechanged" -> {
                    evaluate("Spotify broadcast ($action)")
                }
            }
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate("metadata changed")
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate("playback state changed")
        override fun onSessionDestroyed() {
            detach()
            evaluate("Spotify session ended")
        }
    }

    // Re-evaluate when the user changes a setting in the UI (not on our own mute-state writes).
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.K_ENABLED || key == Prefs.K_AD_PERCENT || key == Prefs.K_EXTRA_TITLES ||
            key == Prefs.K_SUBSTITUTE_MUSIC || key == Prefs.K_MUTE_SYSTEM_VOLUME) {
            if (!Prefs.isSubstituteMusicEnabled(this)) {
                audioPlayer.cutOff()
            }
            evaluate("settings changed")
        }
    }

    // ---- Lifecycle & Foreground Service management -----------------------------------------

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceCompat()
        evaluate("service start command")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        SpotMuteBus.addLog("SpotMute task swiped away/closed, keeping background listener active")
        startForegroundServiceCompat()
        NotificationListenerService.requestRebind(me)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SpotMute Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SpotMute running in background to detect and mute Spotify ads"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpotMute")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startForegroundServiceCompat() {
        createNotificationChannel()
        val text = if (adActive) "Ad detected — Volume muted" else "Active — Monitoring Spotify"
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            SpotMuteBus.addLog("Foreground service start: ${e.message}")
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    // ---- NotificationListenerService lifecycle ---------------------------------------------

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        startForegroundServiceCompat()
        Prefs.register(this, prefsListener)

        try {
            val filter = IntentFilter().apply {
                addAction("com.spotify.music.metadatachanged")
                addAction("com.spotify.music.playbackstatechanged")
                addAction("com.spotify.music.queuechanged")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(spotifyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(spotifyReceiver, filter)
            }
        } catch (e: Exception) {
            SpotMuteBus.addLog("Spotify broadcast registration failed: ${e.message}")
        }

        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
            attach(sessions.getActiveSessions(me))
        } catch (e: SecurityException) {
            SpotMuteBus.addLog("Media session access denied: ${e.message}")
        }
        try {
            activeNotifications
                ?.firstOrNull { it.packageName in SPOTIFY_PACKAGES }
                ?.let { readNotification(it) }
        } catch (_: Exception) {
            // best effort only
        }
        SpotMuteBus.addLog("Listener connected")
        evaluate("listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        restoreNow("listener disconnected")
        shutdownWatchers()
        SpotMuteBus.publish(Status(listenerConnected = false))
        updateNotification("Listener disconnected — tap to check permissions")
        // Ask the system to reconnect us (API 24+, which React Native already requires).
        NotificationListenerService.requestRebind(me)
    }

    override fun onDestroy() {
        restoreNow("service destroyed")
        shutdownWatchers()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in SPOTIFY_PACKAGES) return
        readNotification(sbn)
        evaluate("Spotify notification updated")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in SPOTIFY_PACKAGES) return
        notifTitle = ""
        notifText = ""
        evaluate("Spotify notification removed")
    }

    // ---- Session handling --------------------------------------------------------------------

    private fun attach(list: List<MediaController>) {
        val spotify = list.firstOrNull { it.packageName in SPOTIFY_PACKAGES }
        if (spotify?.sessionToken == controller?.sessionToken) return
        detach()
        controller = spotify
        spotify?.registerCallback(controllerCallback, handler)
        SpotMuteBus.addLog(if (spotify != null) "Attached to Spotify media session" else "Spotify session gone")
        evaluate("session changed")
    }

    private fun detach() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun shutdownWatchers() {
        cancelRestore()
        audioPlayer.release()
        handler.removeCallbacks(muteWatchdog)
        adMutedStartTime = 0L
        try {
            unregisterReceiver(spotifyReceiver)
        } catch (_: Exception) {}
        detach()
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {}
        Prefs.unregister(this, prefsListener)
    }

    private fun readNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        notifText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }

    private fun snapshot(c: MediaController): MetaSnapshot {
        val m = c.metadata
        val ps = c.playbackState
        fun str(key: String): String = m?.getString(key).orEmpty()

        val title = str(MediaMetadata.METADATA_KEY_TITLE).ifEmpty { str(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
        val artist = str(MediaMetadata.METADATA_KEY_ARTIST).ifEmpty { str(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) }
        val state = ps?.state

        // If metadata has clearly moved to a new track, don't pass stale notification text
        val useNotif = notifTitle.isEmpty() || notifTitle.equals(title, ignoreCase = true) || title.equals("spotify", ignoreCase = true)
        val effectiveNotifTitle = if (useNotif) notifTitle else ""
        val effectiveNotifText = if (useNotif) notifText else ""

        return MetaSnapshot(
            title = title,
            artist = artist,
            album = str(MediaMetadata.METADATA_KEY_ALBUM),
            mediaId = str(MediaMetadata.METADATA_KEY_MEDIA_ID),
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            canSkipNext = ps?.let { (it.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L },
            playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING,
            notifTitle = effectiveNotifTitle,
            notifText = effectiveNotifText,
        )
    }

    // ---- Core decision -----------------------------------------------------------------------

    private fun evaluate(reason: String) {
        if (!connected) return
        val c = controller
        val enabled = Prefs.isEnabled(this)

        var snap: MetaSnapshot? = null
        var verdict = Verdict(false, 0, emptyList())
        if (c != null && enabled) {
            snap = snapshot(c)
            verdict = AdDetector.evaluate(snap, AdDetector.parseExtra(Prefs.extraTitles(this)))
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val playing = snap?.playing == true || audioManager.isMusicActive
        val adNow = verdict.isAd

        if (adNow) {
            cancelRestore()
            if (!adActive) {
                adMutedStartTime = System.currentTimeMillis()
                SpotMuteBus.addLog("AD detected (score ${verdict.score}; $reason): ${verdict.signals.joinToString("; ")}")
                updateNotification("Ad detected — Volume muted")
                handler.removeCallbacks(muteWatchdog)
                handler.postDelayed(muteWatchdog, 1000L)
                if (Prefs.isSubstituteMusicEnabled(this)) {
                    audioPlayer.startPlaying()
                }
            }
            val muteMsg = volume.muteForAd()
            if (muteMsg.isNotEmpty()) {
                SpotMuteBus.addLog("Mute: $muteMsg")
            }
        } else {
            if (adActive) {
                SpotMuteBus.addLog("Ad no longer detected ($reason)")
                updateNotification(if (!enabled) "SpotMute is paused" else "Active — Monitoring Spotify")
                handler.removeCallbacks(muteWatchdog)
                audioPlayer.cutOff()
                if (adMutedStartTime > 0L) {
                    val elapsedSec = ((System.currentTimeMillis() - adMutedStartTime) / 1000L).coerceIn(1L, 120L)
                    Prefs.incrementAdStats(this, elapsedSec)
                    SpotMuteBus.addLog("Ad silenced for ${elapsedSec}s (total ads: ${Prefs.adsMutedCount(this)})")
                    adMutedStartTime = 0L
                }
            }
            if (volume.isMutedByUs()) {
                scheduleRestore(if (!enabled) "SpotMute switched off" else "ad ended")
            }
        }
        adActive = adNow

        publishStatus(c, enabled, snap, verdict, playing, adNow)
    }

    private fun publishStatus(
        c: MediaController?,
        enabled: Boolean,
        snap: MetaSnapshot?,
        verdict: Verdict,
        playing: Boolean,
        adNow: Boolean
    ) {
        SpotMuteBus.publish(
            Status(
                listenerConnected = true,
                spotifyFound = c != null,
                state = when {
                    !enabled -> "off"
                    c == null -> "idle"
                    adNow -> "ad"
                    else -> "music"
                },
                playing = playing,
                mutedByUs = volume.isMutedByUs(),
                title = snap?.title.orEmpty(),
                artist = snap?.artist.orEmpty(),
                album = snap?.album.orEmpty(),
                score = verdict.score,
                signals = verdict.signals,
                adsMutedCount = Prefs.adsMutedCount(this),
                timeSavedSeconds = Prefs.timeSavedSec(this),
            )
        )
    }

    // ---- Restore helpers ---------------------------------------------------------------------

    private fun scheduleRestore(why: String) {
        if (restoreTask != null) return
        val task = Runnable {
            restoreTask = null
            audioPlayer.cutOff()
            val out = volume.restoreAfterAd()
            SpotMuteBus.addLog("Restore ($why): ${out.message}")
            if (adMutedStartTime > 0L) {
                val elapsedSec = ((System.currentTimeMillis() - adMutedStartTime) / 1000L).coerceIn(1L, 120L)
                Prefs.incrementAdStats(this, elapsedSec)
            }
            adActive = false
            adMutedStartTime = 0L
            handler.removeCallbacks(muteWatchdog)

            val c = controller
            val enabled = Prefs.isEnabled(this)
            val snap = c?.let { snapshot(it) }
            val verdict = if (c != null && enabled && snap != null) {
                AdDetector.evaluate(snap, AdDetector.parseExtra(Prefs.extraTitles(this)))
            } else {
                Verdict(false, 0, emptyList())
            }
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val playing = snap?.playing == true || audioManager.isMusicActive
            publishStatus(c, enabled, snap, verdict, playing, false)
        }
        restoreTask = task
        handler.postDelayed(task, RESTORE_DELAY_MS)
    }

    private fun cancelRestore() {
        restoreTask?.let { handler.removeCallbacks(it) }
        restoreTask = null
    }

    private fun restoreNow(why: String) {
        cancelRestore()
        audioPlayer.cutOff()
        handler.removeCallbacks(muteWatchdog)
        adMutedStartTime = 0L
        adActive = false
        if (volume.isMutedByUs()) {
            SpotMuteBus.addLog("Restore ($why): ${volume.restoreAfterAd().message}")
        }
        val c = controller
        val enabled = Prefs.isEnabled(this)
        val snap = c?.let { snapshot(it) }
        val verdict = if (c != null && enabled && snap != null) {
            AdDetector.evaluate(snap, AdDetector.parseExtra(Prefs.extraTitles(this)))
        } else {
            Verdict(false, 0, emptyList())
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val playing = snap?.playing == true || audioManager.isMusicActive
        publishStatus(c, enabled, snap, verdict, playing, false)
    }
}
