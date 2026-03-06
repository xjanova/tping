package com.xjanova.tping.puzzle

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object PuzzleSolver {

    private const val TAG = "PuzzleSolver"
    private var opencvInitialized = false

    /** Brightness threshold for "dark pixel" detection */
    private const val DARK_THRESHOLD = 70.0

    fun ensureOpenCV(): Boolean {
        if (!opencvInitialized) {
            opencvInitialized = try {
                OpenCVLoader.initLocal()
            } catch (e: Exception) {
                Log.e(TAG, "OpenCV initLocal exception: ${e.message}")
                false
            }
            if (!opencvInitialized) {
                Log.w(TAG, "initLocal failed, trying initDebug...")
                opencvInitialized = try {
                    @Suppress("DEPRECATION")
                    OpenCVLoader.initDebug()
                } catch (e: Exception) {
                    Log.e(TAG, "OpenCV initDebug exception: ${e.message}")
                    false
                }
            }
            if (opencvInitialized) {
                Log.d(TAG, "OpenCV initialized successfully")
            } else {
                Log.e(TAG, "OpenCV initialization FAILED")
            }
        }
        return opencvInitialized
    }

    /**
     * Auto-detect the dark gap region in a full screenshot.
     * Searches the area above the slider button for a rectangular dark shadow.
     *
     * @param screenshot Full screen bitmap
     * @param sliderY Y coordinate of slider button (search above this)
     * @return Rect of the dark gap region in screen coordinates, or null if not found
     */
    fun findDarkRegion(screenshot: Bitmap, sliderY: Int): Rect? {
        if (!ensureOpenCV()) return null

        val src = Mat()
        val gray = Mat()
        val binary = Mat()
        val morphed = Mat()
        val hierarchy = Mat()

        try {
            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // Search region: from sliderY - 600 to sliderY - 20, full width
            // Clamp to valid bitmap bounds
            val searchTop = (sliderY - 600).coerceAtLeast(0)
            val searchBottom = (sliderY - 20).coerceAtLeast(searchTop + 10)
                .coerceAtMost(screenshot.height)
            val searchWidth = screenshot.width

            if (searchBottom <= searchTop) {
                Log.w(TAG, "Invalid search region: top=$searchTop bottom=$searchBottom")
                return null
            }

            // Crop search region from grayscale
            val searchRegion = gray.submat(searchTop, searchBottom, 0, searchWidth)

            // Threshold: dark pixels (brightness <= DARK_THRESHOLD) → white, rest → black
            Imgproc.threshold(searchRegion, binary, DARK_THRESHOLD, 255.0, Imgproc.THRESH_BINARY_INV)

            // Morphological close to connect nearby dark pixels
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.morphologyEx(binary, morphed, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            // Find contours
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                morphed, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val searchArea = (searchBottom - searchTop).toDouble() * searchWidth
            // Gap is typically 2-15% of the search area
            val minArea = searchArea * 0.005
            val maxArea = searchArea * 0.20

            var bestRect: Rect? = null
            var bestScore = 0.0

            Log.d(TAG, "findDarkRegion: ${contours.size} contours, searchArea=${"%.0f".format(searchArea)}")

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea || area > maxArea) {
                    contour.release()
                    continue
                }

                val rect = Imgproc.boundingRect(contour)
                if (rect.height <= 5 || rect.width <= 5) {
                    contour.release()
                    continue
                }

                val aspectRatio = rect.width.toFloat() / rect.height
                // Gap is roughly square-ish (0.4 to 2.5 ratio)
                if (aspectRatio < 0.4 || aspectRatio > 2.5) {
                    contour.release()
                    continue
                }

                // Skip if too far left (< 10% from left edge) — that's usually the puzzle piece
                if (rect.x < searchWidth * 0.08) {
                    contour.release()
                    continue
                }

                // Score: prefer larger, more square shapes
                val squareness = 1.0 - Math.abs(1.0 - aspectRatio.toDouble()).coerceAtMost(1.0)
                val score = area * (0.5 + squareness * 0.5)

                Log.d(TAG, "  dark contour: x=${rect.x} y=${rect.y} w=${rect.width} h=${rect.height} " +
                        "area=${"%.0f".format(area)} ratio=${"%.2f".format(aspectRatio)} score=${"%.0f".format(score)}")

                if (score > bestScore) {
                    bestScore = score
                    // Convert back to full-screen coordinates
                    bestRect = Rect(
                        rect.x,
                        rect.y + searchTop,
                        rect.x + rect.width,
                        rect.y + rect.height + searchTop
                    )
                }
                contour.release()
            }

            searchRegion.release()

            if (bestRect != null) {
                Log.d(TAG, "findDarkRegion: found gap at (${bestRect.left},${bestRect.top})-(${bestRect.right},${bestRect.bottom})")
            } else {
                Log.d(TAG, "findDarkRegion: no gap found")
            }
            return bestRect
        } catch (e: Exception) {
            Log.e(TAG, "findDarkRegion failed: ${e.message}")
            return null
        } finally {
            src.release()
            gray.release()
            binary.release()
            morphed.release()
            hierarchy.release()
        }
    }

    /**
     * Measure the "darkness" of a specific region in a bitmap.
     * Returns the count of pixels darker than the threshold.
     * Used to track whether the gap is being filled during sliding.
     *
     * @param bitmap Full screen bitmap
     * @param region The region to measure (screen coordinates)
     * @return Number of dark pixels, or -1 on error
     */
    fun measureDarkness(bitmap: Bitmap, region: Rect): Int {
        if (!ensureOpenCV()) return -1

        val src = Mat()
        val gray = Mat()

        try {
            // Clamp region to bitmap bounds
            val left = region.left.coerceIn(0, bitmap.width - 1)
            val top = region.top.coerceIn(0, bitmap.height - 1)
            val right = region.right.coerceIn(left + 1, bitmap.width)
            val bottom = region.bottom.coerceIn(top + 1, bitmap.height)

            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val roi = gray.submat(top, bottom, left, right)

            // Count pixels below threshold
            val mask = Mat()
            Imgproc.threshold(roi, mask, DARK_THRESHOLD, 255.0, Imgproc.THRESH_BINARY_INV)
            val count = Core.countNonZero(mask)

            mask.release()
            roi.release()

            return count
        } catch (e: Exception) {
            Log.e(TAG, "measureDarkness failed: ${e.message}")
            return -1
        } finally {
            src.release()
            gray.release()
        }
    }

    // ===== Legacy methods below (kept for backward compatibility) =====

    /**
     * Analyze a puzzle image to find the gap's X offset from the left edge.
     * Returns the pixel X offset where the gap center is, or -1 on failure.
     */
    fun findGapOffset(puzzleBitmap: Bitmap, method: String = "edge"): Int {
        if (!ensureOpenCV()) return -1

        val primary = when (method) {
            "template" -> findGapByColumnVariance(puzzleBitmap)
            else -> findGapByEdgeDetection(puzzleBitmap)
        }
        if (primary >= 0) return primary

        Log.d(TAG, "Primary method '$method' failed, trying fallback...")
        return when (method) {
            "template" -> findGapByEdgeDetection(puzzleBitmap)
            else -> findGapByColumnVariance(puzzleBitmap)
        }
    }

    private fun findGapByEdgeDetection(bitmap: Bitmap): Int {
        val src = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val dilated = Mat()
        val hierarchy = Mat()

        try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, dilated, kernel)
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                dilated, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val imageArea = bitmap.width.toDouble() * bitmap.height
            val minArea = imageArea * 0.005
            val maxArea = imageArea * 0.20

            var bestX = -1
            var bestScore = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea || area > maxArea) { contour.release(); continue }

                val rect = Imgproc.boundingRect(contour)
                if (rect.height <= 0) { contour.release(); continue }
                val aspectRatio = rect.width.toFloat() / rect.height
                if (aspectRatio < 0.3 || aspectRatio > 3.0) { contour.release(); continue }
                if (rect.x < bitmap.width * 0.10) { contour.release(); continue }

                val squareness = 1.0 - Math.abs(1.0 - aspectRatio.toDouble()).coerceAtMost(1.0)
                val score = area * (0.5 + squareness * 0.5)

                if (score > bestScore) {
                    bestScore = score
                    bestX = rect.x + rect.width / 2
                }
                contour.release()
            }

            return bestX
        } catch (e: Exception) {
            Log.e(TAG, "Edge detection failed: ${e.message}")
            return -1
        } finally {
            src.release(); gray.release(); blurred.release()
            edges.release(); dilated.release(); hierarchy.release()
        }
    }

    private fun findGapByColumnVariance(bitmap: Bitmap): Int {
        val src = Mat()
        val gray = Mat()

        try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val width = gray.cols()
            val columnVariances = DoubleArray(width)

            for (x in 0 until width) {
                val col = gray.col(x)
                val meanMat = MatOfDouble()
                val stddevMat = MatOfDouble()
                Core.meanStdDev(col, meanMat, stddevMat)
                columnVariances[x] = if (stddevMat.rows() > 0) stddevMat.get(0, 0)[0] else 0.0
                col.release(); meanMat.release(); stddevMat.release()
            }

            val startX = (width * 0.15).toInt()
            val endX = (width * 0.95).toInt()
            val windowSize = (width * 0.08).toInt().coerceAtLeast(10)
            var minAvgVariance = Double.MAX_VALUE
            var minCenterX = -1

            if (endX <= startX + windowSize) return -1
            for (x in startX until (endX - windowSize)) {
                var sum = 0.0
                for (w in 0 until windowSize) { sum += columnVariances[x + w] }
                val avg = sum / windowSize
                if (avg < minAvgVariance) {
                    minAvgVariance = avg
                    minCenterX = x + windowSize / 2
                }
            }

            return minCenterX
        } catch (e: Exception) {
            Log.e(TAG, "Column variance failed: ${e.message}")
            return -1
        } finally {
            src.release(); gray.release()
        }
    }
}
