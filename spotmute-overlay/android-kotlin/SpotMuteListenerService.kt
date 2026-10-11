package com.spotmute

import android.app.Notification
import android.content.ComponentName
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Runs in the background once the user grants "Notification access".
 *
 * Inputs : Spotify's media session (metadata + playback state) and Spotify's notification text.
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private val me by lazy { ComponentName(this, SpotMuteListenerService::class.java) }
    private val sessions by lazy { getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val volume by lazy { VolumeController(this) }

    private var connected = false
    private var controller: MediaController? = null
    private var notifTitle = ""
    private var notifText = ""
    private var adActive = false
    private var restoreTask: Runnable? = null

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
        if (key == Prefs.K_ENABLED || key == Prefs.K_AD_PERCENT || key == Prefs.K_EXTRA_TITLES) {
            evaluate("settings changed")
        }
    }

    // ---- NotificationListenerService lifecycle ---------------------------------------------

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Prefs.register(this, prefsListener)
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
        detach()
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
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
        return MetaSnapshot(
            title = title,
            artist = artist,
            album = str(MediaMetadata.METADATA_KEY_ALBUM),
            mediaId = str(MediaMetadata.METADATA_KEY_MEDIA_ID),
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            canSkipNext = ps?.let { (it.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L },
            playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING,
            notifTitle = notifTitle,
            notifText = notifText,
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
        val playing = snap?.playing == true
        val adNow = verdict.isAd && playing

        if (adNow) {
            cancelRestore()
            if (!adActive) {
                SpotMuteBus.addLog("AD detected (score ${verdict.score}; $reason): ${verdict.signals.joinToString("; ")}")
            }
            if (!volume.isMutedByUs()) SpotMuteBus.addLog("Mute: " + volume.muteForAd())
        } else {
            if (adActive) SpotMuteBus.addLog("Ad no longer detected ($reason)")
            if (volume.isMutedByUs()) {
                // Also covers crash recovery: if we were killed while muted, this puts the volume back.
                scheduleRestore(if (!enabled) "SpotMute switched off" else "ad ended")
            }
        }
        adActive = adNow

        SpotMuteBus.publish(
            Status(
                listenerConnected = true,
                spotifyFound = c != null,
                state = when {
                    !enabled -> "off"
                    c == null -> "idle"
                    adNow -> "ad"
                    verdict.isAd -> "ad-paused"
                    else -> "music"
                },
                playing = playing,
                mutedByUs = volume.isMutedByUs(),
                title = snap?.title.orEmpty(),
                artist = snap?.artist.orEmpty(),
                album = snap?.album.orEmpty(),
                score = verdict.score,
                signals = verdict.signals,
            )
        )
    }

    // ---- Restore helpers ---------------------------------------------------------------------

    private fun scheduleRestore(why: String) {
        if (restoreTask != null) return
        val task = Runnable {
            restoreTask = null
            val out = volume.restoreAfterAd()
            SpotMuteBus.addLog("Restore ($why): ${out.message}")
            evaluate("restored")
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
        if (volume.isMutedByUs()) {
            SpotMuteBus.addLog("Restore ($why): ${volume.restoreAfterAd().message}")
        }
    }
}
