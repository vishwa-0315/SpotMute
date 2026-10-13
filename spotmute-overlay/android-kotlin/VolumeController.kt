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
        if (!Prefs.isMuteSystemVolume(ctx)) {
            return "per-app volume mode: master volume untouched"
        }
        val current = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        val target = (max * Prefs.adVolumePercent(ctx) / 100f).roundToInt().coerceIn(0, max)

        if (isMutedByUs()) {
            // Already tracking original volume.
            // If the system volume is currently above target (e.g. Spotify reset volume on track transition),
            // re-enforce the mute without overwriting the user's original music volume.
            if (current > target) {
                applyVolume(target)
                return "volume re-muted to $target (was $current)"
            }
            return ""
        }

        // First time muting this ad break:
        if (current <= target) {
            return "volume already $current/$max, left alone"
        }

        // Persist original music volume BEFORE muting
        Prefs.saveMuteState(ctx, saved = current, mutedTo = target)
        applyVolume(target)
        return "volume $current -> $target (of $max), original saved"
    }

    private fun applyVolume(target: Int) {
        if (target == 0) {
            try { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) {}
        }
        am.setStreamVolume(stream, target, 0)
    }

    /** Restores the saved volume. */
    fun restoreAfterAd(): RestoreOutcome {
        val saved = Prefs.savedVolume(ctx)
        if (saved < 0) return RestoreOutcome(RestoreResult.NOT_MUTED, "nothing to restore")

        val current = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        val expected = Prefs.mutedTo(ctx)
        Prefs.clearMuteState(ctx)

        // If the user intentionally raised the volume well above their saved volume during the ad:
        if (current > expected && current > saved && expected >= 0) {
            return RestoreOutcome(
                RestoreResult.USER_CHANGED,
                "volume changed to $current during ad, kept"
            )
        }

        // Restore to saved volume, ensuring it's not silent (fallback to 50% if saved was invalid)
        val restoreTo = if (saved > 0) saved else (max / 2).coerceAtLeast(1)

        try { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        am.setStreamVolume(stream, restoreTo, 0)

        // Extra check: if device is still silent, nudge it
        if (am.getStreamVolume(stream) == 0 && restoreTo > 0) {
            try { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) } catch (_: Exception) {}
            am.setStreamVolume(stream, restoreTo, 0)
        }

        return RestoreOutcome(RestoreResult.RESTORED, "volume restored to $restoreTo")
    }
}
