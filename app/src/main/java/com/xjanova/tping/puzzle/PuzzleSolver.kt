package com.xjanova.tping.puzzle

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object PuzzleSolver {

    private const val TAG = "PuzzleSolver"
    private var opencvInitialized = false

    fun ensureOpenCV(): Boolean {
        if (!opencvInitialized) {
            opencvInitialized = OpenCVLoader.initLocal()
            if (!opencvInitialized) {
                Log.e(TAG, "OpenCV initialization failed")
            }
        }
        return opencvInitialized
    }

    /**
     * Analyze a puzzle image to find the gap's X offset from the left edge.
     * Returns the pixel X offset where the gap center is, or -1 on failure.
     */
    fun findGapOffset(puzzleBitmap: Bitmap, method: String = "edge"): Int {
        if (!ensureOpenCV()) return -1

        return when (method) {
            "template" -> findGapByColumnVariance(puzzleBitmap)
            else -> findGapByEdgeDetection(puzzleBitmap)
        }
    }

    /**
     * Edge detection approach:
     * 1. Convert to grayscale
     * 2. Gaussian blur to reduce noise
     * 3. Canny edge detection
     * 4. Dilate edges to close gaps in contours
     * 5. Find contours
     * 6. Filter by area, aspect ratio, and position
     * 7. Return X center of the best matching contour
     */
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
            val minArea = imageArea * 0.01
            val maxArea = imageArea * 0.15

            var bestX = -1
            var bestArea = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea || area > maxArea) {
                    contour.release()
                    continue
                }

                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toFloat() / rect.height
                if (aspectRatio < 0.4 || aspectRatio > 2.5) {
                    contour.release()
                    continue
                }

                // Gap should not be at the very left (that's the floating piece)
                if (rect.x < bitmap.width * 0.15) {
                    contour.release()
                    continue
                }

                if (area > bestArea) {
                    bestArea = area
                    bestX = rect.x + rect.width / 2
                }
                contour.release()
            }

            Log.d(TAG, "Edge detection result: gapX=$bestX, imageW=${bitmap.width}")
            return bestX
        } catch (e: Exception) {
            Log.e(TAG, "Edge detection failed: ${e.message}")
            return -1
        } finally {
            src.release()
            gray.release()
            blurred.release()
            edges.release()
            dilated.release()
            hierarchy.release()
        }
    }

    /**
     * Fallback: Column variance method.
     * The gap region has different brightness/variance than surrounding background.
     * Scan columns and detect the region with the biggest brightness change.
     */
    private fun findGapByColumnVariance(bitmap: Bitmap): Int {
        val src = Mat()
        val gray = Mat()

        try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val width = gray.cols()
            val height = gray.rows()
            val columnVariances = DoubleArray(width)

            for (x in 0 until width) {
                val col = gray.col(x)
                val meanMat = Mat()
                val stddevMat = Mat()
                Core.meanStdDev(col, meanMat, stddevMat)
                columnVariances[x] = if (stddevMat.rows() > 0 && stddevMat.cols() > 0)
                    stddevMat.get(0, 0)[0] else 0.0
                col.release()
                meanMat.release()
                stddevMat.release()
            }

            // Find the region with lowest variance (shadow/gap is more uniform)
            // Skip the leftmost 15% (floating piece area)
            val startX = (width * 0.15).toInt()
            val endX = (width * 0.95).toInt()

            // Use a sliding window to find the dip in variance
            val windowSize = (width * 0.08).toInt().coerceAtLeast(10)
            var minAvgVariance = Double.MAX_VALUE
            var minCenterX = -1

            for (x in startX until (endX - windowSize)) {
                var sum = 0.0
                for (w in 0 until windowSize) {
                    sum += columnVariances[x + w]
                }
                val avg = sum / windowSize
                if (avg < minAvgVariance) {
                    minAvgVariance = avg
                    minCenterX = x + windowSize / 2
                }
            }

            Log.d(TAG, "Column variance result: gapX=$minCenterX, imageW=$width")
            return minCenterX
        } catch (e: Exception) {
            Log.e(TAG, "Column variance failed: ${e.message}")
            return -1
        } finally {
            src.release()
            gray.release()
        }
    }
}
