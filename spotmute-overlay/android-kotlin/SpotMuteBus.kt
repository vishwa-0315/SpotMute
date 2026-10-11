package com.spotmute

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Snapshot of what the service currently sees. Sent to the React Native UI. */
data class Status(
    val listenerConnected: Boolean = false,
    val spotifyFound: Boolean = false,
    /** off | idle | music | ad | ad-paused */
    val state: String = "idle",
    val playing: Boolean = false,
    val mutedByUs: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val score: Int = 0,
    val signals: List<String> = emptyList(),
)

/** In-process mailbox between the listener service and the React Native module. */
object SpotMuteBus {
    @Volatile
    var status: Status = Status()
        private set

    @Volatile
    var listener: (() -> Unit)? = null

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun publish(s: Status) {
        status = s
        listener?.invoke()
    }

    @Synchronized
    fun addLog(msg: String) {
        lines.addFirst("${fmt.format(Date())}  $msg")
        while (lines.size > 40) lines.removeLast()
        listener?.invoke()
    }

    @Synchronized
    fun logLines(): List<String> = lines.toList()
}
