package com.xjanova.tping.data.license

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import com.xjanova.tping.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Runtime integrity checker — detects APK tampering, root, Frida, and debuggers.
 *
 * Usage:
 * - [runQuickCheck] — fast (debugger + Frida ports), call from Application.onCreate()
 * - [runAllChecks] — full scan, call from LicenseManager.initialize()
 */
object IntegrityChecker {

    private const val TAG = "IntegrityChecker"

    data class IntegrityResult(
        val isApkTampered: Boolean = false,
        val isRooted: Boolean = false,
        val isFridaDetected: Boolean = false,
        val isDebuggerAttached: Boolean = false,
        val isDebuggable: Boolean = false,
        val isXposedDetected: Boolean = false,
        val details: List<String> = emptyList()
    ) {
        /** Critical tampering: APK modified or active instrumentation in release */
        val shouldBlockLicense: Boolean
            get() = isApkTampered ||
                    (isFridaDetected && !BuildConfig.DEBUG) ||
                    (isDebuggerAttached && !BuildConfig.DEBUG)

        /** Any flag worth reporting to server */
        val hasAnyFlag: Boolean
            get() = isApkTampered || isRooted || isFridaDetected ||
                    isDebuggerAttached || isDebuggable || isXposedDetected
    }

    // ====== Public API ======

    /**
     * Full integrity scan. Call from LicenseManager.initialize().
     */
    fun runAllChecks(context: Context): IntegrityResult {
        val details = mutableListOf<String>()
        return try {
            val apkTampered = checkApkSignature(context).also {
                if (it) details.add("apk_tampered")
            }
            val rooted = checkRoot().also {
                if (it) details.add("root_detected")
            }
            val frida = checkFrida().also {
                if (it) details.add("frida_detected")
            }
            val debugger = checkDebuggerAttached().also {
                if (it) details.add("debugger_attached")
            }
            val debuggable = checkDebuggable(context).also {
                if (it) details.add("debuggable_flag")
            }
            val xposed = checkXposed().also {
                if (it) details.add("xposed_detected")
            }

            IntegrityResult(
                isApkTampered = apkTampered,
                isRooted = rooted,
                isFridaDetected = frida,
                isDebuggerAttached = debugger,
                isDebuggable = debuggable,
                isXposedDetected = xposed,
                details = details
            )
        } catch (e: Exception) {
            Log.e(TAG, "runAllChecks failed", e)
            IntegrityResult(details = listOf("check_error: ${e.message}"))
        }
    }

    /**
     * Quick check — debugger + Frida ports only. Fast, no heavy I/O.
     * Call from TpingApplication.onCreate().
     */
    fun runQuickCheck(): IntegrityResult {
        val details = mutableListOf<String>()
        return try {
            val debugger = checkDebuggerAttached().also {
                if (it) details.add("debugger_attached")
            }
            val frida = checkFridaPorts().also {
                if (it) details.add("frida_port_open")
            }
            IntegrityResult(
                isDebuggerAttached = debugger,
                isFridaDetected = frida,
                details = details
            )
        } catch (e: Exception) {
            Log.e(TAG, "runQuickCheck failed", e)
            IntegrityResult()
        }
    }

    // ====== APK Signature Verification ======

    /**
     * Returns true if APK signing cert does NOT match expected hash (= tampered).
     */
    private fun checkApkSignature(context: Context): Boolean {
        return try {
            val expectedHash = BuildConfig.EXPECTED_SIGNING_CERT_HASH
            if (expectedHash.isBlank()) return false

            val actualHash = getSigningCertHash(context) ?: return false

            val tampered = !actualHash.equals(expectedHash, ignoreCase = true)
            if (tampered) {
                Log.w(TAG, "APK SIGNATURE MISMATCH! expected=$expectedHash actual=$actualHash")
            }
            tampered
        } catch (e: Exception) {
            Log.e(TAG, "checkApkSignature failed", e)
            false // fail open
        }
    }

    @Suppress("DEPRECATION")
    private fun getSigningCertHash(context: Context): String? {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }

            if (signatures.isNullOrEmpty()) return null

            val certBytes = signatures[0].toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "getSigningCertHash failed", e)
            null
        }
    }

    // ====== Root Detection ======

    private fun checkRoot(): Boolean {
        return try {
            checkSuBinary() || checkRootManagementApps() || checkMagiskPaths() ||
                    checkSELinuxPermissive()
        } catch (e: Exception) {
            Log.e(TAG, "checkRoot failed", e)
            false
        }
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/su", "/data/local/su", "/data/local/bin/su",
            "/data/local/xbin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/vendor/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkRootManagementApps(): Boolean {
        val packages = arrayOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su"
        )
        return packages.any { pkg ->
            try {
                Runtime.getRuntime().exec("pm path $pkg")
                    .inputStream.bufferedReader().readLine()?.isNotBlank() == true
            } catch (_: Exception) { false }
        }
    }

    private fun checkMagiskPaths(): Boolean {
        val paths = arrayOf(
            "/sbin/.magisk", "/data/adb/magisk",
            "/data/adb/modules", "/cache/magisk.log"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkSELinuxPermissive(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("getenforce")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()?.trim() ?: ""
            reader.close()
            result.equals("Permissive", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    // ====== Frida Detection ======

    private fun checkFrida(): Boolean {
        return try {
            checkFridaPorts() || checkFridaProcess() || checkFridaLibraries()
        } catch (e: Exception) {
            Log.e(TAG, "checkFrida failed", e)
            false
        }
    }

    /**
     * Check if frida-server default ports are open.
     */
    private fun checkFridaPorts(): Boolean {
        val fridaPorts = intArrayOf(27042, 27043)
        return fridaPorts.any { port ->
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                socket.close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun checkFridaProcess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("ps -A")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("frida", ignoreCase = true) == true) {
                    reader.close()
                    return true
                }
            }
            reader.close()
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check /proc/self/maps for frida-agent injected libraries.
     */
    private fun checkFridaLibraries(): Boolean {
        return try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                mapsFile.readLines().any { line ->
                    line.contains("frida", ignoreCase = true) ||
                            line.contains("gadget", ignoreCase = true)
                }
            } else false
        } catch (_: Exception) {
            false
        }
    }

    // ====== Xposed Detection ======

    private fun checkXposed(): Boolean {
        return try {
            val xposedPackages = arrayOf(
                "de.robv.android.xposed.installer",
                "com.solohsu.android.edxp.manager",
                "org.meowcat.edxposed.manager",
                "org.lsposed.manager"
            )
            val hasXposedApp = xposedPackages.any { pkg ->
                try {
                    Runtime.getRuntime().exec("pm path $pkg")
                        .inputStream.bufferedReader().readLine()?.isNotBlank() == true
                } catch (_: Exception) { false }
            }

            // Check stack trace for Xposed hooks
            val hasXposedStack = try {
                throw Exception("xposed-check")
            } catch (e: Exception) {
                e.stackTrace.any { frame ->
                    frame.className.contains("xposed", ignoreCase = true) ||
                            frame.className.contains("lsposed", ignoreCase = true)
                }
            }

            hasXposedApp || hasXposedStack
        } catch (_: Exception) {
            false
        }
    }

    // ====== Debugger Detection ======

    private fun checkDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    private fun checkDebuggable(context: Context): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }
    }
}
