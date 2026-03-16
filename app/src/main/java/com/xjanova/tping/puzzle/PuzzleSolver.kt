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

    /** Last template matching confidence (0..1) from detectGapSimple */
    @Volatile var lastConfidence: Double = 0.0
        private set

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
     * v1.2.80: Find the gap by detecting the white geometric contour shape.
     *
     * The gap ALWAYS has a thin white border forming a CLOSED geometric shape.
     * We find this shape by:
     *   1. Try multiple white thresholds (190, 200, 210, 220)
     *   2. Morphological close to seal gaps in the outline
     *   3. Find contours → filter by size + geometric shape (approxPolyDP)
     *   4. Verify: interior should be darker than exterior (gap shadow)
     *   5. Pick the best verified candidate
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
            val searchLeft = (sliderX + 60f).toInt().coerceAtLeast(0)
            val searchRight = screenshot.width
            if (searchRight - searchLeft < 80) return null

            Utils.bitmapToMat(screenshot, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val region = gray.submat(top, bottom, searchLeft, searchRight)
            val regionH = bottom - top
            val regionW = searchRight - searchLeft

            debugCounter++

            // === Try multiple thresholds to handle different image brightness ===
            data class GapCandidate(
                val cx: Int, val cy: Int,
                val rect: org.opencv.core.Rect,
                val area: Double, val vertices: Int,
                val interiorDiff: Double, // negative = interior darker = gap shadow
                val threshold: Int
            )
            val allCandidates = mutableListOf<GapCandidate>()

            for (thresh in intArrayOf(190, 200, 210, 220)) {
                val whiteMask = Mat()
                Imgproc.threshold(region, whiteMask, thresh.toDouble(), 255.0, Imgproc.THRESH_BINARY)

                // Morphological close: dilate then erode — seals small gaps in outline
                val closeK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                Imgproc.morphologyEx(whiteMask, whiteMask, Imgproc.MORPH_CLOSE, closeK)
                closeK.release()

                if (thresh == 200) saveMat(whiteMask, "white_mask_200")

                val contours = mutableListOf<MatOfPoint>()
                val hierarchy = Mat()
                val clone = whiteMask.clone()
                Imgproc.findContours(
                    clone, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
                )
                clone.release()
                hierarchy.release()
                whiteMask.release()

                for (contour in contours) {
                    val rect = Imgproc.boundingRect(contour)
                    val area = Imgproc.contourArea(contour)

                    // Size filter: jigsaw piece sized (30-150px)
                    if (rect.width < 30 || rect.width > 150 ||
                        rect.height < 30 || rect.height > 150) {
                        contour.release(); continue
                    }

                    // Aspect ratio check
                    val aspect = rect.width.toFloat() / rect.height
                    if (aspect < 0.4f || aspect > 2.5f) {
                        contour.release(); continue
                    }

                    // Geometric shape check: approximate to polygon
                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val perim = Imgproc.arcLength(contour2f, true)
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(contour2f, approx, perim * 0.02, true)
                    val vertices = approx.rows()
                    contour2f.release()
                    approx.release()
                    contour.release()

                    // A geometric shape has 4-20 vertices after simplification
                    // (rectangle=4, jigsaw with tabs=6-16, complex noise=many more)
                    if (vertices < 4 || vertices > 25) continue

                    // === Verify: interior should be darker than exterior (gap shadow) ===
                    val innerL = (rect.x + 5).coerceAtLeast(0)
                    val innerR = (rect.x + rect.width - 5).coerceAtMost(regionW)
                    val innerT = (rect.y + 5).coerceAtLeast(0)
                    val innerB = (rect.y + rect.height - 5).coerceAtMost(regionH)

                    if (innerR <= innerL || innerB <= innerT) continue

                    val innerRegion = region.submat(innerT, innerB, innerL, innerR)
                    val innerMean = Core.mean(innerRegion).`val`[0]
                    innerRegion.release()

                    // Exterior: sample left and right bands outside the rect
                    var exteriorMean = 0.0
                    var extCount = 0
                    val extBandW = 20
                    val extL = (rect.x - extBandW).coerceAtLeast(0)
                    val extR = (rect.x + rect.width + extBandW).coerceAtMost(regionW)
                    if (rect.x - extL >= 5) {
                        val leftBand = region.submat(innerT, innerB, extL, rect.x)
                        exteriorMean += Core.mean(leftBand).`val`[0]
                        leftBand.release()
                        extCount++
                    }
                    if (extR - (rect.x + rect.width) >= 5) {
                        val rightBand = region.submat(
                            innerT, innerB, rect.x + rect.width, extR
                        )
                        exteriorMean += Core.mean(rightBand).`val`[0]
                        rightBand.release()
                        extCount++
                    }
                    if (extCount > 0) exteriorMean /= extCount
                    else exteriorMean = innerMean // can't verify

                    // Interior-exterior difference (negative = interior darker = gap shadow)
                    val interiorDiff = innerMean - exteriorMean

                    val cx = rect.x + rect.width / 2 + searchLeft
                    val cy = rect.y + rect.height / 2

                    Log.d(TAG, "detectGap: thresh=$thresh contour x=$cx " +
                        "${rect.width}x${rect.height} area=${"%.0f".format(area)} " +
                        "vertices=$vertices innerDiff=${"%.1f".format(interiorDiff)}")

                    allCandidates.add(GapCandidate(
                        cx, cy, rect, area, vertices, interiorDiff, thresh
                    ))
                }
            }

            region.release()

            if (allCandidates.isEmpty()) {
                Log.d(TAG, "detectGap: no geometric contour found")
                return null
            }

            // === Select best candidate ===
            // Priority: interior darker than exterior (negative interiorDiff)
            // Among those, pick the one with largest area (most well-defined outline)
            val darkInterior = allCandidates.filter { it.interiorDiff < -2.0 }
            val best = if (darkInterior.isNotEmpty()) {
                // Gap has shadow: pick largest area among dark-interior candidates
                darkInterior.maxByOrNull { it.area }!!
            } else {
                // No clear shadow: pick largest area overall
                allCandidates.maxByOrNull { it.area }!!
            }

            Log.d(TAG, "detectGap: BEST cx=${best.cx} ${best.rect.width}x${best.rect.height} " +
                "area=${"%.0f".format(best.area)} vertices=${best.vertices} " +
                "innerDiff=${"%.1f".format(best.interiorDiff)} thresh=${best.threshold} " +
                "candidates=${allCandidates.size} darkInterior=${darkInterior.size}")

            return best.cx
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
    /**
     * Result from diff-based gap detection.
     * Returns the SLIDER TARGET position (not the image gap position).
     * Accounts for piece-to-slider movement ratio automatically.
     */
    data class DiffResult(
        val sliderTarget: Int,   // Where the slider should be moved to (screen X)
        val gapImageX: Int,      // Gap center in image coordinates
        val pieceOldCx: Int,     // Piece old position center X (image coords)
        val pieceNewCx: Int,     // Piece new position center X (image coords)
        val pieceMovement: Int,  // Actual piece movement in image pixels
        val scaleFactor: Float,  // slider_movement / piece_movement
        val confidence: Double,
        val method: String
    )

    /**
     * v1.2.82: Diff + RAW grayscale template matching.
     *
     * Key insight from user: the gap has the SAME image content as the piece
     * but BRIGHTER. Using Canny edges destroys brightness info and loses the match.
     *
     * TM_CCOEFF_NORMED is brightness-invariant (normalized cross-correlation),
     * so it naturally handles the brighter gap content.
     *
     * Steps:
     * 1. Diff → find piece blob (merged old+new positions)
     * 2. Estimate real piece width = blob width - moveDistance
     * 3. Crop piece from RIGHT side of blob in AFTER image (where piece IS now)
     * 4. Light blur on BEFORE image → zero out ENTIRE diff blob area
     * 5. ONE template match (TM_CCOEFF_NORMED) on RAW grayscale
     * 6. Return gap center X in screen coordinates
     */
    fun detectGapSimple(
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

            // === Step 1: Diff to find piece blob ===
            val diff = Mat()
            Core.absdiff(regionBefore, regionAfter, diff)
            val blurDiff = Mat()
            Imgproc.GaussianBlur(diff, blurDiff, Size(7.0, 7.0), 0.0)
            val binary = Mat()
            Imgproc.threshold(blurDiff, binary, 25.0, 255.0, Imgproc.THRESH_BINARY)

            val morphK = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, morphK)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, morphK)
            morphK.release()

            debugCounter++
            saveMat(diff, "diff_raw")
            saveMat(binary, "diff_binary")

            // === Step 2: Find largest blob = merged old+new piece positions ===
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            val bClone = binary.clone()
            Imgproc.findContours(bClone, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            bClone.release()
            hierarchy.release()

            var blobRect: org.opencv.core.Rect? = null
            var blobArea = 0.0
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                val rect = Imgproc.boundingRect(c)
                c.release()
                if (area > blobArea && rect.width > 20 && rect.height > 20
                    && rect.width < 300 && rect.height < 200) {
                    blobArea = area
                    blobRect = rect
                }
            }

            if (blobRect == null) {
                Log.w(TAG, "detectGapSimple: no diff blob found")
                diff.release(); blurDiff.release(); binary.release()
                regionBefore.release(); regionAfter.release()
                return null
            }

            // === Step 3: Estimate real piece width and crop from RIGHT side ===
            // Diff blob = merged old+new positions. If piece moved moveDistance px,
            // blob width ≈ pieceWidth + moveDistance. So pieceWidth ≈ blobWidth - moveDistance
            val moveInt = moveDistance.toInt()
            val estimatedPieceW = (blobRect.width - moveInt).coerceIn(30, 150)
            val pieceH = blobRect.height

            Log.d(TAG, "detectGapSimple: blob=(${blobRect.x},${blobRect.y}) " +
                "${blobRect.width}x${blobRect.height} area=${"%.0f".format(blobArea)} " +
                "estimatedPieceW=$estimatedPieceW moveDistance=$moveInt")

            // Piece is at the RIGHT side of the blob (where it moved TO in AFTER)
            val pL = (blobRect.x + blobRect.width - estimatedPieceW).coerceAtLeast(0)
            val pR = (blobRect.x + blobRect.width).coerceAtMost(w)
            val pT = blobRect.y.coerceAtLeast(0)
            val pB = (blobRect.y + pieceH).coerceAtMost(regionAfter.rows())

            if (pR - pL < 20 || pB - pT < 20) {
                diff.release(); blurDiff.release(); binary.release()
                regionBefore.release(); regionAfter.release()
                return null
            }

            // Crop piece from AFTER image (RAW grayscale, NO Canny)
            val pieceCrop = regionAfter.submat(pT, pB, pL, pR)
            // Light blur to reduce noise but keep content
            val pieceTemplate = Mat()
            Imgproc.GaussianBlur(pieceCrop, pieceTemplate, Size(3.0, 3.0), 0.0)
            saveMat(pieceCrop, "piece_crop")

            // === Step 4: Prepare search image (BEFORE, blur, zero out piece area) ===
            val searchImage = Mat()
            Imgproc.GaussianBlur(regionBefore, searchImage, Size(3.0, 3.0), 0.0)

            // Zero out the ENTIRE diff blob area (covers both old and new piece positions)
            // Add generous padding to avoid partial matches near the piece
            val zeroL = (blobRect.x - 20).coerceAtLeast(0)
            val zeroR = (blobRect.x + blobRect.width + 20).coerceAtMost(w)
            if (zeroR > zeroL) {
                val zeroArea = searchImage.submat(0, searchImage.rows(), zeroL, zeroR)
                zeroArea.setTo(Scalar(128.0)) // set to neutral gray (not 0 which creates edges)
                zeroArea.release()
            }

            saveMat(searchImage, "search_masked")

            // === Step 5: Template match (RAW grayscale, TM_CCOEFF_NORMED) ===
            if (searchImage.cols() < pieceTemplate.cols() ||
                searchImage.rows() < pieceTemplate.rows()) {
                pieceTemplate.release(); searchImage.release()
                pieceCrop.release()
                diff.release(); blurDiff.release(); binary.release()
                regionBefore.release(); regionAfter.release()
                return null
            }

            val result = Mat()
            Imgproc.matchTemplate(searchImage, pieceTemplate, result, Imgproc.TM_CCOEFF_NORMED)
            val loc = Core.minMaxLoc(result)
            result.release()
            pieceTemplate.release()
            searchImage.release()
            pieceCrop.release()

            val gapCenterX = loc.maxLoc.x.toInt() + estimatedPieceW / 2
            lastConfidence = loc.maxVal

            Log.d(TAG, "detectGapSimple: match at x=$gapCenterX " +
                "conf=${"%.3f".format(loc.maxVal)} " +
                "zeroArea=($zeroL→$zeroR) pieceW=$estimatedPieceW")

            // Cleanup
            diff.release(); blurDiff.release(); binary.release()
            regionBefore.release(); regionAfter.release()

            if (loc.maxVal < 0.05) {
                Log.w(TAG, "detectGapSimple: confidence too low (${"%.3f".format(loc.maxVal)})")
                return null
            }

            return gapCenterX
        } catch (e: Exception) {
            Log.e(TAG, "detectGapSimple failed: ${e.message}")
            return null
        } finally {
            matBefore.release(); matAfter.release()
            grayBefore.release(); grayAfter.release()
        }
    }

    fun detectGapByDiff(
        before: Bitmap, after: Bitmap,
        sliderY: Int, sliderX: Float, moveDistance: Float
    ): DiffResult? {
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

            Log.d(TAG, "detectGapByDiff: bitmap=${before.width}x${before.height} " +
                "sliderX=${"%.0f".format(sliderX)} sliderY=$sliderY move=${"%.0f".format(moveDistance)} " +
                "region=($top→$bottom, 0→$w)")

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

            // === Step 2: Find ALL valid contours in diff ===
            // Diff produces TWO blobs: old piece pos (where it left) and new piece pos (where it arrived)
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            val diffClone = binaryDiff.clone()
            Imgproc.findContours(
                diffClone, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            diffClone.release()
            hierarchy.release()

            data class ContourInfo(val rect: org.opencv.core.Rect, val area: Double, val cx: Float)
            val validContours = mutableListOf<ContourInfo>()

            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val area = Imgproc.contourArea(contour)
                contour.release()

                Log.d(TAG, "detectGapByDiff: contour (${rect.x},${rect.y}) " +
                    "${rect.width}x${rect.height} area=${"%.0f".format(area)}")

                if (area < 500 || rect.width < 20 || rect.height < 20) continue
                if (rect.width > 300 || rect.height > 300) continue

                val cx = rect.x + rect.width / 2f
                validContours.add(ContourInfo(rect, area, cx))
            }

            if (validContours.isEmpty()) {
                Log.w(TAG, "detectGapByDiff: no valid contours in diff (${contours.size} total)")
                diff.release(); blurDiff.release(); binaryDiff.release()
                regionBefore.release(); regionAfter.release()
                return null
            }

            // Sort by X position — leftmost = old piece pos, rightmost = new piece pos
            validContours.sortBy { it.cx }

            // Identify OLD (leftmost) and NEW (rightmost) piece positions
            val oldPiece: ContourInfo
            val newPiece: ContourInfo

            if (validContours.size >= 2) {
                oldPiece = validContours.first()
                newPiece = validContours.last()
            } else {
                // Only 1 contour — can't determine movement; use as new piece
                newPiece = validContours.first()
                // Estimate old position from slider movement
                val estimatedOldCx = newPiece.cx - moveDistance
                oldPiece = ContourInfo(
                    org.opencv.core.Rect(
                        (estimatedOldCx - newPiece.rect.width / 2).toInt().coerceAtLeast(0),
                        newPiece.rect.y,
                        newPiece.rect.width,
                        newPiece.rect.height
                    ),
                    newPiece.area,
                    estimatedOldCx
                )
                Log.d(TAG, "detectGapByDiff: single contour, estimated oldCx=${estimatedOldCx.toInt()}")
            }

            // === Step 3: Calculate piece-to-slider movement ratio ===
            val pieceMovement = newPiece.cx - oldPiece.cx
            val scaleFactor: Float
            if (pieceMovement > 5f) {
                scaleFactor = moveDistance / pieceMovement
            } else {
                // Piece barely moved — assume 1:1 ratio
                scaleFactor = 1.0f
                Log.w(TAG, "detectGapByDiff: piece barely moved (${pieceMovement.toInt()}px), assuming 1:1 ratio")
            }

            Log.d(TAG, "detectGapByDiff: oldPiece cx=${oldPiece.cx.toInt()} " +
                "newPiece cx=${newPiece.cx.toInt()} " +
                "pieceMove=${pieceMovement.toInt()} scaleFactor=${"%.3f".format(scaleFactor)}")

            val pieceW = newPiece.rect.width
            val pieceH = newPiece.rect.height
            val pieceRect = newPiece.rect

            val pT = pieceRect.y.coerceAtLeast(0)
            val pB = (pieceRect.y + pieceH).coerceAtMost(regionBefore.rows())
            val pL = pieceRect.x.coerceAtLeast(0)
            val pR = (pieceRect.x + pieceW).coerceAtMost(regionBefore.cols())

            var edgeGapX: Int? = null
            var edgeConfidence = 0.0
            var silhouetteGapX: Int? = null
            var silhouetteConfidence = 0.0

            if (pR - pL >= 20 && pB - pT >= 20) {
                // === v1.2.78 CRITICAL FIX: Exclude BOTH old and new piece positions ===
                // Old code only excluded newPiece → template matching found the old piece
                // itself as best match (100% match!) → gapDist=0 → gap_too_close every time!
                //
                // Search zones (all exclude BOTH piece positions):
                // Zone A: LEFT of old piece [0, oldExcL)
                // Zone B: BETWEEN old and new piece (oldExcR, newExcL)
                // Zone C: RIGHT of new piece (newExcR, w)
                val oldExcL = (oldPiece.rect.x - 10).coerceAtLeast(0)
                val oldExcR = (oldPiece.rect.x + oldPiece.rect.width + 10).coerceAtMost(w)
                val newExcL = (newPiece.rect.x - 10).coerceAtLeast(0)
                val newExcR = (newPiece.rect.x + newPiece.rect.width + 10).coerceAtMost(w)

                Log.d(TAG, "detectGapByDiff: oldExc=($oldExcL→$oldExcR) " +
                    "newExc=($newExcL→$newExcR) width=$w")

                // Crop piece template from AFTER image at new position
                val pieceCrop = regionAfter.submat(pT, pB, pL, pR)

                // === METHOD A: Piece image edges (PROVEN approach) ===
                val pieceBlur = Mat()
                Imgproc.GaussianBlur(pieceCrop, pieceBlur, Size(5.0, 5.0), 0.0)
                val pieceEdges = Mat()
                Imgproc.Canny(pieceBlur, pieceEdges, 100.0, 200.0)

                saveMat(pieceCrop, "piece_crop")
                saveMat(pieceEdges, "piece_edges_blurred")

                // Define search zones: between pieces, and right of new piece
                // Zone B: between old piece right edge and new piece left edge
                data class SearchZone(val left: Int, val right: Int, val name: String)
                val zones = mutableListOf<SearchZone>()

                // Zone A: left of old piece (rarely has gap, but check anyway)
                if (oldExcL > pieceW + 10) {
                    zones.add(SearchZone(0, oldExcL, "leftOfOld"))
                }
                // Zone B: between old and new piece
                if (newExcL - oldExcR > pieceW + 10) {
                    zones.add(SearchZone(oldExcR, newExcL, "between"))
                }
                // Zone C: right of new piece
                if (w - newExcR > pieceW + 10) {
                    zones.add(SearchZone(newExcR, w, "rightOfNew"))
                }

                Log.d(TAG, "detectGapByDiff: ${zones.size} search zones: " +
                    zones.joinToString { "${it.name}(${it.left}→${it.right})" })

                // Search all zones for Method A (edge template)
                for (zone in zones) {
                    val searchSub = regionBefore.submat(
                        0, regionBefore.rows(), zone.left, zone.right
                    )
                    val searchBlur = Mat()
                    Imgproc.GaussianBlur(searchSub, searchBlur, Size(5.0, 5.0), 0.0)
                    val searchEdges = Mat()
                    Imgproc.Canny(searchBlur, searchEdges, 100.0, 200.0)

                    if (zone.name == "between") saveMat(searchEdges, "search_edges_between")
                    if (zone.name == "rightOfNew") saveMat(searchEdges, "search_edges_right")

                    if (searchEdges.cols() >= pieceEdges.cols() &&
                        searchEdges.rows() >= pieceEdges.rows()) {
                        val result = Mat()
                        Imgproc.matchTemplate(
                            searchEdges, pieceEdges, result,
                            Imgproc.TM_CCOEFF_NORMED
                        )
                        val loc = Core.minMaxLoc(result)
                        val candidateX = loc.maxLoc.x.toInt() + pieceW / 2 + zone.left
                        if (loc.maxVal > edgeConfidence) {
                            edgeGapX = candidateX
                            edgeConfidence = loc.maxVal
                        }
                        result.release()
                        Log.d(TAG, "detectGapByDiff: [A-edge-${zone.name}] x=$candidateX " +
                            "conf=${"%.3f".format(loc.maxVal)}")
                    }
                    searchBlur.release(); searchEdges.release()
                    searchSub.release()
                }

                Log.d(TAG, "detectGapByDiff: [A-edge-BEST] x=$edgeGapX " +
                    "conf=${"%.3f".format(edgeConfidence)}")

                // === METHOD B: Silhouette edges ===
                val pieceMask = binaryDiff.submat(pT, pB, pL, pR)
                val silEdges = Mat()
                Imgproc.Canny(pieceMask, silEdges, 50.0, 150.0)
                saveMat(silEdges, "piece_silhouette_edges")

                // Search all zones for Method B (silhouette)
                for (zone in zones) {
                    val searchSub2 = regionBefore.submat(
                        0, regionBefore.rows(), zone.left, zone.right
                    )
                    val searchEdges2 = Mat()
                    Imgproc.Canny(searchSub2, searchEdges2, 50.0, 150.0)

                    if (searchEdges2.cols() >= silEdges.cols() &&
                        searchEdges2.rows() >= silEdges.rows()) {
                        val result2 = Mat()
                        Imgproc.matchTemplate(
                            searchEdges2, silEdges, result2,
                            Imgproc.TM_CCOEFF_NORMED
                        )
                        val loc2 = Core.minMaxLoc(result2)
                        val candidateX = loc2.maxLoc.x.toInt() + pieceW / 2 + zone.left
                        if (loc2.maxVal > silhouetteConfidence) {
                            silhouetteGapX = candidateX
                            silhouetteConfidence = loc2.maxVal
                        }
                        result2.release()
                        Log.d(TAG, "detectGapByDiff: [B-sil-${zone.name}] x=$candidateX " +
                            "conf=${"%.3f".format(loc2.maxVal)}")
                    }
                    searchEdges2.release(); searchSub2.release()
                }

                pieceBlur.release(); pieceEdges.release()
                silEdges.release()
            }

            // === METHOD C: Local contrast column scan ===
            // Start AFTER the old piece position to avoid detecting the piece itself
            val contrastScanStart = (oldPiece.rect.x + oldPiece.rect.width + 5).toFloat()
            val contrastGapX = localContrastScan(
                regionBefore, w, pieceW, contrastScanStart.coerceAtLeast(0f)
            )

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
            if (edgeGapX != null && edgeConfidence >= 0.10)
                candidates.add(Candidate(edgeGapX, edgeConfidence, "edge"))
            if (silhouetteGapX != null && silhouetteConfidence >= 0.10)
                candidates.add(Candidate(silhouetteGapX, silhouetteConfidence, "sil"))
            if (contrastGapX != null)
                candidates.add(Candidate(contrastGapX, 0.5, "contrast"))

            if (candidates.isEmpty()) {
                Log.w(TAG, "detectGapByDiff: all 3 methods failed")
                return null
            }

            // Determine best gap position in IMAGE coordinates
            var bestGapImageX: Int
            var bestConf: Double
            var bestMethod: String

            // Check for consensus: any 2 methods within 30px
            var consensusFound = false
            for (i in 0 until candidates.size) {
                for (j in i + 1 until candidates.size) {
                    if (kotlin.math.abs(candidates[i].x - candidates[j].x) < 30) {
                        bestGapImageX = (candidates[i].x + candidates[j].x) / 2
                        bestConf = maxOf(candidates[i].conf, candidates[j].conf)
                        bestMethod = "${candidates[i].name}+${candidates[j].name}"

                        Log.d(TAG, "detectGapByDiff: CONSENSUS $bestMethod → imageX=$bestGapImageX")

                        // Convert image gap position → slider target
                        val gapDistFromPieceStart = bestGapImageX - oldPiece.cx
                        val sliderDist = gapDistFromPieceStart * scaleFactor
                        val sliderTarget = (sliderX + sliderDist).toInt()

                        Log.d(TAG, "detectGapByDiff: gapDist=${gapDistFromPieceStart.toInt()} " +
                            "× scale=${"%.3f".format(scaleFactor)} = sliderDist=${sliderDist.toInt()} " +
                            "→ sliderTarget=$sliderTarget")

                        return DiffResult(
                            sliderTarget = sliderTarget,
                            gapImageX = bestGapImageX,
                            pieceOldCx = oldPiece.cx.toInt(),
                            pieceNewCx = newPiece.cx.toInt(),
                            pieceMovement = pieceMovement.toInt(),
                            scaleFactor = scaleFactor,
                            confidence = bestConf,
                            method = bestMethod
                        )
                    }
                }
            }

            // No consensus — pick best by confidence
            val best = candidates.maxByOrNull { it.conf }!!
            bestGapImageX = best.x
            bestConf = best.conf
            bestMethod = best.name

            Log.d(TAG, "detectGapByDiff: no consensus, using $bestMethod imageX=$bestGapImageX " +
                "conf=${"%.3f".format(bestConf)}")

            // Convert image gap position → slider target
            val gapDistFromPieceStart = bestGapImageX - oldPiece.cx
            val sliderDist = gapDistFromPieceStart * scaleFactor
            val sliderTarget = (sliderX + sliderDist).toInt()

            Log.d(TAG, "detectGapByDiff: gapDist=${gapDistFromPieceStart.toInt()} " +
                "× scale=${"%.3f".format(scaleFactor)} = sliderDist=${sliderDist.toInt()} " +
                "→ sliderTarget=$sliderTarget")

            return DiffResult(
                sliderTarget = sliderTarget,
                gapImageX = bestGapImageX,
                pieceOldCx = oldPiece.cx.toInt(),
                pieceNewCx = newPiece.cx.toInt(),
                pieceMovement = pieceMovement.toInt(),
                scaleFactor = scaleFactor,
                confidence = bestConf,
                method = bestMethod
            )
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
