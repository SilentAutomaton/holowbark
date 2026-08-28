package net.holowbark

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bounded in-memory log the Logs screen can read. Everything in the app logs
 * here; android.util.Log alone is not visible to a user debugging on their own
 * network.
 */
object AppLogger {
    enum class Level { V, D, I, W, E }

    data class Line(val time: String, val level: Level, val tag: String, val msg: String)

    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Replaced wholesale rather than mutated, so readers never see a torn list and
    // appending costs one allocation instead of copying the whole buffer.
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    @Synchronized
    fun append(level: Level, tag: String, msg: String) {
        val current = _lines.value
        val kept = if (current.size >= MAX_LINES) current.subList(1, current.size) else current
        _lines.value = kept + Line(fmt.format(Date()), level, tag, msg)
    }

    fun clear() { _lines.value = emptyList() }

    fun v(tag: String, msg: String) { Log.v(tag, msg); append(Level.V, tag, msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); append(Level.D, tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); append(Level.I, tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); append(Level.W, tag, msg) }
    fun e(tag: String, msg: String) { Log.e(tag, msg); append(Level.E, tag, msg) }
    fun e(tag: String, msg: String, t: Throwable) { Log.e(tag, msg, t); append(Level.E, tag, "$msg — $t") }
}
