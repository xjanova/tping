package com.xjanova.tping.puzzle

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

object PuzzleSolver {

    private const val TAG = "PuzzleSolver"
    private var opencvInitialized = false

    /** Brightness threshold for "dark pixel" detection (legacy) */
    private const val DARK_THRESHOLD = 70.0

    /** Enable saving debug images to /sdcard/puzzle_debug/ */
    var debugSaveImages = true
    private var debugCounter = 0

    /** Set the debug output directory (should be app cache/files dir) */
    var debugDir: File? = null

    private fun resolveDebugDir(): File? {
        // Try app-specific dir first (no permission needed), then fallback
        val dir = debugDir ?: File(Environment.getExternalStorageDirectory(), "puzzle_debug")
        if (!dir.exists()) dir.mkdirs()
        return if (dir.canWrite()) dir else null
    }

    /** Clean old debug images (keep last 3 rounds) */
    fun cleanOldDebugImages() {
        try {
            val dir = resolveDebugDir() ?: return
            val files = dir.listFiles() ?: return
            // Keep only files from last 3 debugCounter values
            val minKeep = (debugCounter - 2).coerceAtLeast(0)
            for (f in files) {
                val num = f.name.substringBefore("_").toIntOrNull() ?: continue
                if (num < minKeep) f.delete()
            }
        } catch (_: Exception) {}
    }

    /** Save a Mat as PNG for diagnosis */
    private fun saveMat(mat: Mat, name: String) {
        if (!debugSaveImages) return
        try {
            val dir = resolveDebugDir() ?: return
            val file = File(dir, "${debugCounter}_$name.png")
            val toSave = if (mat.channels() == 1) {
                val rgb = Mat()
                Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_GRAY2BGR)
                rgb
            } else if (mat.channels() == 4) {
                val bgr = Mat()
                Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
                bgr
            } else mat
            Imgcodecs.imwrite(file.absolutePath, toSave)
            if (toSave !== mat) toSave.release()
            Log.d(TAG, "Debug saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Debug save failed for $name: ${e.message}")
        }
    }

    /** Save a scan position screenshot for debugging */
    fun saveScanPosition(bitmap: Bitmap, index: Int, posX: Int) {
        saveBitmap(bitmap, "scan_pos${index}_x${posX}")
    }

    /** Save a Bitmap as PNG */
    private fun saveBitmap(bitmap: Bitmap, name: String) {
        if (!debugSaveImages) return
        try {
            val dir = resolveDebugDir() ?: return
            val file = File(dir, "${debugCounter}_$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "Debug saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Debug save failed for $name: ${e.message}")
        }
    }

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
    // Smart scan: Measure edge count to find where piece fills the gap
    // =====================================================================

    /**
     * Measure total Canny edge count in the puzzle area (above slider).
     * When the puzzle piece fills the gap, the gap's shadow edges disappear,
     * causing a measurable DIP in edge count.
     *
     * @return edge count, or -1 on error
     */
    fun measurePuzzleEdges(screenshot: Bitmap, sliderY: Int): Int {
        if (!ensureOpenCV()) return -1
        val src = Mat()
        val gray = Mat()
        try {
            val top = (sliderY - 500).coerceAtLeast(0)
            val bottom = (sliderY - 20).coerceAtLeast(top + 10).coerceAtMost(screenshot.height)
            if (bottom <= top) return -1

            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val region = gray.submat(top, bottom, 0, screenshot.width)
            val blurred = Mat()
            Imgproc.GaussianBlur(region, blurred, Size(3.0, 3.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            val count = Core.countNonZero(edges)
            edges.release(); blurred.release(); region.release()
            return count
        } catch (e: Exception) {
            Log.e(TAG, "measurePuzzleEdges failed: ${e.message}")
            return -1
        } finally {
            src.release(); gray.release()
        }
    }

    /**
     * Measure edge count in a narrow vertical strip centered at a specific X position.
     * Used during scan to detect where the piece boundary blends with the gap.
     *
     * @param centerX X center of the strip
     * @param stripWidth width of the strip in pixels
     * @return edge count in the strip, or -1 on error
     */
    fun measureEdgesAtX(screenshot: Bitmap, sliderY: Int, centerX: Int, stripWidth: Int = 80): Int {
        if (!ensureOpenCV()) return -1
        val src = Mat()
        val gray = Mat()
        try {
            val top = (sliderY - 500).coerceAtLeast(0)
            val bottom = (sliderY - 20).coerceAtLeast(top + 10).coerceAtMost(screenshot.height)
            val left = (centerX - stripWidth / 2).coerceAtLeast(0)
            val right = (centerX + stripWidth / 2).coerceAtMost(screenshot.width)
            if (bottom <= top || right <= left) return -1

            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val strip = gray.submat(top, bottom, left, right)
            val blurred = Mat()
            Imgproc.GaussianBlur(strip, blurred, Size(3.0, 3.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            val count = Core.countNonZero(edges)
            edges.release(); blurred.release(); strip.release()
            return count
        } catch (e: Exception) {
            Log.e(TAG, "measureEdgesAtX failed: ${e.message}")
            return -1
        } finally {
            src.release(); gray.release()
        }
    }

    // =====================================================================
    // White border detection: Both puzzle piece and gap have white border lines
    // When piece is IN the gap, white borders overlap → detectable signal
    // =====================================================================

    /**
     * Measure white border alignment score at a given X position.
     * Puzzle piece + gap both have white border lines on their edges.
     * When piece aligns with gap, the white borders overlap perfectly →
     * a VERTICAL column of bright pixels at the gap edges disappears.
     *
     * Higher score = MORE white border pixels visible = piece NOT in gap.
     * Lower score = fewer borders = piece covering gap = CORRECT position.
     *
     * @param screenshot current screen
     * @param sliderY Y of slider (search area above this)
     * @param centerX X to measure around (current piece position)
     * @param stripWidth width of analysis strip (default 100px)
     * @return white border score (0 = perfect, higher = more visible borders), -1 on error
     */
    fun measureWhiteBorderScore(
        screenshot: Bitmap, sliderY: Int, centerX: Int, stripWidth: Int = 100
    ): Int {
        if (!ensureOpenCV()) return -1
        val src = Mat()
        val gray = Mat()
        try {
            val top = (sliderY - 500).coerceAtLeast(0)
            val bottom = (sliderY - 20).coerceAtLeast(top + 10).coerceAtMost(screenshot.height)
            val left = (centerX - stripWidth / 2).coerceAtLeast(0)
            val right = (centerX + stripWidth / 2).coerceAtMost(screenshot.width)
            if (bottom <= top || right <= left) return -1

            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val strip = gray.submat(top, bottom, left, right)

            // Detect bright/white pixels (the white border lines)
            // White borders typically > 200 brightness
            val whiteMask = Mat()
            Imgproc.threshold(strip, whiteMask, 200.0, 255.0, Imgproc.THRESH_BINARY)

            // Focus on vertical edges using Sobel X — white borders are vertical lines
            val sobelX = Mat()
            Imgproc.Sobel(strip, sobelX, CvType.CV_16S, 1, 0)
            val absSobel = Mat()
            Core.convertScaleAbs(sobelX, absSobel)

            // Combine: bright pixels that are also on vertical edges
            val edgeMask = Mat()
            Imgproc.threshold(absSobel, edgeMask, 40.0, 255.0, Imgproc.THRESH_BINARY)

            // AND: white AND vertical edge → white border pixels
            val whiteBorder = Mat()
            Core.bitwise_and(whiteMask, edgeMask, whiteBorder)

            val score = Core.countNonZero(whiteBorder)

            whiteBorder.release(); edgeMask.release(); absSobel.release()
            sobelX.release(); whiteMask.release(); strip.release()

            return score
        } catch (e: Exception) {
            Log.e(TAG, "measureWhiteBorderScore failed: ${e.message}")
            return -1
        } finally {
            src.release(); gray.release()
        }
    }

    /**
     * Detect the gap X position by finding where white border signal peaks
     * on both LEFT and RIGHT sides of the gap.
     *
     * Scans vertical columns in the puzzle area for high-brightness vertical
     * edges (white border lines). The gap has TWO white borders: left edge and right edge.
     * Between them is the actual gap. We find these borders and return the center.
     *
     * @param screenshot current screen
     * @param sliderY Y of slider
     * @param searchStartX start scanning from this X (typically sliderX + 100)
     * @param searchEndX end scanning at this X
     * @return gap center X, or null if not found
     */
    fun findGapByWhiteBorder(
        screenshot: Bitmap, sliderY: Int,
        searchStartX: Int, searchEndX: Int
    ): Int? {
        if (!ensureOpenCV()) return null
        val src = Mat()
        val gray = Mat()
        try {
            val top = (sliderY - 500).coerceAtLeast(0)
            val bottom = (sliderY - 20).coerceAtLeast(top + 10).coerceAtMost(screenshot.height)
            if (bottom <= top) return null
            val startX = searchStartX.coerceAtLeast(0)
            val endX = searchEndX.coerceAtMost(screenshot.width)
            if (endX <= startX + 30) return null

            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val region = gray.submat(top, bottom, startX, endX)

            // Detect white pixels in the region (brightness > 200)
            val whiteMask = Mat()
            Imgproc.threshold(region, whiteMask, 200.0, 255.0, Imgproc.THRESH_BINARY)

            // Get Sobel X for vertical edges
            val sobelX = Mat()
            Imgproc.Sobel(region, sobelX, CvType.CV_16S, 1, 0)
            val absSobel = Mat()
            Core.convertScaleAbs(sobelX, absSobel)
            val edgeMask = Mat()
            Imgproc.threshold(absSobel, edgeMask, 40.0, 255.0, Imgproc.THRESH_BINARY)

            // Combine: white vertical edges
            val whiteBorder = Mat()
            Core.bitwise_and(whiteMask, edgeMask, whiteBorder)

            // Sum columns: project vertically to find where white borders are strongest
            val colSum = IntArray(endX - startX)
            val rows = whiteBorder.rows()
            for (col in 0 until whiteBorder.cols()) {
                var sum = 0
                for (row in 0 until rows) {
                    if (whiteBorder.get(row, col)[0] > 0) sum++
                }
                colSum[col] = sum
            }

            // Find peaks in colSum — these are the white border positions
            // A peak = column with significantly more white border pixels than neighbors
            val avgSum = colSum.average()
            val threshold = (avgSum * 2.5).coerceAtLeast(5.0)

            // Find clusters of peaks (border is a few pixels wide)
            val peaks = mutableListOf<Int>() // cluster centers
            var inPeak = false
            var peakStart = 0
            for (col in colSum.indices) {
                if (colSum[col] > threshold) {
                    if (!inPeak) { peakStart = col; inPeak = true }
                } else {
                    if (inPeak) {
                        peaks.add((peakStart + col - 1) / 2)
                        inPeak = false
                    }
                }
            }
            if (inPeak) peaks.add((peakStart + colSum.size - 1) / 2)

            whiteBorder.release(); edgeMask.release(); absSobel.release()
            sobelX.release(); whiteMask.release(); region.release()

            Log.d(TAG, "findGapByWhiteBorder: ${peaks.size} border peaks found, " +
                "threshold=${"%.1f".format(threshold)}, avgSum=${"%.1f".format(avgSum)}")

            if (peaks.size < 2) return null

            // Find the pair of peaks with gap-like spacing (40-120px apart)
            // This pair represents the left and right borders of the gap
            var bestPairCenter: Int? = null
            var bestPairScore = 0
            for (i in 0 until peaks.size - 1) {
                val gap = peaks[i + 1] - peaks[i]
                if (gap in 35..130) {
                    // Score = combined column sum (stronger borders = more confident)
                    val score = colSum[peaks[i]] + colSum[peaks[i + 1]]
                    if (score > bestPairScore) {
                        bestPairScore = score
                        bestPairCenter = (peaks[i] + peaks[i + 1]) / 2
                    }
                }
            }

            if (bestPairCenter != null) {
                val gapX = bestPairCenter + startX
                Log.d(TAG, "findGapByWhiteBorder: gap center at x=$gapX " +
                    "(score=$bestPairScore)")
                return gapX
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "findGapByWhiteBorder failed: ${e.message}")
            return null
        } finally {
            src.release(); gray.release()
        }
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

            // Strategy C: Darkest vertical column band detection
            // The puzzle gap/shadow is the darkest vertical strip in the image
            val resultC = findGapByDarkestColumn(searchRegion, searchTop, searchWidth, h)
            if (resultC != null) {
                Log.d(
                    TAG,
                    "Gap found via Darkest Column at (${resultC.left},${resultC.top})-(${resultC.right},${resultC.bottom})"
                )
                searchRegion.release()
                return resultC
            }

            // Strategy D: Local contrast anomaly (last resort)
            val resultD = findGapByContrastAnomaly(searchRegion, searchTop, searchWidth, h)
            if (resultD != null) {
                Log.d(
                    TAG,
                    "Gap found via Contrast Anomaly at (${resultD.left},${resultD.top})-(${resultD.right},${resultD.bottom})"
                )
                searchRegion.release()
                return resultD
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
     * Strategy C: Darkest vertical column band detection.
     * Computes per-column average brightness, then finds the contiguous band of columns
     * that is significantly darker than the overall image. The gap shadow is always the
     * darkest vertical strip because the missing piece lets the dark background show through.
     */
    private fun findGapByDarkestColumn(
        searchRegion: Mat,
        searchTop: Int,
        width: Int,
        height: Int
    ): Rect? {
        try {
            if (width < 50 || height < 20) return null

            // Skip left 10% (puzzle piece starting position)
            val startCol = (width * 0.10).toInt()

            // Compute mean brightness for each column
            val colMeans = DoubleArray(width)
            for (x in startCol until width) {
                val col = searchRegion.col(x)
                val mean = Core.mean(col).`val`[0]
                colMeans[x] = mean
                col.release()
            }

            // Global mean of the search area (excluding left 10%)
            val validMeans = colMeans.drop(startCol).filter { it > 0.0 }
            if (validMeans.isEmpty()) return null
            val globalMean = validMeans.average()
            val globalStd = Math.sqrt(validMeans.map { (it - globalMean) * (it - globalMean) }.average())

            // Dark threshold: columns darker than mean - 0.8*std
            val darkThresh = globalMean - globalStd * 0.8
            if (darkThresh <= 0) return null

            // Find contiguous bands of dark columns using sliding window
            // Typical puzzle gap is 40-120px wide
            val minGapWidth = 25
            val maxGapWidth = 150

            var bestBandStart = -1
            var bestBandEnd = -1
            var bestBandDarkness = Double.MAX_VALUE

            var bandStart = -1
            for (x in startCol until width) {
                if (colMeans[x] < darkThresh) {
                    if (bandStart < 0) bandStart = x
                } else {
                    if (bandStart >= 0) {
                        val bandWidth = x - bandStart
                        if (bandWidth in minGapWidth..maxGapWidth) {
                            // Average darkness of this band
                            val avgDark = colMeans.slice(bandStart until x).average()
                            if (avgDark < bestBandDarkness) {
                                bestBandDarkness = avgDark
                                bestBandStart = bandStart
                                bestBandEnd = x
                            }
                        }
                        bandStart = -1
                    }
                }
            }
            // Close any trailing band
            if (bandStart >= 0) {
                val bandWidth = width - bandStart
                if (bandWidth in minGapWidth..maxGapWidth) {
                    val avgDark = colMeans.slice(bandStart until width).average()
                    if (avgDark < bestBandDarkness) {
                        bestBandStart = bandStart
                        bestBandEnd = width
                    }
                }
            }

            if (bestBandStart < 0) {
                // Fallback: find the single darkest window of minGapWidth columns
                for (x in startCol..(width - minGapWidth)) {
                    val windowAvg = colMeans.slice(x until x + minGapWidth).average()
                    if (windowAvg < bestBandDarkness) {
                        bestBandDarkness = windowAvg
                        bestBandStart = x
                        bestBandEnd = x + minGapWidth
                    }
                }
            }

            if (bestBandStart < 0) return null

            // The gap must be significantly darker than global mean
            val darkRatio = bestBandDarkness / globalMean
            Log.d(TAG, "  [DarkCol] band=$bestBandStart-$bestBandEnd w=${bestBandEnd - bestBandStart} " +
                "darkAvg=${"%.1f".format(bestBandDarkness)} globalMean=${"%.1f".format(globalMean)} " +
                "ratio=${"%.2f".format(darkRatio)} thresh=${"%.1f".format(darkThresh)}")

            if (darkRatio > 0.92) {
                Log.d(TAG, "  [DarkCol] band not dark enough (ratio=${"%.2f".format(darkRatio)})")
                return null
            }

            // Now find vertical extent: scan rows in the band to find top/bottom of dark area
            val bandCenterX = (bestBandStart + bestBandEnd) / 2
            val bandW = bestBandEnd - bestBandStart
            var gapTop = 0
            var gapBottom = height
            val rowThreshold = globalMean - globalStd * 0.5

            for (y in 0 until height) {
                val roi = searchRegion.submat(y, y + 1, bestBandStart, bestBandEnd)
                val rowMean = Core.mean(roi).`val`[0]
                roi.release()
                if (rowMean < rowThreshold) {
                    gapTop = y
                    break
                }
            }
            for (y in height - 1 downTo 0) {
                val roi = searchRegion.submat(y, y + 1, bestBandStart, bestBandEnd)
                val rowMean = Core.mean(roi).`val`[0]
                roi.release()
                if (rowMean < rowThreshold) {
                    gapBottom = y + 1
                    break
                }
            }

            return Rect(
                bestBandStart,
                gapTop + searchTop,
                bestBandEnd,
                gapBottom + searchTop
            )
        } catch (e: Exception) {
            Log.e(TAG, "findGapByDarkestColumn failed: ${e.message}")
            return null
        }
    }

    /**
     * Strategy D: Local contrast anomaly detection.
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
    // Diff + Template Matching: Extract piece shape, find gap by edge match
    // =====================================================================

    /**
     * Find gap position by comparing before/after screenshots + edge template matching.
     *
     * 1. absdiff(before, after) → extract piece shape from moved pixels
     * 2. Canny edges on piece template + background
     * 3. matchTemplate(TM_CCOEFF_NORMED) → find gap
     *
     * @param beforeBitmap Screenshot BEFORE moving slider
     * @param afterBitmap  Screenshot AFTER moving slider ~60px right
     * @param sliderY      Y coordinate of slider (gap is above)
     * @param sliderX      X of slider start (exclude from search)
     * @return Gap center X in screen coords, or null if detection failed
     */
    fun findGapByDiffMatch(
        beforeBitmap: Bitmap,
        afterBitmap: Bitmap,
        sliderY: Int,
        sliderX: Float
    ): Int? {
        if (!ensureOpenCV()) return null

        debugCounter++
        saveBitmap(beforeBitmap, "a_before")
        saveBitmap(afterBitmap, "b_after")

        val beforeMat = Mat()
        val afterMat = Mat()
        val beforeGray = Mat()
        val afterGray = Mat()
        val diff = Mat()
        val binary = Mat()

        try {
            Utils.bitmapToMat(beforeBitmap, beforeMat)
            Utils.bitmapToMat(afterBitmap, afterMat)
            Imgproc.cvtColor(beforeMat, beforeGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(afterMat, afterGray, Imgproc.COLOR_RGBA2GRAY)

            // === Step 1: absdiff → find changed pixels ===
            Core.absdiff(beforeGray, afterGray, diff)
            saveMat(diff, "c_diff_raw")

            // === Step 2: Threshold + morphology → clean binary mask ===
            Imgproc.threshold(diff, binary, 25.0, 255.0, Imgproc.THRESH_BINARY)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(binary, binary, kernel)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            saveMat(binary, "d_diff_binary")

            // === Step 3: Find contours in puzzle area ===
            val searchTop = (sliderY - 600).coerceAtLeast(0)
            val searchBottom = (sliderY - 20).coerceAtLeast(searchTop + 10)
                .coerceAtMost(binary.rows())
            if (searchBottom <= searchTop) return null

            val puzzleMask = binary.submat(searchTop, searchBottom, 0, binary.cols())
            saveMat(puzzleMask, "e_puzzle_mask")

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                puzzleMask.clone(), contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            hierarchy.release()

            // Draw all contours on a debug image
            if (debugSaveImages && contours.isNotEmpty()) {
                val contourViz = Mat.zeros(puzzleMask.size(), CvType.CV_8UC3)
                for (i in contours.indices) {
                    val area = Imgproc.contourArea(contours[i])
                    val color = if (area > 500) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 0.0, 255.0)
                    Imgproc.drawContours(contourViz, contours, i, color, 2)
                    val rect = Imgproc.boundingRect(contours[i])
                    Imgproc.putText(contourViz, "a=${area.toInt()}",
                        Point(rect.x.toDouble(), rect.y.toDouble() - 5),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, Scalar(255.0, 255.0, 255.0), 1)
                }
                saveMat(contourViz, "f_contours")
                contourViz.release()
            }
            Log.d(TAG, "findGapByDiffMatch: ${contours.size} contours in puzzle area")

            // === Step 4: Find the piece contour (rightmost large contour) ===
            var bestContour: MatOfPoint? = null
            var bestRect: org.opencv.core.Rect? = null
            var bestRightEdge = 0

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area < 500) continue  // too small
                val rect = Imgproc.boundingRect(c)
                if (rect.width < 15 || rect.height < 15) continue  // too thin
                val rightEdge = rect.x + rect.width
                if (rightEdge > bestRightEdge) {
                    bestRightEdge = rightEdge
                    bestContour = c
                    bestRect = rect
                }
            }

            if (bestContour == null || bestRect == null) {
                Log.d(TAG, "findGapByDiffMatch: no piece contour found in diff")
                puzzleMask.release()
                return null
            }

            Log.d(TAG, "findGapByDiffMatch: piece contour at " +
                "(${bestRect.x},${bestRect.y}) ${bestRect.width}x${bestRect.height}")

            // === Step 5: Extract piece template from AFTER image ===
            val pieceTop = (searchTop + bestRect.y).coerceAtLeast(0)
            val pieceBottom = (pieceTop + bestRect.height).coerceAtMost(afterGray.rows())
            val pieceLeft = bestRect.x.coerceAtLeast(0)
            val pieceRight = (pieceLeft + bestRect.width).coerceAtMost(afterGray.cols())

            if (pieceRight - pieceLeft < 15 || pieceBottom - pieceTop < 15) {
                puzzleMask.release()
                return null
            }

            val pieceCrop = afterGray.submat(pieceTop, pieceBottom, pieceLeft, pieceRight)
            saveMat(pieceCrop, "g_piece_crop")

            // === Step 6: Canny edges on piece ===
            val pieceEdges = Mat()
            Imgproc.Canny(pieceCrop, pieceEdges, 100.0, 200.0)
            saveMat(pieceEdges, "h_piece_edges")

            // Check piece has enough edges
            val pieceEdgeCount = Core.countNonZero(pieceEdges)
            if (pieceEdgeCount < 20) {
                Log.d(TAG, "findGapByDiffMatch: piece edges too few ($pieceEdgeCount)")
                pieceEdges.release()
                pieceCrop.release()
                puzzleMask.release()
                return null
            }

            // === Step 7: Canny edges on background (puzzle area, right of slider) ===
            val bgSearchLeft = (sliderX + 100f).toInt().coerceAtLeast(0)
                .coerceAtMost(beforeGray.cols() - bestRect.width - 1)
            val bgSearchRight = beforeGray.cols()

            if (bgSearchRight - bgSearchLeft < bestRect.width) {
                pieceEdges.release()
                pieceCrop.release()
                puzzleMask.release()
                return null
            }

            val bgArea = beforeGray.submat(searchTop, searchBottom, bgSearchLeft, bgSearchRight)
            val bgBlurred = Mat()
            Imgproc.GaussianBlur(bgArea, bgBlurred, Size(3.0, 3.0), 0.0)
            val bgEdges = Mat()
            Imgproc.Canny(bgBlurred, bgEdges, 100.0, 200.0)
            saveMat(bgEdges, "i_bg_edges")

            // Check that template is smaller than search area
            if (pieceEdges.rows() > bgEdges.rows() || pieceEdges.cols() > bgEdges.cols()) {
                Log.d(TAG, "findGapByDiffMatch: template larger than search area")
                bgEdges.release()
                bgBlurred.release()
                bgArea.release()
                pieceEdges.release()
                pieceCrop.release()
                puzzleMask.release()
                return null
            }

            // === Step 8: Template matching ===
            val result = Mat()
            Imgproc.matchTemplate(bgEdges, pieceEdges, result, Imgproc.TM_CCOEFF_NORMED)
            val minMaxLoc = Core.minMaxLoc(result)

            val confidence = minMaxLoc.maxVal
            val matchX = minMaxLoc.maxLoc.x
            val matchY = minMaxLoc.maxLoc.y

            Log.d(TAG, "findGapByDiffMatch: match at ($matchX,$matchY) " +
                "confidence=${"%.3f".format(confidence)}")

            // Save result heatmap + annotated background
            if (debugSaveImages) {
                // Normalize result to 0-255 for visualization
                val resultNorm = Mat()
                Core.normalize(result, resultNorm, 0.0, 255.0, Core.NORM_MINMAX)
                resultNorm.convertTo(resultNorm, CvType.CV_8U)
                val heatmap = Mat()
                Imgproc.applyColorMap(resultNorm, heatmap, Imgproc.COLORMAP_JET)
                saveMat(heatmap, "j_match_heatmap")
                heatmap.release()
                resultNorm.release()

                // Draw match position on background
                val bgViz = Mat()
                Imgproc.cvtColor(bgEdges, bgViz, Imgproc.COLOR_GRAY2BGR)
                Imgproc.rectangle(bgViz,
                    Point(matchX, matchY),
                    Point(matchX + bestRect.width, matchY + bestRect.height),
                    Scalar(0.0, 255.0, 0.0), 2)
                Imgproc.putText(bgViz, "conf=${"%.2f".format(confidence)}",
                    Point(matchX, matchY - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, Scalar(0.0, 255.0, 0.0), 1)
                saveMat(bgViz, "k_match_on_bg")
                bgViz.release()
            }

            result.release()
            bgEdges.release()
            bgBlurred.release()
            bgArea.release()
            pieceEdges.release()
            pieceCrop.release()
            puzzleMask.release()

            if (confidence < 0.25) {
                Log.d(TAG, "findGapByDiffMatch: confidence too low (${"%.3f".format(confidence)})")
                return null
            }

            // Convert to screen X: matchX is relative to bgSearchLeft
            val gapCenterX = bgSearchLeft + matchX.toInt() + bestRect.width / 2
            Log.d(TAG, "findGapByDiffMatch: gap center X = $gapCenterX " +
                "(bgSearchLeft=$bgSearchLeft, matchX=${matchX.toInt()}, " +
                "pieceW=${bestRect.width}, confidence=${"%.3f".format(confidence)})")

            return gapCenterX

        } catch (e: Exception) {
            Log.e(TAG, "findGapByDiffMatch failed: ${e.message}")
            return null
        } finally {
            beforeMat.release()
            afterMat.release()
            beforeGray.release()
            afterGray.release()
            diff.release()
            binary.release()
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
