package com.example.flipperdroid.util

import java.io.IOException
import java.io.OutputStream

/**
 * Single entry point for everything that needs a root shell.
 *
 * Devices disagree about `su`'s command line:
 *
 * - Magisk, SuperSU and most rooted ROMs take `su -c '<cmd>'`.
 * - The AOSP/toybox `su` shipped in userdebug images — including the Android 14
 *   `google_apis` emulator image — takes `su [WHO [COMMAND...]]` and rejects `-c`
 *   with `su: invalid uid/gid '-c'`. `su 0 sh -c '<cmd>'` is the equivalent there.
 *
 * Probing once and remembering the working form means a root module behaves the
 * same on a rooted Pixel 7 and on an emulator, instead of reporting "no root"
 * because of an argv mismatch. It also gives the root modules one place to fail
 * loudly and one place to test.
 *
 * The command string is always built with [ShellArgs]: it is handed to a shell,
 * so unquoted user data is root command injection.
 *
 * Blocking: every call here runs a process. Call from `Dispatchers.IO`, never
 * from the main thread.
 */
object RootShell {

    /** `su -c '<cmd>'` — Magisk, SuperSU, most rooted ROMs. */
    private val SHELL_C_DIALECT = arrayOf("su", "-c")

    /** `su 0 sh -c '<cmd>'` — AOSP/toybox `su` on userdebug and emulator images. */
    private val AOSP_DIALECT = arrayOf("su", "0", "sh", "-c")

    private val DIALECTS = listOf(SHELL_C_DIALECT, AOSP_DIALECT)

    @Volatile private var dialect: Array<String>? = null

    @Volatile private var probed = false

    /**
     * True when a usable root shell answered `id` as uid 0.
     *
     * Cached: the probe starts one or two processes, and the answer cannot change
     * while the app is running except by un-rooting the device.
     */
    fun isAvailable(): Boolean = dialect() != null

    /** Starts [command] as root. Throws [IOException] when there is no root shell. */
    fun exec(command: String): Process = ProcessBuilder(commandLine(command)).start()

    /**
     * The argv for [command], for callers that need their own [ProcessBuilder]
     * (e.g. `redirectErrorStream`). Throws [IOException] when there is no root shell.
     */
    fun commandLine(command: String): List<String> {
        val prefix = dialect() ?: throw IOException("no root shell available")
        return prefix.toList() + command
    }

    /** Runs [command] to completion and returns its exit code (-1 if unavailable). */
    fun exitCode(command: String): Int = try {
        exec(command).waitFor()
    } catch (_: Exception) {
        -1
    }

    /** Runs [command] to completion and returns its stdout ("" if unavailable). */
    fun output(command: String): String = try {
        val process = exec(command)
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    } catch (_: Exception) {
        ""
    }

    /**
     * Opens stdin of a long-lived root process, for callers that stream into it
     * (the USB-gadget BadUSB writes HID reports to `/dev/hidgX`).
     */
    fun openForWriting(command: String): OutputStream = exec(command).outputStream


    /** Returns the working `su` prefix, probing the known dialects once. */
    private fun dialect(): Array<String>? {
        dialect?.let { return it }
        if (probed) return null
        synchronized(this) {
            dialect?.let { return it }
            if (probed) return null
            for (candidate in DIALECTS) {
                if (probeRoot(candidate)) {
                    dialect = candidate
                    probed = true
                    AppLog.log("RootShell", "Using root dialect: ${candidate.joinToString(" ")}")
                    return candidate
                }
            }
            probed = true
            AppLog.log("RootShell", "No root shell answered as uid 0")
            return null
        }
    }

    private fun probeRoot(prefix: Array<String>): Boolean = try {
        val process = Runtime.getRuntime().exec(prefix + "id")
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out.contains("uid=0")
    } catch (_: Exception) {
        false
    }
}
