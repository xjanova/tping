package com.xjanova.tping.puzzle

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object PuzzleSolver {

    private const val TAG = "PuzzleSolver"
    private var opencvInitialized = false

    /** Brightness threshold for "dark pixel" detection (legacy) */
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

    // =====================================================================
    // Primary API: Multi-strategy gap detection + edge-based tracking
    // =====================================================================

    /**
     * Auto-detect the gap region using multiple strategies.
     * Tries: 1) Multi-threshold dark  2) Edge contours  3) Contrast anomaly.
     *
     * Works for any overlay type: dark, semi-transparent, triangle, circle, star, jigsaw.
     *
     * @param screenshot Full screen bitmap
     * @param sliderY Y coordinate of slider button (search above this)
     * @return Rect of the gap region in screen coordinates, or null if not found
     */
    fun findGapRegion(screenshot: Bitmap, sliderY: Int): Rect? {
        if (!ensureOpenCV()) return null

        val searchTop = (sliderY - 600).coerceAtLeast(0)
        val searchBottom = (sliderY - 20).coerceAtLeast(searchTop + 10)
            .coerceAtMost(screenshot.height)
        val searchWidth = screenshot.width

        if (searchBottom <= searchTop) {
            Log.w(TAG, "Invalid search region: top=$searchTop bottom=$searchBottom")
            return null
        }

        val src = Mat()
        val gray = Mat()

        try {
            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val searchRegion = gray.submat(searchTop, searchBottom, 0, searchWidth)
            val h = searchBottom - searchTop

            Log.d(TAG, "findGapRegion: searchRegion ${searchWidth}x$h, top=$searchTop")

            // Strategy A: Multi-threshold dark detection (best for semi-transparent overlays)
            val resultA = findGapByMultiThreshold(searchRegion, searchTop, searchWidth, h)
            if (resultA != null) {
                Log.d(
                    TAG,
                    "Gap found via Multi-Threshold at (${resultA.left},${resultA.top})-(${resultA.right},${resultA.bottom})"
                )
                searchRegion.release()
                return resultA
            }

            // Strategy B: Edge-based contour detection (works for any overlay type)
            val resultB = findGapByEdgeContours(searchRegion, searchTop, searchWidth, h)
            if (resultB != null) {
                Log.d(
                    TAG,
                    "Gap found via Edge Contours at (${resultB.left},${resultB.top})-(${resultB.right},${resultB.bottom})"
                )
                searchRegion.release()
                return resultB
            }

            // Strategy C: Local contrast anomaly (last resort)
            val resultC = findGapByContrastAnomaly(searchRegion, searchTop, searchWidth, h)
            if (resultC != null) {
                Log.d(
                    TAG,
                    "Gap found via Contrast Anomaly at (${resultC.left},${resultC.top})-(${resultC.right},${resultC.bottom})"
                )
                searchRegion.release()
                return resultC
            }

            searchRegion.release()
            Log.d(TAG, "findGapRegion: no gap found by any strategy")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "findGapRegion failed: ${e.message}")
            return null
        } finally {
            src.release()
            gray.release()
        }
    }

    /**
     * Strategy A: Multi-threshold dark detection.
     * Tries progressively higher brightness thresholds (70 -> 100 -> 130).
     * Catches semi-transparent dark overlays that a single low threshold misses.
     */
    private fun findGapByMultiThreshold(
        searchRegion: Mat,
        searchTop: Int,
        width: Int,
        height: Int
    ): Rect? {
        val thresholds = doubleArrayOf(70.0, 100.0, 130.0)

        for (threshold in thresholds) {
            val binary = Mat()
            val morphed = Mat()
            val hierarchy = Mat()

            try {
                Imgproc.threshold(
                    searchRegion,
                    binary,
                    threshold,
                    255.0,
                    Imgproc.THRESH_BINARY_INV
                )

                val kernel =
                    Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
                Imgproc.morphologyEx(binary, morphed, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(
                    morphed,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )

                val searchArea = width.toDouble() * height
                val minArea = searchArea * 0.003 // 0.3%
                val maxArea = searchArea * 0.25 // 25%

                var bestRect: Rect? = null
                var bestScore = 0.0

                Log.d(
                    TAG,
                    "  [Thresh=${"%.0f".format(threshold)}] ${contours.size} contours"
                )

                for (contour in contours) {
                    val area = Imgproc.contourArea(contour)
                    if (area < minArea || area > maxArea) {
                        contour.release(); continue
                    }

                    val rect = Imgproc.boundingRect(contour)
                    if (rect.height <= 5 || rect.width <= 5) {
                        contour.release(); continue
                    }

                    // Skip left 8% — puzzle piece is there
                    if (rect.x < width * 0.08) {
                        contour.release(); continue
                    }

                    // Compactness: 4pi * area / perimeter^2
                    // Circle = 1.0, Square ~ 0.78, Triangle ~ 0.60
                    val pts2f = MatOfPoint2f(*contour.toArray())
                    val perimeter = Imgproc.arcLength(pts2f, true)
                    pts2f.release()
                    val compactness =
                        if (perimeter > 0) (4 * Math.PI * area) / (perimeter * perimeter) else 0.0

                    // Accept shapes with compactness > 0.15 (triangle, circle, star, jigsaw)
                    if (compactness < 0.15) {
                        contour.release(); continue
                    }

                    // Score: prefer right side + larger + more compact
                    val rightBias = rect.x.toDouble() / width
                    val score = area * (0.3 + rightBias * 0.4 + compactness * 0.3)

                    Log.d(
                        TAG,
                        "  [Thresh=${"%.0f".format(threshold)}] x=${rect.x} y=${rect.y} " +
                            "w=${rect.width} h=${rect.height} area=${"%.0f".format(area)} " +
                            "compact=${"%.2f".format(compactness)} score=${"%.0f".format(score)}"
                    )

                    if (score > bestScore) {
                        bestScore = score
                        bestRect = Rect(
                            rect.x,
                            rect.y + searchTop,
                            rect.x + rect.width,
                            rect.y + rect.height + searchTop
                        )
                    }
                    contour.release()
                }

                if (bestRect != null) return bestRect
            } catch (e: Exception) {
                Log.e(TAG, "  [Thresh=$threshold] failed: ${e.message}")
            } finally {
                binary.release()
                morphed.release()
                hierarchy.release()
            }
        }
        return null
    }

    /**
     * Strategy B: Edge-based contour detection.
     * Uses Canny edges -> morphological closing -> contour finding.
     * Works for any overlay type because edges always form at overlay boundaries.
     */
    private fun findGapByEdgeContours(
        searchRegion: Mat,
        searchTop: Int,
        width: Int,
        height: Int
    ): Rect? {
        val blurred = Mat()
        val edges = Mat()
        val closed = Mat()
        val hierarchy = Mat()

        try {
            Imgproc.GaussianBlur(searchRegion, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 30.0, 100.0)

            // Dilate and close to form complete shapes from edge fragments
            val dilateK =
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(edges, closed, dilateK)
            dilateK.release()

            val closeK =
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
            Imgproc.morphologyEx(closed, closed, Imgproc.MORPH_CLOSE, closeK)
            closeK.release()

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                closed,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val searchArea = width.toDouble() * height
            val minArea = searchArea * 0.003
            val maxArea = searchArea * 0.25

            var bestRect: Rect? = null
            var bestScore = 0.0

            Log.d(TAG, "  [Edge] ${contours.size} contours")

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea || area > maxArea) {
                    contour.release(); continue
                }

                val rect = Imgproc.boundingRect(contour)
                if (rect.height <= 5 || rect.width <= 5) {
                    contour.release(); continue
                }
                if (rect.x < width * 0.08) {
                    contour.release(); continue
                }

                val pts2f = MatOfPoint2f(*contour.toArray())
                val perimeter = Imgproc.arcLength(pts2f, true)
                pts2f.release()
                val compactness =
                    if (perimeter > 0) (4 * Math.PI * area) / (perimeter * perimeter) else 0.0
                if (compactness < 0.15) {
                    contour.release(); continue
                }

                val rightBias = rect.x.toDouble() / width
                val score = area * (0.3 + rightBias * 0.4 + compactness * 0.3)

                Log.d(
                    TAG,
                    "  [Edge] x=${rect.x} y=${rect.y} w=${rect.width} h=${rect.height} " +
                        "area=${"%.0f".format(area)} compact=${"%.2f".format(compactness)} " +
                        "score=${"%.0f".format(score)}"
                )

                if (score > bestScore) {
                    bestScore = score
                    bestRect = Rect(
                        rect.x,
                        rect.y + searchTop,
                        rect.x + rect.width,
                        rect.y + rect.height + searchTop
                    )
                }
                contour.release()
            }

            return bestRect
        } catch (e: Exception) {
            Log.e(TAG, "findGapByEdgeContours failed: ${e.message}")
            return null
        } finally {
            blurred.release()
            edges.release()
            closed.release()
            hierarchy.release()
        }
    }

    /**
     * Strategy C: Local contrast anomaly detection.
     * Divides the search region into blocks and finds the area with notably lower brightness
     * compared to the global mean. Works when the overlay changes local brightness.
     */
    private fun findGapByContrastAnomaly(
        searchRegion: Mat,
        searchTop: Int,
        width: Int,
        height: Int
    ): Rect? {
        try {
            val blockSize = 40
            val cols = width / blockSize
            val rows = height / blockSize

            if (cols < 3 || rows < 3) return null

            // Global statistics
            val gMeanMat = MatOfDouble()
            val gStdMat = MatOfDouble()
            Core.meanStdDev(searchRegion, gMeanMat, gStdMat)
            val gMean = gMeanMat.get(0, 0)[0]
            val gStd = gStdMat.get(0, 0)[0]
            gMeanMat.release()
            gStdMat.release()

            // Block means
            val blockMeans = Array(rows) { DoubleArray(cols) }
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val roi = searchRegion.submat(
                        r * blockSize,
                        (r + 1) * blockSize,
                        c * blockSize,
                        (c + 1) * blockSize
                    )
                    blockMeans[r][c] = Core.mean(roi).`val`[0]
                    roi.release()
                }
            }

            // Blocks darker than mean - 1 stddev are anomalous
            val darkThreshold = gMean - gStd * 1.0
            val startCol = (cols * 0.08).toInt().coerceAtLeast(1)

            var bestRect: Rect? = null
            var bestDarkRatio = 0.0

            // Sliding window of various sizes to find dark cluster
            for (wH in 2..6) {
                for (wW in 2..6) {
                    if (wH > rows || wW > cols) continue
                    for (r in 0..(rows - wH)) {
                        for (c in startCol..(cols - wW)) {
                            var darkCount = 0
                            val totalBlocks = wH * wW
                            for (dr in 0 until wH) {
                                for (dc in 0 until wW) {
                                    if (blockMeans[r + dr][c + dc] < darkThreshold) {
                                        darkCount++
                                    }
                                }
                            }
                            val ratio = darkCount.toDouble() / totalBlocks
                            // At least 40% of blocks should be dark
                            if (ratio > 0.4 && ratio > bestDarkRatio) {
                                bestDarkRatio = ratio
                                bestRect = Rect(
                                    c * blockSize,
                                    r * blockSize + searchTop,
                                    (c + wW) * blockSize,
                                    (r + wH) * blockSize + searchTop
                                )
                            }
                        }
                    }
                }
            }

            if (bestRect != null) {
                Log.d(
                    TAG,
                    "  [Contrast] anomaly found: ratio=${"%.2f".format(bestDarkRatio)}"
                )
            }
            return bestRect
        } catch (e: Exception) {
            Log.e(TAG, "findGapByContrastAnomaly failed: ${e.message}")
            return null
        }
    }

    // =====================================================================
    // Tracking: Edge strength for fill detection during sliding
    // =====================================================================

    /**
     * Measure edge strength in a specific region using Canny edge detection.
     * Returns the count of edge pixels.
     *
     * When the puzzle piece slides into the gap, the overlay boundary edges disappear,
     * causing the count to drop significantly. More reliable than dark pixel counting
     * for semi-transparent or non-black overlays.
     *
     * @param bitmap Full screen bitmap
     * @param region The region to measure (screen coordinates)
     * @return Number of edge pixels, or -1 on error
     */
    fun measureEdgeStrength(bitmap: Bitmap, region: Rect): Int {
        if (!ensureOpenCV()) return -1

        val src = Mat()
        val gray = Mat()

        try {
            val left = region.left.coerceIn(0, bitmap.width - 1)
            val top = region.top.coerceIn(0, bitmap.height - 1)
            val right = region.right.coerceIn(left + 1, bitmap.width)
            val bottom = region.bottom.coerceIn(top + 1, bitmap.height)

            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val roi = gray.submat(top, bottom, left, right)
            val blurred = Mat()
            val edges = Mat()

            Imgproc.GaussianBlur(roi, blurred, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(blurred, edges, 30.0, 100.0)
            val edgeCount = Core.countNonZero(edges)

            blurred.release()
            edges.release()
            roi.release()

            return edgeCount
        } catch (e: Exception) {
            Log.e(TAG, "measureEdgeStrength failed: ${e.message}")
            return -1
        } finally {
            src.release()
            gray.release()
        }
    }

    // =====================================================================
    // Backward compatibility wrappers
    // =====================================================================

    /**
     * Backward-compatible wrapper. Now delegates to findGapRegion() with multi-strategy detection.
     */
    fun findDarkRegion(screenshot: Bitmap, sliderY: Int): Rect? {
        return findGapRegion(screenshot, sliderY)
    }

    /**
     * Measure "darkness" of a region (dark pixels below threshold 70).
     * Kept for backward compatibility and as secondary signal.
     */
    fun measureDarkness(bitmap: Bitmap, region: Rect): Int {
        if (!ensureOpenCV()) return -1

        val src = Mat()
        val gray = Mat()

        try {
            val left = region.left.coerceIn(0, bitmap.width - 1)
            val top = region.top.coerceIn(0, bitmap.height - 1)
            val right = region.right.coerceIn(left + 1, bitmap.width)
            val bottom = region.bottom.coerceIn(top + 1, bitmap.height)

            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            val roi = gray.submat(top, bottom, left, right)
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

                val squareness =
                    1.0 - Math.abs(1.0 - aspectRatio.toDouble()).coerceAtMost(1.0)
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
                columnVariances[x] =
                    if (stddevMat.rows() > 0) stddevMat.get(0, 0)[0] else 0.0
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
