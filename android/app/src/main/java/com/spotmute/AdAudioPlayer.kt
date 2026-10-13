package com.spotmute

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Manages playback of user-uploaded songs during Spotify ad breaks.
 * Plays while an ad is running, and cuts off immediately when the ad ends.
 */
class AdAudioPlayer(private val ctx: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var currentTrackIndex = 0

    fun startPlaying() {
        val tracks = Prefs.getSubstituteTracks(ctx)
        if (tracks.isEmpty()) {
            SpotMuteBus.addLog("No substitute tracks available to play")
            return
        }

        // If media player already exists and paused, resume seamlessly
        mediaPlayer?.let { mp ->
            try {
                if (!mp.isPlaying) {
                    applyVolume(mp)
                    mp.start()
                    SpotMuteBus.addLog("Resumed substitute track: ${tracks[currentTrackIndex % tracks.size].name}")
                    return
                }
            } catch (_: Exception) {
                release()
            }
        }

        playTrackAtIndex(currentTrackIndex.coerceIn(0, tracks.size - 1), tracks)
    }

    private fun playTrackAtIndex(index: Int, tracks: List<SubstituteTrack>) {
        if (tracks.isEmpty()) return
        val safeIndex = index % tracks.size
        currentTrackIndex = safeIndex
        val track = tracks[safeIndex]
        val file = File(track.path)
        if (!file.exists()) {
            SpotMuteBus.addLog("Track file missing on disk: ${track.name}")
            return
        }

        try {
            release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    // When one track finishes during a longer ad, cycle to next track in list
                    val currentTracks = Prefs.getSubstituteTracks(ctx)
                    if (currentTracks.isNotEmpty()) {
                        playTrackAtIndex((currentTrackIndex + 1) % currentTracks.size, currentTracks)
                    }
                }
                setOnPreparedListener { mp ->
                    try {
                        applyVolume(mp)
                        mp.start()
                        SpotMuteBus.addLog("Playing substitute track: ${track.name}")
                    } catch (e: Exception) {
                        SpotMuteBus.addLog("Failed starting track: ${e.message}")
                    }
                }
                setOnErrorListener { _, what, extra ->
                    SpotMuteBus.addLog("Substitute player error: what=$what extra=$extra")
                    release()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            SpotMuteBus.addLog("Failed to prepare substitute track: ${e.message}")
            release()
        }
    }

    /**
     * Instantly cuts off music playback when the Spotify ad finishes or stops.
     */
    fun cutOff() {
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    SpotMuteBus.addLog("Substitute song cut off (Spotify ad ended)")
                }
            } catch (_: Exception) {
                release()
            }
        }
    }

    private fun applyVolume(mp: MediaPlayer) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val savedVol = Prefs.savedVolume(ctx)
        val maxMusic = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val currentMusic = if (savedVol > 0) savedVol else am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val ratio = if (maxMusic > 0) (currentMusic.toFloat() / maxMusic.toFloat()).coerceIn(0.2f, 1.0f) else 0.8f

        val maxAlarm = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
        val currentAlarm = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
        if (currentAlarm == 0 && maxAlarm > 0) {
            try {
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, (maxAlarm * 0.7f).toInt().coerceAtLeast(1), 0)
            } catch (_: Exception) {}
        }
        mp.setVolume(ratio, ratio)
    }

    fun release() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
