package com.xjanova.tping.puzzle

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Helper for Shizuku shell command execution.
 *
 * Shizuku provides ADB-level privileges (shell UID) without root.
 * This enables `input swipe` commands that produce isTrusted=true
 * touch events in WebView — critical for CAPTCHA solving.
 *
 * Verified against Shizuku API 13.1.5:
 *   - newProcess is private static → accessed via reflection
 *   - ShizukuRemoteProcess extends Process → cast works
 *   - waitForTimeout(long, TimeUnit) → efficient Binder-level wait
 *
 * User setup required:
 * 1. Install Shizuku from Play Store
 * 2. Enable Developer Options → Wireless Debugging
 * 3. Start Shizuku via Wireless Debugging
 * 4. Grant permission to Tping
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    // Cache the reflected method to avoid repeated lookups
    @Volatile
    private var cachedNewProcessMethod: Method? = null

    /**
     * Check if Shizuku is fully available (binder alive + permission granted).
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    /**
     * Check if Shizuku binder is alive (app installed and service running).
     */
    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if Shizuku permission is granted.
     */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Request Shizuku permission. Must be called from an Activity context.
     * Listen for result via Shizuku.addRequestPermissionResultListener().
     */
    fun requestPermission(requestCode: Int = 1) {
        try {
            if (!isRunning()) {
                Log.w(TAG, "Cannot request permission: Shizuku not running")
                return
            }
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission error: ${e.message}")
        }
    }

    /**
     * Get detailed status for diagnostics (machine-readable).
     */
    fun getDetailedStatus(): String {
        return try {
            val binderAlive = Shizuku.pingBinder()
            val permGranted = if (binderAlive) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else false
            val version = if (binderAlive) {
                try { Shizuku.getVersion().toString() } catch (_: Exception) { "?" }
            } else "n/a"
            "binder=$binderAlive, perm=$permGranted, ver=$version"
        } catch (e: Exception) {
            "error:${e.message?.take(40)}"
        }
    }

    /**
     * Get status description for UI display.
     */
    fun getStatusText(): String {
        return try {
            when {
                !Shizuku.pingBinder() -> "ไม่พบ Shizuku — กรุณาติดตั้งและเปิดใช้งาน"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    "Shizuku ทำงานอยู่ — กรุณาอนุญาตสิทธิ์"
                else -> "Shizuku พร้อมใช้งาน ✓"
            }
        } catch (e: Exception) {
            "ไม่พบ Shizuku — กรุณาติดตั้งและเปิดใช้งาน"
        }
    }

    /**
     * Get or create the reflected newProcess method.
     * Cached after first successful lookup.
     *
     * Shizuku 13.1.5 signature:
     *   private static ShizukuRemoteProcess newProcess(String[], String[], String)
     */
    private fun getNewProcessMethod(): Method {
        cachedNewProcessMethod?.let { return it }
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        cachedNewProcessMethod = method
        return method
    }

    /**
     * Execute shell command via Shizuku (ADB-level privileges).
     * Uses reflection to access newProcess (private in Shizuku 13.1.5+).
     *
     * The command runs as shell UID (2000) which has permission to use
     * `input` commands that create hardware-level touch events.
     *
     * Returns "ok" on success, or error description.
     */
    fun execCommand(cmd: String): String {
        return try {
            if (!isAvailable()) {
                val detail = getDetailedStatus()
                Log.d(TAG, "Shizuku unavailable: $detail")
                return "shizuku_unavailable($detail)"
            }

            val method = getNewProcessMethod()
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null as Array<String>?,
                null as String?
            ) as Process

            // Use ShizukuRemoteProcess.waitForTimeout for efficient Binder-level wait
            // (avoids busy-polling in default Process.waitFor(long, TimeUnit))
            val exited = if (process is ShizukuRemoteProcess) {
                process.waitForTimeout(10, TimeUnit.SECONDS)
            } else {
                process.waitFor(10, TimeUnit.SECONDS)
            }

            if (!exited) {
                process.destroy()
                return "timeout"
            }

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                "ok"
            } else {
                val err = process.errorStream.bufferedReader().readText().take(80)
                "exit$exitCode:$err"
            }
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Shizuku API changed: newProcess not found", e)
            cachedNewProcessMethod = null  // Clear cache on API change
            "error:newProcess_not_found"
        } catch (e: SecurityException) {
            Log.e(TAG, "Shizuku security error", e)
            "error:security(${e.message?.take(40)})"
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec error: ${e.message}")
            "error:${e.message?.take(60)}"
        }
    }

    /**
     * Execute `input swipe` via Shizuku for hardware-level touch events.
     * Creates isTrusted=true MotionEvents in WebView.
     */
    fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
        Log.d(TAG, "inputSwipe: ($x1,$y1)->($x2,$y2) ${durationMs}ms")
        return execCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Execute `input draganddrop` via Shizuku.
     * Unlike `input swipe`, draganddrop has a built-in long press (~500ms)
     * before starting the drag, required by many puzzle CAPTCHAs.
     */
    fun inputDragAndDrop(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
        Log.d(TAG, "inputDragAndDrop: ($x1,$y1)->($x2,$y2) ${durationMs}ms")
        return execCommand("input draganddrop $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Execute `input tap` via Shizuku.
     */
    fun inputTap(x: Int, y: Int): String {
        Log.d(TAG, "inputTap: ($x,$y)")
        return execCommand("input tap $x $y")
    }

    /**
     * Execute shell command via Shizuku and return stdout output.
     * Unlike execCommand which returns "ok"/"error", this returns the actual
     * command output for commands like `getevent -pl`.
     * Returns null on failure.
     */
    fun execCommandWithOutput(cmd: String): String? {
        return try {
            if (!isAvailable()) return null

            val method = getNewProcessMethod()
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null as Array<String>?,
                null as String?
            ) as Process

            val stdout = process.inputStream.bufferedReader().readText()

            val exited = if (process is ShizukuRemoteProcess) {
                process.waitForTimeout(10, TimeUnit.SECONDS)
            } else {
                process.waitFor(10, TimeUnit.SECONDS)
            }

            if (!exited) {
                process.destroy()
                return null
            }

            if (process.exitValue() == 0) stdout.trim().ifEmpty { null } else null
        } catch (e: Exception) {
            Log.e(TAG, "execCommandWithOutput error: ${e.message}")
            null
        }
    }
}
