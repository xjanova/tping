package com.xjanova.tping.puzzle

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.CompletableDeferred

object PuzzleScreenCapture {

    private const val TAG = "PuzzleScreenCapture"

    /**
     * Capture the current screen using AccessibilityService.takeScreenshot() (API 30+).
     * Returns null if capture fails or API level is too low.
     */
    suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+, current: ${Build.VERSION.SDK_INT}")
            return null
        }

        val service = TpingAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "AccessibilityService not available")
            return null
        }

        val deferred = CompletableDeferred<Bitmap?>()

        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            // Convert hardware bitmap to software bitmap for OpenCV processing
                            val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            result.hardwareBuffer.close()
                            deferred.complete(swBitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to process screenshot: ${e.message}")
                            deferred.complete(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        deferred.complete(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception: ${e.message}")
            deferred.complete(null)
        }

        return deferred.await()
    }

    /**
     * Crop a region from a screenshot bitmap.
     * Coordinates are already scaled to current screen resolution by the caller.
     */
    fun cropRegion(
        screenshot: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int
    ): Bitmap? {
        return try {
            val clampedLeft = left.coerceIn(0, screenshot.width - 1)
            val clampedTop = top.coerceIn(0, screenshot.height - 1)
            val clampedRight = right.coerceIn(clampedLeft + 1, screenshot.width)
            val clampedBottom = bottom.coerceIn(clampedTop + 1, screenshot.height)
            Bitmap.createBitmap(
                screenshot,
                clampedLeft, clampedTop,
                clampedRight - clampedLeft,
                clampedBottom - clampedTop
            )
        } catch (e: Exception) {
            Log.e(TAG, "Crop failed: ${e.message}")
            null
        }
    }
}
