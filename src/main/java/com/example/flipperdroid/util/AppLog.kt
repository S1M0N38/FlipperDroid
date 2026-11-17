package com.example.flipperdroid.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central application log. Modules call [log] to trace their actions; the Logs
 * screen displays the stream. In memory (max 500 lines).
 */
object AppLog {
    private const val MAX = 500
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    fun log(tag: String, message: String) {
        val line = synchronized(fmt) { "${fmt.format(Date())} [$tag] $message" }
        // update {} = atomic read-modify-write: modules log from several IO coroutines,
        // a plain `value = value + line` would lose lines.
        _entries.update { (it + line).takeLast(MAX) }
        android.util.Log.d("FlipperDroid/$tag", message)
    }

    fun clear() { _entries.value = emptyList() }

    fun dump(): String = _entries.value.joinToString("\n")
}
