package com.eraassistant.mobile.shizuku

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService that exposes a deliberately small, read-only command set.
 * It never accepts arbitrary shell text from ERA or from an AI model.
 */
@Keep
class EraShizukuUserService : IEraShizukuUserService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun executeReadOnly(operationId: String, argument: String): String {
        return try {
            when (operationId) {
                "identity" -> "uid=${Process.myUid()} pid=${Process.myPid()}\n" +
                    runCommand(listOf("/system/bin/id"))
                "device" -> listOf(
                    "manufacturer=${Build.MANUFACTURER}",
                    "model=${Build.MODEL}",
                    "device=${Build.DEVICE}",
                    "android=${Build.VERSION.RELEASE}",
                    "sdk=${Build.VERSION.SDK_INT}",
                    "shell_uid=${Process.myUid()}"
                ).joinToString("\n")
                "battery" -> {
                    val raw = runCommand(listOf("/system/bin/dumpsys", "battery"))
                    raw.lineSequence()
                        .filter { line ->
                            line.trim().substringBefore(":") in BATTERY_FIELDS
                        }
                        .take(16)
                        .joinToString("\n")
                        .ifBlank { "Battery details unavailable." }
                }
                "apps" -> runCommand(listOf("/system/bin/pm", "list", "packages", "-3"))
                    .lineSequence()
                    .filter { it.startsWith("package:") }
                    .take(MAX_APP_ROWS)
                    .joinToString("\n")
                "display" -> listOf(
                    "wm size:\n" + runCommand(listOf("/system/bin/wm", "size")),
                    "wm density:\n" + runCommand(listOf("/system/bin/wm", "density")),
                    "screen_brightness=" + runCommand(listOf("/system/bin/settings", "get", "system", "screen_brightness")).trim(),
                    "screen_brightness_mode=" + runCommand(listOf("/system/bin/settings", "get", "system", "screen_brightness_mode")).trim()
                ).joinToString("\n")
                "storage" -> listOf(
                    "data filesystem:\n" + runCommand(listOf("/system/bin/df", "-k", "/data")),
                    "memory:\n" + runCommand(listOf("/system/bin/sh", "-c", "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo"))
                ).joinToString("\n")
                "app_path" -> {
                    if (!PACKAGE_NAME.matches(argument)) {
                        "ERROR: invalid package name"
                    } else {
                        runCommand(listOf("/system/bin/pm", "path", argument))
                    }
                }
                else -> "ERROR: operation is not in the read-only allowlist"
            }
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(240)}"
        }
    }

    private fun runCommand(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "ERROR: command timed out"
        }
        val text = process.inputStream.bufferedReader().use { reader ->
            buildString {
                while (length < MAX_OUTPUT_CHARS) {
                    val line = reader.readLine() ?: break
                    appendLine(line)
                }
            }
        }.trim()
        return if (process.exitValue() == 0) {
            text.ifBlank { "(no output)" }
        } else {
            "ERROR (exit ${process.exitValue()}): ${text.take(MAX_ERROR_CHARS)}"
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 5L
        private const val MAX_OUTPUT_CHARS = 16_000
        private const val MAX_ERROR_CHARS = 600
        private const val MAX_APP_ROWS = 120
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val BATTERY_FIELDS = setOf(
            "AC powered", "USB powered", "Wireless powered", "status", "health",
            "level", "voltage", "temperature", "cycle count", "present",
            "Max charging current", "Max charging voltage"
        )
    }
}
