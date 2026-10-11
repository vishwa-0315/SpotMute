package com.spotmute

import android.content.Context
import android.content.SharedPreferences

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

    fun register(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).registerOnSharedPreferenceChangeListener(l)

    fun unregister(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).unregisterOnSharedPreferenceChangeListener(l)
}
