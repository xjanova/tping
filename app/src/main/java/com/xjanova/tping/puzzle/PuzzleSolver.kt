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
    // v1.2.69 PRIMARY: Detect gap from STATIC screenshot BEFORE dragging
    // Proven approach: Sobel edges + column analysis + white border peaks
    // =====================================================================

    /**
     * PRIMARY gap detection — call BEFORE touching the slider.
     * Combines multiple signals to find the gap X position from a static screenshot:
     *   1. Sobel gradient (X+Y) → strong edges at gap boundaries
     *   2. Column brightness analysis → gap is darker than surroundings
     *   3. White border peak detection → vertical bright lines at gap edges
     *
     * Returns the gap CENTER X in screen coordinates, or null if detection fails.
     */
    fun detectGapFromStatic(
        screenshot: Bitmap, sliderY: Int, sliderX: Float
    ): Int? {
        if (!ensureOpenCV()) return null
        val src = Mat()
        val gray = Mat()
        try {
            val top = (sliderY - 500).coerceAtLeast(0)
            val bottom = (sliderY - 20).coerceAtLeast(top + 10).coerceAtMost(screenshot.height)
            if (bottom <= top) return null
            // Search right of the piece starting position (skip left 15% where piece sits)
            val searchLeft = (sliderX + 80f).toInt().coerceAtLeast(0)
            val searchRight = screenshot.width
            if (searchRight - searchLeft < 50) return null

            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val region = gray.submat(top, bottom, searchLeft, searchRight)
            val regionW = searchRight - searchLeft
            val regionH = bottom - top

            // === Signal 1: Sobel gradient magnitude per column ===
            // Gap boundaries create strong vertical edges (Sobel X) and
            // the shadow inside has low gradient → column dip pattern
            val blurred = Mat()
            Imgproc.GaussianBlur(region, blurred, Size(3.0, 3.0), 0.0)
            val gradX = Mat(); val gradY = Mat()
            Imgproc.Sobel(blurred, gradX, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(blurred, gradY, CvType.CV_16S, 0, 1)
            val absGradX = Mat(); val absGradY = Mat()
            Core.convertScaleAbs(gradX, absGradX)
            Core.convertScaleAbs(gradY, absGradY)
            val grad = Mat()
            Core.addWeighted(absGradX, 0.5, absGradY, 0.5, 0.0, grad)

            // Column gradient sums
            val gradColSum = DoubleArray(regionW)
            for (col in 0 until regionW) {
                val c = grad.col(col)
                gradColSum[col] = Core.sumElems(c).`val`[0]
                c.release()
            }

            // === Signal 2: Column brightness (darker = more likely gap shadow) ===
            val brightColSum = DoubleArray(regionW)
            for (col in 0 until regionW) {
                val c = region.col(col)
                brightColSum[col] = Core.mean(c).`val`[0]
                c.release()
            }
            val globalBright = brightColSum.average()

            // === Signal 3: White border detection (brightness > 200 + vertical edge) ===
            val whiteMask = Mat()
            Imgproc.threshold(region, whiteMask, 200.0, 255.0, Imgproc.THRESH_BINARY)
            val edgeMask = Mat()
            Imgproc.threshold(absGradX, edgeMask, 40.0, 255.0, Imgproc.THRESH_BINARY)
            val whiteBorder = Mat()
            Core.bitwise_and(whiteMask, edgeMask, whiteBorder)
            val wbColSum = DoubleArray(regionW)
            for (col in 0 until regionW) {
                val c = whiteBorder.col(col)
                wbColSum[col] = Core.sumElems(c).`val`[0] / 255.0 // count of white pixels
                c.release()
            }

            // Release intermediate
            whiteBorder.release(); edgeMask.release(); whiteMask.release()
            grad.release(); absGradY.release(); absGradX.release()
            gradY.release(); gradX.release(); blurred.release(); region.release()

            // === Combine signals to find gap ===
            // Method A: Find band with highest LOCAL CONTRAST (darker than neighbors)
            // NOT globally darkest — handles images with naturally dark regions
            var bestDarkX: Int? = null
            var bestLocalContrast = 0.0
            for (w in 40..80 step 10) {
                if (w * 3 > regionW) break  // need room for left+center+right windows
                for (x in w..(regionW - w * 2)) {
                    val windowAvg = brightColSum.slice(x until x + w).average()
                    val leftAvg = brightColSum.slice((x - w) until x).average()
                    val rightEnd = (x + w * 2).coerceAtMost(regionW)
                    val rightAvg = brightColSum.slice((x + w) until rightEnd).average()
                    // Local contrast: how much darker than both neighbors
                    val localContrast = ((leftAvg + rightAvg) / 2.0) - windowAvg
                    if (localContrast > bestLocalContrast) {
                        bestLocalContrast = localContrast
                        bestDarkX = x + w / 2
                    }
                }
            }
            // Adaptive threshold: use relative contrast for white/bright images
            val minDarkContrast = (globalBright * 0.012).coerceIn(1.5, 5.0)
            val darkGapX = if (bestLocalContrast > minDarkContrast && bestDarkX != null)
                bestDarkX + searchLeft else null

            // Method B: Find white border peak pairs (gap has LEFT and RIGHT white borders)
            val wbAvg = wbColSum.average()
            val wbThreshold = (wbAvg * 3.0).coerceAtLeast(3.0)
            val wbPeaks = mutableListOf<Int>()
            var inPeak = false; var peakStart = 0
            for (col in wbColSum.indices) {
                if (wbColSum[col] > wbThreshold) {
                    if (!inPeak) { peakStart = col; inPeak = true }
                } else {
                    if (inPeak) { wbPeaks.add((peakStart + col - 1) / 2); inPeak = false }
                }
            }
            if (inPeak) wbPeaks.add((peakStart + wbColSum.size - 1) / 2)

            var wbGapX: Int? = null
            var bestWbScore = 0.0
            for (i in 0 until wbPeaks.size - 1) {
                val gap = wbPeaks[i + 1] - wbPeaks[i]
                if (gap in 30..130) {
                    val score = wbColSum[wbPeaks[i]] + wbColSum[wbPeaks[i + 1]]
                    if (score > bestWbScore) {
                        bestWbScore = score
                        wbGapX = (wbPeaks[i] + wbPeaks[i + 1]) / 2 + searchLeft
                    }
                }
            }

            // Method C: Find high-gradient peaks (gap edges have strong Sobel response)
            val gradAvg = gradColSum.average()
            val gradThreshold = gradAvg * 1.8
            val gradPeaks = mutableListOf<Int>()
            inPeak = false; peakStart = 0
            for (col in gradColSum.indices) {
                if (gradColSum[col] > gradThreshold) {
                    if (!inPeak) { peakStart = col; inPeak = true }
                } else {
                    if (inPeak) { gradPeaks.add((peakStart + col - 1) / 2); inPeak = false }
                }
            }
            if (inPeak) gradPeaks.add((peakStart + gradColSum.size - 1) / 2)

            var gradGapX: Int? = null
            var bestGradScore = 0.0
            for (i in 0 until gradPeaks.size - 1) {
                val gap = gradPeaks[i + 1] - gradPeaks[i]
                if (gap in 30..130) {
                    val score = gradColSum[gradPeaks[i]] + gradColSum[gradPeaks[i + 1]]
                    if (score > bestGradScore) {
                        bestGradScore = score
                        gradGapX = (gradPeaks[i] + gradPeaks[i + 1]) / 2 + searchLeft
                    }
                }
            }

            // === Consensus: pick best result ===
            val candidates = mutableListOf<Pair<Int, String>>()
            if (darkGapX != null) candidates.add(darkGapX to "dark")
            if (wbGapX != null) candidates.add(wbGapX to "whiteBorder")
            if (gradGapX != null) candidates.add(gradGapX to "gradient")

            Log.d(TAG, "detectGapFromStatic: dark=$darkGapX(localContrast=${"%.1f".format(bestLocalContrast)}), " +
                "wb=$wbGapX(peaks=${wbPeaks.size}), " +
                "grad=$gradGapX(peaks=${gradPeaks.size}), candidates=${candidates.size}")

            if (candidates.isEmpty()) {
                // Fallback: use findGapRegion
                Log.d(TAG, "detectGapFromStatic: no candidates, falling back to findGapRegion")
                return null
            }

            // If multiple methods agree (within 40px), use their average
            if (candidates.size >= 2) {
                val sorted = candidates.sortedBy { it.first }
                for (i in 0 until sorted.size - 1) {
                    if (kotlin.math.abs(sorted[i].first - sorted[i + 1].first) < 40) {
                        val avg = (sorted[i].first + sorted[i + 1].first) / 2
                        Log.d(TAG, "detectGapFromStatic: consensus ${sorted[i].second}+${sorted[i + 1].second} → x=$avg")
                        return avg
                    }
                }
            }

            // Single best: prefer whiteBorder > gradient > dark
            val result = wbGapX ?: gradGapX ?: darkGapX
            if (result != null) {
                val method = when (result) {
                    wbGapX -> "whiteBorder"
                    gradGapX -> "gradient"
                    else -> "dark"
                }
                Log.d(TAG, "detectGapFromStatic: single-method $method → x=$result")
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "detectGapFromStatic failed: ${e.message}")
            return null
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
    // v1.2.75 PRIMARY: Proven puzzle CAPTCHA solver approach
    // Based on production solvers (GeeTest, Tencent, etc.):
    //   Heavy GaussianBlur → Canny(100,200) → TM_CCOEFF_NORMED
    // =====================================================================

    /**
     * Detect gap by comparing BEFORE/AFTER screenshots.
     *
     * THREE detection methods, combined by consensus:
     *
     * Method A — Edge template matching (PROVEN approach):
     *   1. Diff → find piece bounding rect
     *   2. Crop piece from AFTER image
     *   3. Heavy GaussianBlur(5×5) → Canny(100,200) on piece → kills texture, keeps shape
     *   4. Same blur+Canny on BEFORE image (search area)
     *   5. matchTemplate(TM_CCOEFF_NORMED) → gap position
     *   This is the standard method used by all successful puzzle CAPTCHA solvers.
     *
     * Method B — Silhouette template matching:
     *   Same as A but uses diff binary mask edges (no texture at all).
     *   Better for images with heavy texture, worse for simple images.
     *
     * Method C — Local contrast column scan:
     *   Scans for band darker than its LEFT+RIGHT neighbors.
     *   Works on any image type, especially white/bright images.
     */
    fun detectGapByDiff(
        before: Bitmap, after: Bitmap,
        sliderY: Int, sliderX: Float, moveDistance: Float
    ): Int? {
        if (!ensureOpenCV()) return null

        val matBefore = Mat()
        val matAfter = Mat()
        val grayBefore = Mat()
        val grayAfter = Mat()

        try {
            val top = (sliderY - 500).coerceAtLeast(0)
            val bottom = (sliderY - 20).coerceAtLeast(top + 10).coerceAtMost(before.height)
            if (bottom - top < 50) return null
            val w = before.width

            Utils.bitmapToMat(before, matBefore)
            Utils.bitmapToMat(after, matAfter)
            Imgproc.cvtColor(matBefore, grayBefore, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(matAfter, grayAfter, Imgproc.COLOR_RGBA2GRAY)

            val regionBefore = grayBefore.submat(top, bottom, 0, w)
            val regionAfter = grayAfter.submat(top, bottom, 0, w)

            // === Step 1: Compute diff ===
            val diff = Mat()
            Core.absdiff(regionBefore, regionAfter, diff)

            val blurDiff = Mat()
            Imgproc.GaussianBlur(diff, blurDiff, Size(7.0, 7.0), 0.0)

            val binaryDiff = Mat()
            Imgproc.threshold(blurDiff, binaryDiff, 30.0, 255.0, Imgproc.THRESH_BINARY)

            // Morphological: erode to clean noise, then dilate to restore shape
            val erodeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.erode(binaryDiff, binaryDiff, erodeK)
            erodeK.release()
            val dilateK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
            Imgproc.dilate(binaryDiff, binaryDiff, dilateK)
            dilateK.release()

            debugCounter++
            saveMat(diff, "diff_raw")
            saveMat(binaryDiff, "diff_binary")

            // === Step 2: Find piece contour in diff ===
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            val diffClone = binaryDiff.clone()
            Imgproc.findContours(
                diffClone, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            diffClone.release()
            hierarchy.release()

            val expectedNewX = sliderX + moveDistance
            var pieceRect: org.opencv.core.Rect? = null
            var bestDist = Float.MAX_VALUE

            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val area = Imgproc.contourArea(contour)
                contour.release()

                Log.d(TAG, "detectGapByDiff: contour (${rect.x},${rect.y}) " +
                    "${rect.width}x${rect.height} area=${"%.0f".format(area)}")

                if (area < 500 || rect.width < 20 || rect.height < 20) continue
                if (rect.width > 250 || rect.height > 250) continue

                val cx = rect.x + rect.width / 2f
                val d = kotlin.math.abs(cx - expectedNewX)
                if (d < bestDist) {
                    bestDist = d
                    pieceRect = rect
                }
            }

            if (pieceRect == null) {
                Log.w(TAG, "detectGapByDiff: no piece contour in diff " +
                    "(${contours.size} contours, expectedX=${expectedNewX.toInt()})")
                val contrastResult = localContrastScan(regionBefore, w, 60, sliderX)
                diff.release(); blurDiff.release(); binaryDiff.release()
                regionBefore.release(); regionAfter.release()
                return contrastResult
            }

            val pieceW = pieceRect.width
            val pieceH = pieceRect.height
            Log.d(TAG, "detectGapByDiff: piece ${pieceW}x${pieceH} " +
                "at (${pieceRect.x},${pieceRect.y})")

            val pT = pieceRect.y.coerceAtLeast(0)
            val pB = (pieceRect.y + pieceH).coerceAtMost(regionBefore.rows())
            val pL = pieceRect.x.coerceAtLeast(0)
            val pR = (pieceRect.x + pieceW).coerceAtMost(regionBefore.cols())

            var edgeGapX: Int? = null
            var edgeConfidence = 0.0
            var silhouetteGapX: Int? = null
            var silhouetteConfidence = 0.0

            if (pR - pL >= 20 && pB - pT >= 20) {
                // Search area: right of piece starting position
                val searchLeft = (sliderX + pieceW * 0.5f).toInt().coerceIn(0, w - 1)
                if (w - searchLeft > pieceW + 10) {
                    val searchSub = regionBefore.submat(
                        0, regionBefore.rows(), searchLeft, w
                    )

                    // === METHOD A: Piece image edges (PROVEN approach) ===
                    // Key insight from production solvers:
                    // GaussianBlur kills texture noise, Canny(100,200) keeps only
                    // strong shape edges. The jigsaw outline survives blur+high-threshold.
                    val pieceCrop = regionAfter.submat(pT, pB, pL, pR)
                    val pieceBlur = Mat()
                    Imgproc.GaussianBlur(pieceCrop, pieceBlur, Size(5.0, 5.0), 0.0)
                    val pieceEdges = Mat()
                    Imgproc.Canny(pieceBlur, pieceEdges, 100.0, 200.0)

                    val searchBlur = Mat()
                    Imgproc.GaussianBlur(searchSub, searchBlur, Size(5.0, 5.0), 0.0)
                    val searchEdges = Mat()
                    Imgproc.Canny(searchBlur, searchEdges, 100.0, 200.0)

                    saveMat(pieceCrop, "piece_crop")
                    saveMat(pieceEdges, "piece_edges_blurred")
                    saveMat(searchEdges, "search_edges_blurred")

                    if (searchEdges.cols() >= pieceEdges.cols() &&
                        searchEdges.rows() >= pieceEdges.rows()) {
                        val result = Mat()
                        Imgproc.matchTemplate(
                            searchEdges, pieceEdges, result,
                            Imgproc.TM_CCOEFF_NORMED
                        )
                        val loc = Core.minMaxLoc(result)
                        edgeGapX = loc.maxLoc.x.toInt() + pieceW / 2 + searchLeft
                        edgeConfidence = loc.maxVal
                        result.release()

                        Log.d(TAG, "detectGapByDiff: [A-edge] x=$edgeGapX " +
                            "conf=${"%.3f".format(edgeConfidence)}")
                    }
                    pieceBlur.release(); pieceEdges.release()
                    searchBlur.release(); searchEdges.release()

                    // === METHOD B: Silhouette edges (backup for textured images) ===
                    val pieceMask = binaryDiff.submat(pT, pB, pL, pR)
                    val silEdges = Mat()
                    Imgproc.Canny(pieceMask, silEdges, 50.0, 150.0)
                    saveMat(silEdges, "piece_silhouette_edges")

                    // Use lighter blur on search for silhouette matching
                    val searchEdges2 = Mat()
                    Imgproc.Canny(searchSub, searchEdges2, 50.0, 150.0)

                    if (searchEdges2.cols() >= silEdges.cols() &&
                        searchEdges2.rows() >= silEdges.rows()) {
                        val result2 = Mat()
                        Imgproc.matchTemplate(
                            searchEdges2, silEdges, result2,
                            Imgproc.TM_CCOEFF_NORMED
                        )
                        val loc2 = Core.minMaxLoc(result2)
                        silhouetteGapX = loc2.maxLoc.x.toInt() + pieceW / 2 + searchLeft
                        silhouetteConfidence = loc2.maxVal
                        result2.release()

                        Log.d(TAG, "detectGapByDiff: [B-sil] x=$silhouetteGapX " +
                            "conf=${"%.3f".format(silhouetteConfidence)}")
                    }
                    silEdges.release(); searchEdges2.release()
                    searchSub.release()
                }
            }

            // === METHOD C: Local contrast column scan ===
            val contrastGapX = localContrastScan(regionBefore, w, pieceW, sliderX)

            // Cleanup
            diff.release(); blurDiff.release(); binaryDiff.release()
            regionBefore.release(); regionAfter.release()

            // === Combine all results ===
            Log.d(TAG, "detectGapByDiff: [A] edge=$edgeGapX(${
                "%.3f".format(edgeConfidence)}), [B] sil=$silhouetteGapX(${
                "%.3f".format(silhouetteConfidence)}), [C] contrast=$contrastGapX")

            // Collect valid candidates with scores
            data class Candidate(val x: Int, val conf: Double, val name: String)
            val candidates = mutableListOf<Candidate>()
            if (edgeGapX != null && edgeConfidence >= 0.05)
                candidates.add(Candidate(edgeGapX, edgeConfidence, "edge"))
            if (silhouetteGapX != null && silhouetteConfidence >= 0.05)
                candidates.add(Candidate(silhouetteGapX, silhouetteConfidence, "sil"))
            if (contrastGapX != null)
                candidates.add(Candidate(contrastGapX, 0.5, "contrast"))

            if (candidates.isEmpty()) {
                Log.w(TAG, "detectGapByDiff: all 3 methods failed")
                return null
            }

            // Check for consensus: any 2 methods within 30px
            for (i in 0 until candidates.size) {
                for (j in i + 1 until candidates.size) {
                    if (kotlin.math.abs(candidates[i].x - candidates[j].x) < 30) {
                        val avg = (candidates[i].x + candidates[j].x) / 2
                        Log.d(TAG, "detectGapByDiff: CONSENSUS ${candidates[i].name}+" +
                            "${candidates[j].name} → x=$avg")
                        return avg
                    }
                }
            }

            // No consensus — pick best by confidence
            val best = candidates.maxByOrNull { it.conf }!!
            Log.d(TAG, "detectGapByDiff: no consensus, using ${best.name} x=${best.x} " +
                "conf=${"%.3f".format(best.conf)}")
            return best.x
        } catch (e: Exception) {
            Log.e(TAG, "detectGapByDiff failed: ${e.message}")
            return null
        } finally {
            matBefore.release(); matAfter.release()
            grayBefore.release(); grayAfter.release()
        }
    }

    /**
     * Scan for gap using LOCAL CONTRAST — find the band that is darkest
     * relative to its LEFT and RIGHT neighbors (not globally darkest).
     *
     * This handles varied backgrounds where parts of the image are naturally dark.
     * The gap shadow is always darker than its IMMEDIATE surroundings.
     *
     * @param region Grayscale puzzle region
     * @param width Image width
     * @param pieceW Estimated piece width (from diff or default 60)
     * @param sliderX Slider position (skip area around piece)
     * @return Gap center X in region coordinates, or null
     */
    private fun localContrastScan(
        region: Mat, width: Int, pieceW: Int, sliderX: Float
    ): Int? {
        try {
            // Precompute per-column mean brightness
            val colMeans = DoubleArray(width)
            for (x in 0 until width) {
                val col = region.col(x)
                colMeans[x] = Core.mean(col).`val`[0]
                col.release()
            }

            // Scan: for each candidate position, compare window to left/right neighbors
            val scanStart = (sliderX + pieceW * 0.5f).toInt().coerceAtLeast(pieceW)
            val scanEnd = width - pieceW
            if (scanStart >= scanEnd) return null

            var bestX: Int? = null
            var bestContrast = 0.0

            for (x in scanStart..scanEnd) {
                // Center window
                val windowAvg = colMeans.slice(x until (x + pieceW)).average()

                // Left neighbor (same width as piece, or whatever fits)
                val leftEnd = x
                val leftStart = (x - pieceW).coerceAtLeast(0)
                if (leftEnd <= leftStart) continue
                val leftAvg = colMeans.slice(leftStart until leftEnd).average()

                // Right neighbor
                val rightStart = x + pieceW
                val rightEnd = (rightStart + pieceW).coerceAtMost(width)
                if (rightEnd <= rightStart) continue
                val rightAvg = colMeans.slice(rightStart until rightEnd).average()

                // Local contrast = how much darker than BOTH neighbors
                val contrast = ((leftAvg + rightAvg) / 2.0) - windowAvg

                if (contrast > bestContrast) {
                    bestContrast = contrast
                    bestX = x + pieceW / 2
                }
            }

            // Adaptive threshold: use relative contrast for white/bright images
            // White image (mean ~200): absolute 3.0 is too high → use 1.0% of mean
            val globalMean = colMeans.slice(scanStart..scanEnd).average()
            val minContrast = (globalMean * 0.012).coerceIn(1.5, 5.0)

            if (bestContrast < minContrast || bestX == null) {
                Log.d(TAG, "localContrastScan: contrast ${"%.1f".format(bestContrast)} " +
                    "< threshold ${"%.1f".format(minContrast)} (mean=${"%.0f".format(globalMean)})")
                return null
            }

            Log.d(TAG, "localContrastScan: gap x=$bestX " +
                "contrast=${"%.1f".format(bestContrast)} pieceW=$pieceW")
            return bestX
        } catch (e: Exception) {
            Log.e(TAG, "localContrastScan failed: ${e.message}")
            return null
        }
    }

}
