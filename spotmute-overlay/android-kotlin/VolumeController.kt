package com.spotmute

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

enum class RestoreResult { NOT_MUTED, RESTORED, USER_CHANGED }

data class RestoreOutcome(val result: RestoreResult, val message: String)

/**
 * Saves the original music-stream volume, lowers it for an ad, and puts it back afterwards.
 *
 * NOTE: this changes the system-wide STREAM_MUSIC volume, so any other app playing
 * music/video at the same moment is affected too. That is inherent to this approach.
 */
class VolumeController(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val stream = AudioManager.STREAM_MUSIC

    /** True when we have lowered the volume and still owe the user a restore. */
    fun isMutedByUs(): Boolean = Prefs.savedVolume(ctx) >= 0

    /** Lowers the volume for an ad. Safe to call repeatedly. Returns a log line. */
    fun muteForAd(): String {
        if (isMutedByUs()) return "already muted by SpotMute"
        val current = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        val target = (max * Prefs.adVolumePercent(ctx) / 100f).roundToInt().coerceIn(0, max)
        if (current <= target) return "volume already $current/$max, left alone"
        Prefs.saveMuteState(ctx, saved = current, mutedTo = target) // persist BEFORE changing
        am.setStreamVolume(stream, target, 0)
        return "volume $current -> $target (of $max), original saved"
    }

    /** Restores the saved volume, unless the user touched the volume while muted. */
    fun restoreAfterAd(): RestoreOutcome {
        val saved = Prefs.savedVolume(ctx)
        if (saved < 0) return RestoreOutcome(RestoreResult.NOT_MUTED, "nothing to restore")
        val expected = Prefs.mutedTo(ctx)
        val current = am.getStreamVolume(stream)
        Prefs.clearMuteState(ctx)
        if (current != expected) {
            return RestoreOutcome(
                RestoreResult.USER_CHANGED,
                "you changed the volume to $current during the ad, so it was not overwritten"
            )
        }
        am.setStreamVolume(stream, saved, 0)
        return RestoreOutcome(RestoreResult.RESTORED, "volume restored to $saved")
    }
}
