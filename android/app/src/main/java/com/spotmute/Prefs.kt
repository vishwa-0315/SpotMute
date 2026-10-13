package com.spotmute

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny SharedPreferences wrapper.
 * The mute state (saved volume + the level we muted to) is written with commit()
 * so it survives the process being killed while an ad is playing.
 */
object Prefs {
    private const val FILE = "spotmute_prefs"

    const val K_ENABLED = "enabled"
    const val K_AD_PERCENT = "ad_percent"
    const val K_EXTRA_TITLES = "extra_titles"
    const val K_ADS_MUTED = "ads_muted_count"
    const val K_TIME_SAVED_SEC = "time_saved_sec"
    const val K_SUBSTITUTE_MUSIC = "substitute_music_enabled"
    const val K_MUTE_SYSTEM_VOLUME = "mute_system_volume"
    const val K_SUBSTITUTE_TRACKS = "substitute_tracks_json"
    private const val K_SAVED = "saved_volume"
    private const val K_MUTED_TO = "muted_to"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = sp(c).getBoolean(K_ENABLED, true)
    fun setEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_ENABLED, v).apply()
    }

    /** Volume (percent of max) to use while an ad plays. 0 = full mute. */
    fun adVolumePercent(c: Context): Int = sp(c).getInt(K_AD_PERCENT, 0)
    fun setAdVolumePercent(c: Context, v: Int) {
        sp(c).edit().putInt(K_AD_PERCENT, v.coerceIn(0, 50)).apply()
    }

    /** Comma-separated extra words that should count as "this title is an ad". */
    fun extraTitles(c: Context): String = sp(c).getString(K_EXTRA_TITLES, "") ?: ""
    fun setExtraTitles(c: Context, v: String) {
        sp(c).edit().putString(K_EXTRA_TITLES, v).apply()
    }

    /** Volume index we saved just before muting, or -1 when we are not muting. */
    fun savedVolume(c: Context): Int = sp(c).getInt(K_SAVED, -1)

    /** Volume index we set when muting (used to detect that the user changed it during the ad). */
    fun mutedTo(c: Context): Int = sp(c).getInt(K_MUTED_TO, -1)

    fun saveMuteState(c: Context, saved: Int, mutedTo: Int) {
        sp(c).edit().putInt(K_SAVED, saved).putInt(K_MUTED_TO, mutedTo).commit()
    }

    fun clearMuteState(c: Context) {
        sp(c).edit().remove(K_SAVED).remove(K_MUTED_TO).commit()
    }

    fun adsMutedCount(c: Context): Int = sp(c).getInt(K_ADS_MUTED, 0)

    fun timeSavedSec(c: Context): Long = sp(c).getLong(K_TIME_SAVED_SEC, 0L)

    fun incrementAdStats(c: Context, durationSec: Long) {
        val currentCount = adsMutedCount(c)
        val currentTime = timeSavedSec(c)
        sp(c).edit()
            .putInt(K_ADS_MUTED, currentCount + 1)
            .putLong(K_TIME_SAVED_SEC, currentTime + durationSec)
            .apply()
    }

    fun resetStats(c: Context) {
        sp(c).edit().remove(K_ADS_MUTED).remove(K_TIME_SAVED_SEC).apply()
    }

    fun isSubstituteMusicEnabled(c: Context): Boolean = sp(c).getBoolean(K_SUBSTITUTE_MUSIC, false)
    fun setSubstituteMusicEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_SUBSTITUTE_MUSIC, v).apply()
    }

    fun isMuteSystemVolume(c: Context): Boolean = sp(c).getBoolean(K_MUTE_SYSTEM_VOLUME, true)
    fun setMuteSystemVolume(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_MUTE_SYSTEM_VOLUME, v).apply()
    }

    fun getSubstituteTracks(c: Context): List<SubstituteTrack> {
        val raw = sp(c).getString(K_SUBSTITUTE_TRACKS, "[]") ?: "[]"
        val list = mutableListOf<SubstituteTrack>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SubstituteTrack(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        path = obj.getString("path"),
                        sizeBytes = obj.optLong("sizeBytes", 0L)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveSubstituteTracks(c: Context, tracks: List<SubstituteTrack>) {
        val arr = JSONArray()
        for (t in tracks) {
            val obj = JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("path", t.path)
                put("sizeBytes", t.sizeBytes)
            }
            arr.put(obj)
        }
        sp(c).edit().putString(K_SUBSTITUTE_TRACKS, arr.toString()).apply()
    }

    fun addSubstituteTrack(c: Context, track: SubstituteTrack) {
        val current = getSubstituteTracks(c).toMutableList()
        current.add(track)
        saveSubstituteTracks(c, current)
    }

    fun removeSubstituteTrack(c: Context, id: String): List<SubstituteTrack> {
        val current = getSubstituteTracks(c).toMutableList()
        val toRemove = current.find { it.id == id }
        if (toRemove != null) {
            current.remove(toRemove)
            try { File(toRemove.path).delete() } catch (_: Exception) {}
            saveSubstituteTracks(c, current)
        }
        return current
    }

    fun clearSubstituteTracks(c: Context) {
        val current = getSubstituteTracks(c)
        for (t in current) {
            try { File(t.path).delete() } catch (_: Exception) {}
        }
        saveSubstituteTracks(c, emptyList())
    }

    fun register(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).registerOnSharedPreferenceChangeListener(l)

    fun unregister(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).unregisterOnSharedPreferenceChangeListener(l)
}
