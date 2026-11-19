package xyz.nextalone.nagram.helper

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import tw.nekomimi.nekogram.helpers.AppRestartHelper
import tw.nekomimi.nekogram.utils.ShareUtil
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive TrueType (.ttf) Font Creator for MediaTok.
 * Users can draw/edit Cyrillic, Latin, Digits, and Punctuation glyphs on a 10x12 grid
 * (starting from blank or rasterized from any existing font), preview live text,
 * compile a 100% spec-compliant TrueType .ttf file directly on device, apply it, or share it.
 */
object MediaTokTtfCreator {

    const val GRID_W = 10
    const val GRID_H = 12

    // Full Russian + English + Digits + Punctuation alphabet (139 glyphs)
    val CHARSET: List<Char> = buildList {
        for (c in 'А'..'Я') add(c)
        add('Ё')
        for (c in 'а'..'я') add(c)
        add('ё')
        for (c in 'A'..'Z') add(c)
        for (c in 'a'..'z') add(c)
        for (c in '0'..'9') add(c)
        for (c in "!?.,:;+-=()[]@#%*\"'/_") add(c)
    }

    enum class PixelStyle(val label: String) {
        SOLID("Плотный"),
        PIXEL_GAP("Пиксель"),
        BOLD("Жирный"),
        ITALIC("Курсив")
    }

    // ===================== RASTERIZER TEMPLATE =====================

    fun rasterizeTemplate(baseTypeface: Typeface?): HashMap<Char, BooleanArray> {
        val result = HashMap<Char, BooleanArray>(CHARSET.size)
        val bmp = Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 9.2f
            if (baseTypeface != null) {
                typeface = baseTypeface
            }
        }
        val baseline = 9.4f
        for (ch in CHARSET) {
            bmp.eraseColor(Color.TRANSPARENT)
            canvas.drawText(ch.toString(), GRID_W / 2f, baseline, paint)
            val grid = BooleanArray(GRID_W * GRID_H)
            for (y in 0 until GRID_H) {
                for (x in 0 until GRID_W) {
                    val alpha = Color.alpha(bmp.getPixel(x, y))
                    grid[y * GRID_W + x] = alpha >= 92
                }
            }
            result[ch] = grid
        }
        bmp.recycle()
        return result
    }

    // ===================== TRUETYPE (.TTF) BINARY COMPILER =====================

    private data class RectI(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    private data class CmapSeg(var start: Int, var end: Int, val delta: Int)
    private data class TtfNameRec(val pid: Int, val eid: Int, val lid: Int, val nid: Int, val len: Int, val off: Int)

    fun compileTtf(
        fontNameRaw: String,
        glyphGrids: Map<Char, BooleanArray>,
        style: PixelStyle
    ): ByteArray {
        val fontName = fontNameRaw.trim().ifEmpty { "MediaTok Custom" }
        val cellW = when (style) {
            PixelStyle.BOLD -> 82
            else -> 76
        }
        val cellH = 82
        val gap = if (style == PixelStyle.PIXEL_GAP) 10 else 0
        val italicShiftPerRow = if (style == PixelStyle.ITALIC) 14 else 0
        val baselineRow = 9 // y in 0..11; rows 0..9 above baseline, 10..11 descender

        val charRects = HashMap<Int, List<RectI>>(glyphGrids.size + 1)
        charRects[32] = emptyList()

        for ((ch, grid) in glyphGrids) {
            val cp = ch.code
            if (cp == 32 || grid.size != GRID_W * GRID_H) continue
            val rects = ArrayList<RectI>()
            // Merge horizontal runs per row for clean minimal contours
            for (row in 0 until GRID_H) {
                val yBottom = (baselineRow - row) * cellH + gap / 2
                val yTop = (baselineRow - row + 1) * cellH - gap / 2
                val slant = (baselineRow - row) * italicShiftPerRow
                var col = 0
                while (col < GRID_W) {
                    if (!grid[row * GRID_W + col]) {
                        col++
                        continue
                    }
                    val startCol = col
                    if (style == PixelStyle.PIXEL_GAP) {
                        // Separate crisp pixel squares
                        val x1 = 40 + startCol * cellW + gap / 2 + slant
                        val x2 = 40 + (startCol + 1) * cellW - gap / 2 + slant
                        rects.add(RectI(x1, yBottom, x2, yTop))
                        col++
                    } else {
                        while (col < GRID_W && grid[row * GRID_W + col]) {
                            col++
                        }
                        val x1 = 40 + startCol * cellW + slant
                        val x2 = 40 + col * cellW + slant
                        rects.add(RectI(x1, yBottom, x2, yTop))
                    }
                }
            }
            charRects[cp] = rects
        }

        return buildTtfBytes(fontName, charRects)
    }

    private fun calcChecksum(data: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < data.size) {
            val b0 = (data[i].toInt() and 0xFF).toLong() shl 24
            val b1 = if (i + 1 < data.size) (data[i + 1].toInt() and 0xFF).toLong() shl 16 else 0L
            val b2 = if (i + 2 < data.size) (data[i + 2].toInt() and 0xFF).toLong() shl 8 else 0L
            val b3 = if (i + 3 < data.size) (data[i + 3].toInt() and 0xFF).toLong() else 0L
            sum = (sum + (b0 or b1 or b2 or b3)) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private fun buildSimpleGlyph(rects: List<RectI>): ByteArray {
        if (rects.isEmpty()) return ByteArray(0)
        var xMin = Int.MAX_VALUE
        var yMin = Int.MAX_VALUE
        var xMax = Int.MIN_VALUE
        var yMax = Int.MIN_VALUE
        for (r in rects) {
            xMin = min(xMin, min(r.x1, r.x2))
            xMax = max(xMax, max(r.x1, r.x2))
            yMin = min(yMin, min(r.y1, r.y2))
            yMax = max(yMax, max(r.y1, r.y2))
        }
        val baos = ByteArrayOutputStream(rects.size * 24 + 16)
        val dos = DataOutputStream(baos)
        dos.writeShort(rects.size)
        dos.writeShort(xMin)
        dos.writeShort(yMin)
        dos.writeShort(xMax)
        dos.writeShort(yMax)
        for (i in rects.indices) {
            dos.writeShort(i * 4 + 3)
        }
        dos.writeShort(0) // instructionLength = 0

        val nPts = rects.size * 4
        for (i in 0 until nPts) {
            dos.writeByte(1) // ON_CURVE, 16-bit signed deltas
        }
        var prevX = 0
        for (r in rects) {
            val xs = intArrayOf(r.x1, r.x1, r.x2, r.x2)
            for (x in xs) {
                dos.writeShort(x - prevX)
                prevX = x
            }
        }
        var prevY = 0
        for (r in rects) {
            val ys = intArrayOf(r.y1, r.y2, r.y2, r.y1)
            for (y in ys) {
                dos.writeShort(y - prevY)
                prevY = y
            }
        }
        while (baos.size() % 4 != 0) {
            dos.writeByte(0)
        }
        return baos.toByteArray()
    }

    private fun buildTtfBytes(fontName: String, charRects: Map<Int, List<RectI>>): ByteArray {
        val cps = charRects.keys.filter { it != 32 }.sorted()
        val glyphOrder = ArrayList<Int>(cps.size + 2).apply {
            add(0)  // .notdef
            add(32) // space
            addAll(cps)
        }
        val cpToGid = HashMap<Int, Int>(cps.size + 1)
        cpToGid[32] = 1
        for (i in cps.indices) {
            cpToGid[cps[i]] = i + 2
        }
        val numGlyphs = glyphOrder.size

        val glyfOut = ByteArrayOutputStream()
        val locaOut = ByteArrayOutputStream()
        val locaDos = DataOutputStream(locaOut)
        val hmtxOut = ByteArrayOutputStream()
        val hmtxDos = DataOutputStream(hmtxOut)

        var maxPoints = 0
        var maxContours = 0

        for (gid in 0 until numGlyphs) {
            val cp = glyphOrder[gid]
            locaDos.writeInt(glyfOut.size())
            val rects: List<RectI>
            val adv: Int
            val lsb: Int
            if (gid == 0) {
                rects = listOf(RectI(80, 0, 520, 720))
                adv = 600
                lsb = 80
            } else if (cp == 32) {
                rects = emptyList()
                adv = 400
                lsb = 0
            } else {
                rects = charRects[cp].orEmpty()
                if (rects.isNotEmpty()) {
                    val xMax = rects.maxOf { max(it.x1, it.x2) }
                    val xMin = rects.minOf { min(it.x1, it.x2) }
                    adv = max(440, xMax + 70)
                    lsb = xMin
                } else {
                    adv = 400
                    lsb = 0
                }
            }
            val gdata = buildSimpleGlyph(rects)
            glyfOut.write(gdata)
            hmtxDos.writeShort(adv)
            hmtxDos.writeShort(lsb)
            maxPoints = max(maxPoints, rects.size * 4)
            maxContours = max(maxContours, rects.size)
        }
        locaDos.writeInt(glyfOut.size())

        // cmap format 4
        val mappedCps = cpToGid.keys.sorted()
        val segments = ArrayList<CmapSeg>()
        for (cp in mappedCps) {
            val gid = cpToGid[cp] ?: 0
            val delta = (gid - cp) and 0xFFFF
            if (segments.isNotEmpty() && cp == segments.last().end + 1 && delta == segments.last().delta) {
                segments.last().end = cp
            } else {
                segments.add(CmapSeg(cp, cp, delta))
            }
        }
        segments.add(CmapSeg(0xFFFF, 0xFFFF, 1))
        val segCount = segments.size
        val segCountX2 = segCount * 2
        var highestPow2 = 1
        var entrySelector = 0
        while (highestPow2 * 2 <= segCount) {
            highestPow2 *= 2
            entrySelector++
        }
        val searchRange = highestPow2 * 2
        val rangeShift = segCountX2 - searchRange

        val fmt4Out = ByteArrayOutputStream()
        val fmt4 = DataOutputStream(fmt4Out)
        fmt4.writeShort(4)
        fmt4.writeShort(16 + segCount * 8)
        fmt4.writeShort(0)
        fmt4.writeShort(segCountX2)
        fmt4.writeShort(searchRange)
        fmt4.writeShort(entrySelector)
        fmt4.writeShort(rangeShift)
        for (s in segments) fmt4.writeShort(s.end)
        fmt4.writeShort(0)
        for (s in segments) fmt4.writeShort(s.start)
        for (s in segments) fmt4.writeShort(s.delta)
        for (s in segments) fmt4.writeShort(0)
        val fmt4Bytes = fmt4Out.toByteArray()

        val cmapOut = ByteArrayOutputStream()
        val cmap = DataOutputStream(cmapOut)
        cmap.writeShort(0)
        cmap.writeShort(2)
        cmap.writeShort(0); cmap.writeShort(3); cmap.writeInt(20)
        cmap.writeShort(3); cmap.writeShort(1); cmap.writeInt(20)
        cmap.write(fmt4Bytes)

        // name table
        val safePs = fontName.filter { it.isLetterOrDigit() && it.code < 128 }.ifEmpty { "MediaTokCustom" }
        val namePairs = listOf(
            1 to fontName,
            2 to "Regular",
            3 to "MediaTok-$safePs",
            4 to fontName,
            5 to "Version 1.0",
            6 to safePs
        )
        val records = ArrayList<TtfNameRec>()
        val strBlob = ByteArrayOutputStream()
        for ((nid, s) in namePairs) {
            val b = s.map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }.toByteArray()
            records.add(TtfNameRec(1, 0, 0, nid, b.size, strBlob.size()))
            strBlob.write(b)
        }
        for ((nid, s) in namePairs) {
            val b = s.toByteArray(StandardCharsets.UTF_16BE)
            records.add(TtfNameRec(3, 1, 0x0409, nid, b.size, strBlob.size()))
            strBlob.write(b)
        }
        records.sortWith(compareBy({ it.pid }, { it.eid }, { it.lid }, { it.nid }))
        val nameOut = ByteArrayOutputStream()
        val nameDos = DataOutputStream(nameOut)
        nameDos.writeShort(0)
        nameDos.writeShort(records.size)
        nameDos.writeShort(6 + records.size * 12)
        for (r in records) {
            nameDos.writeShort(r.pid)
            nameDos.writeShort(r.eid)
            nameDos.writeShort(r.lid)
            nameDos.writeShort(r.nid)
            nameDos.writeShort(r.len)
            nameDos.writeShort(r.off)
        }
        nameDos.write(strBlob.toByteArray())

        // head (54 bytes)
        val headOut = ByteArrayOutputStream()
        val head = DataOutputStream(headOut)
        head.writeInt(0x00010000)
        head.writeInt(0x00010000)
        head.writeInt(0) // checkSumAdjustment filled later
        head.writeInt(0x5F0F3CF5)
        head.writeShort(0x000B)
        head.writeShort(1000)
        head.writeLong(0L)
        head.writeLong(0L)
        head.writeShort(0)
        head.writeShort(-200)
        head.writeShort(1000)
        head.writeShort(900)
        head.writeShort(0)
        head.writeShort(8)
        head.writeShort(2)
        head.writeShort(1) // indexToLocFormat = 32-bit
        head.writeShort(0)

        // hhea (36 bytes)
        val hheaOut = ByteArrayOutputStream()
        val hhea = DataOutputStream(hheaOut)
        hhea.writeInt(0x00010000)
        hhea.writeShort(850)
        hhea.writeShort(-200)
        hhea.writeShort(50)
        hhea.writeShort(1000)
        hhea.writeShort(0)
        hhea.writeShort(0)
        hhea.writeShort(1000)
        hhea.writeShort(1)
        hhea.writeShort(0)
        hhea.writeShort(0)
        hhea.writeShort(0)
        hhea.writeShort(0)
        hhea.writeShort(0)
        hhea.writeShort(0)
        hhea.writeShort(0)
        hhea.writeShort(numGlyphs)

        // maxp (32 bytes)
        val maxpOut = ByteArrayOutputStream()
        val maxp = DataOutputStream(maxpOut)
        maxp.writeInt(0x00010000)
        maxp.writeShort(numGlyphs)
        maxp.writeShort(maxPoints)
        maxp.writeShort(maxContours)
        maxp.writeShort(0); maxp.writeShort(0); maxp.writeShort(2)
        for (k in 0 until 8) maxp.writeShort(0)

        // OS/2 v3 (96 bytes)
        val os2Out = ByteArrayOutputStream()
        val os2 = DataOutputStream(os2Out)
        os2.writeShort(3) // version
        os2.writeShort(600) // xAvgCharWidth
        os2.writeShort(400) // usWeightClass
        os2.writeShort(5) // usWidthClass
        os2.writeShort(0) // fsType
        os2.writeShort(650); os2.writeShort(600); os2.writeShort(0); os2.writeShort(75)
        os2.writeShort(650); os2.writeShort(600); os2.writeShort(0); os2.writeShort(350)
        os2.writeShort(50); os2.writeShort(300); os2.writeShort(0)
        os2.write(byteArrayOf(2, 0, 5, 3, 0, 0, 0, 0, 0, 0)) // panose
        os2.writeInt(0x00000201); os2.writeInt(0); os2.writeInt(0); os2.writeInt(0) // Latin + Cyrillic
        os2.write("MTOK".toByteArray(StandardCharsets.US_ASCII))
        os2.writeShort(0x0040) // REGULAR
        os2.writeShort(32)
        os2.writeShort(min(0xFFFF, mappedCps.maxOrNull() ?: 0x04FF))
        os2.writeShort(850); os2.writeShort(-200); os2.writeShort(50)
        os2.writeShort(900); os2.writeShort(250)
        os2.writeInt(0x00000005); os2.writeInt(0)
        os2.writeShort(500); os2.writeShort(720)
        os2.writeShort(0); os2.writeShort(32); os2.writeShort(0)

        // post v3.0 (32 bytes)
        val postOut = ByteArrayOutputStream()
        val post = DataOutputStream(postOut)
        post.writeInt(0x00030000)
        post.writeInt(0)
        post.writeShort(-100)
        post.writeShort(50)
        for (k in 0 until 5) post.writeInt(0)

        val tables = linkedMapOf(
            "OS/2" to os2Out.toByteArray(),
            "cmap" to cmapOut.toByteArray(),
            "glyf" to glyfOut.toByteArray(),
            "head" to headOut.toByteArray(),
            "hhea" to hheaOut.toByteArray(),
            "hmtx" to hmtxOut.toByteArray(),
            "loca" to locaOut.toByteArray(),
            "maxp" to maxpOut.toByteArray(),
            "name" to nameOut.toByteArray(),
            "post" to postOut.toByteArray()
        )
        val sortedTags = tables.keys.sorted()
        val nt = sortedTags.size
        var pow2 = 1
        var es = 0
        while (pow2 * 2 <= nt) {
            pow2 *= 2
            es++
        }
        val sr = pow2 * 16
        val rs = nt * 16 - sr

        val fullOut = ByteArrayOutputStream()
        val fullDos = DataOutputStream(fullOut)
        fullDos.writeInt(0x00010000)
        fullDos.writeShort(nt)
        fullDos.writeShort(sr)
        fullDos.writeShort(es)
        fullDos.writeShort(rs)

        var offset = 12 + nt * 16
        var headOffset = 0
        val bodyOut = ByteArrayOutputStream()
        for (tag in sortedTags) {
            val data = tables[tag]!!
            val cs = calcChecksum(data)
            fullDos.write(tag.toByteArray(StandardCharsets.US_ASCII))
            fullDos.writeInt(cs.toInt())
            fullDos.writeInt(offset)
            fullDos.writeInt(data.size)
            if (tag == "head") headOffset = offset
            bodyOut.write(data)
            val pad = (4 - (data.size % 4)) % 4
            for (p in 0 until pad) bodyOut.write(0)
            offset += data.size + pad
        }
        fullDos.write(bodyOut.toByteArray())
        val finalBytes = fullOut.toByteArray()
        val adj = (0xB1B0AFBAL - calcChecksum(finalBytes)) and 0xFFFFFFFFL
        finalBytes[headOffset + 8] = ((adj shr 24) and 0xFF).toByte()
        finalBytes[headOffset + 9] = ((adj shr 16) and 0xFF).toByte()
        finalBytes[headOffset + 10] = ((adj shr 8) and 0xFF).toByte()
        finalBytes[headOffset + 11] = (adj and 0xFF).toByte()
        return finalBytes
    }

    // ===================== INTERACTIVE GLYPH DRAWING CANVAS =====================

    private class GlyphCanvasView(
        ctx: Context,
        private var grid: BooleanArray,
        private val onChanged: () -> Unit
    ) : View(ctx) {
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, 140, 140, 140)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        private val baseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 255, 80, 80)
            strokeWidth = 3f
        }
        private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton)
            style = Paint.Style.FILL
        }
        private var paintMode = true // true = draw, false = erase

        fun setGrid(newGrid: BooleanArray) {
            grid = newGrid
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val sizeW = AndroidUtilities.dp(240f)
            val sizeH = AndroidUtilities.dp(288f)
            setMeasuredDimension(sizeW, sizeH)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cw = width.toFloat() / GRID_W
            val ch = height.toFloat() / GRID_H
            for (r in 0 until GRID_H) {
                for (c in 0 until GRID_W) {
                    val left = c * cw
                    val top = r * ch
                    if (grid[r * GRID_W + c]) {
                        canvas.drawRect(left + 2f, top + 2f, left + cw - 2f, top + ch - 2f, cellPaint)
                    }
                    canvas.drawRect(left, top, left + cw, top + ch, gridPaint)
                }
            }
            // Baseline guide at row 10
            val baseY = 10f * ch
            canvas.drawLine(0f, baseY, width.toFloat(), baseY, baseLinePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val c = (event.x / (width.toFloat() / GRID_W)).toInt().coerceIn(0, GRID_W - 1)
            val r = (event.y / (height.toFloat() / GRID_H)).toInt().coerceIn(0, GRID_H - 1)
            val idx = r * GRID_W + c
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    paintMode = !grid[idx]
                    grid[idx] = paintMode
                    invalidate()
                    onChanged()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (grid[idx] != paintMode) {
                        grid[idx] = paintMode
                        invalidate()
                        onChanged()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private class LivePreviewStrip(
        ctx: Context,
        private val grids: Map<Char, BooleanArray>
    ) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.getColor(Theme.key_dialogTextBlack)
            style = Paint.Style.FILL
        }
        var sample: String = "Привет MediaTok 123"

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, AndroidUtilities.dp(44f))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cell = AndroidUtilities.dp(2.6f).toFloat().coerceAtLeast(2f)
            var x = AndroidUtilities.dp(8f).toFloat()
            val y0 = AndroidUtilities.dp(6f).toFloat()
            for (ch in sample) {
                if (ch == ' ') {
                    x += cell * 5f
                    continue
                }
                val g = grids[ch] ?: continue
                var maxCol = 0
                for (r in 0 until GRID_H) {
                    for (c in 0 until GRID_W) {
                        if (g[r * GRID_W + c]) {
                            canvas.drawRect(x + c * cell, y0 + r * cell, x + (c + 1) * cell, y0 + (r + 1) * cell, paint)
                            if (c > maxCol) maxCol = c
                        }
                    }
                }
                x += (max(4, maxCol + 2)) * cell
                if (x > width - cell * 4) break
            }
        }
    }

    // ===================== FONT CREATOR DIALOG =====================

    @JvmStatic
    fun openFontCreatorDialog(context: Context?) {
        val ctx = context ?: LaunchActivity.getLastFragment()?.parentActivity ?: return
        val grids = rasterizeTemplate(MediaTokFontHelper.getRegularTypeface() ?: Typeface.SANS_SERIF)
        val editedChars = HashSet<Char>()
        var currentChar = 'А'
        var currentStyle = PixelStyle.SOLID
        var lastBuiltFile: File? = null

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = AndroidUtilities.dp(12f)
            setPadding(p, AndroidUtilities.dp(4f), p, AndroidUtilities.dp(4f))
        }

        val nameInput = EditTextBoldCursor(ctx).apply {
            hint = "Название твоего шрифта (напр. МойПиксель)"
            setText("Мой Шрифт")
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
            isSingleLine = true
        }
        root.addView(nameInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 4f))

        // Style row
        val styleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val styleButtons = ArrayList<TextView>()
        val previewStrip = LivePreviewStrip(ctx, grids)
        val canvasView = GlyphCanvasView(ctx, grids[currentChar]!!) {
            editedChars.add(currentChar)
            previewStrip.invalidate()
        }

        fun updateStyleButtons() {
            val accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton)
            val muted = Color.argb(26, 128, 128, 128)
            val normalText = Theme.getColor(Theme.key_dialogTextBlack)
            for ((idx, st) in PixelStyle.values().withIndex()) {
                val tv = styleButtons[idx]
                val sel = st == currentStyle
                tv.background = GradientDrawable().apply {
                    setColor(if (sel) accent else muted)
                    cornerRadius = AndroidUtilities.dp(8f).toFloat()
                }
                tv.setTextColor(if (sel) Color.WHITE else normalText)
            }
        }

        for (st in PixelStyle.values()) {
            val btn = TextView(ctx).apply {
                text = st.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(5f), AndroidUtilities.dp(6f), AndroidUtilities.dp(5f))
                setOnClickListener {
                    currentStyle = st
                    updateStyleButtons()
                }
            }
            styleButtons.add(btn)
            styleRow.addView(btn, LayoutHelper.createLinear(0, 30, 1f, 2, 0, 2, 0))
        }
        updateStyleButtons()
        root.addView(styleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 6f))

        // Live preview strip
        previewStrip.background = GradientDrawable().apply {
            setColor(Color.argb(18, 128, 128, 128))
            cornerRadius = AndroidUtilities.dp(8f).toFloat()
        }
        root.addView(previewStrip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0f, 0f, 0f, 6f))

        // Character selector strip
        val charBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val charViews = HashMap<Char, TextView>()
        val charScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = true
            addView(charBar)
        }

        fun refreshCharSelection() {
            val accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton)
            val editedBg = Color.argb(48, 46, 204, 113)
            val muted = Color.argb(24, 128, 128, 128)
            val normal = Theme.getColor(Theme.key_dialogTextBlack)
            for ((ch, tv) in charViews) {
                val isCur = ch == currentChar
                val bg = when {
                    isCur -> accent
                    editedChars.contains(ch) -> editedBg
                    else -> muted
                }
                tv.background = GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = AndroidUtilities.dp(6f).toFloat()
                }
                tv.setTextColor(if (isCur) Color.WHITE else normal)
            }
        }

        for (ch in CHARSET) {
            val tv = TextView(ctx).apply {
                text = ch.toString()
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    currentChar = ch
                    canvasView.setGrid(grids[ch]!!)
                    refreshCharSelection()
                }
            }
            charViews[ch] = tv
            charBar.addView(tv, LayoutHelper.createLinear(34, 34, 2f, 0f, 2f, 0f))
        }
        refreshCharSelection()
        root.addView(charScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0f, 0f, 0f, 6f))

        // Center canvas
        val canvasHolder = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(canvasView)
        }
        root.addView(canvasHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 6f))

        // Tool buttons
        val toolsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun addTool(label: String, onClick: () -> Unit) {
            val tv = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                background = GradientDrawable().apply {
                    setColor(Color.argb(26, 128, 128, 128))
                    cornerRadius = AndroidUtilities.dp(8f).toFloat()
                }
                setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(6f), AndroidUtilities.dp(6f), AndroidUtilities.dp(6f))
                setOnClickListener { onClick() }
            }
            toolsRow.addView(tv, LayoutHelper.createLinear(0, 32, 1f, 2, 0, 2, 0))
        }

        addTool("Очистить") {
            grids[currentChar]!!.fill(false)
            editedChars.add(currentChar)
            canvasView.invalidate()
            previewStrip.invalidate()
            refreshCharSelection()
        }
        addTool("Зеркало") {
            val g = grids[currentChar]!!
            for (r in 0 until GRID_H) {
                for (c in 0 until GRID_W / 2) {
                    val tmp = g[r * GRID_W + c]
                    g[r * GRID_W + c] = g[r * GRID_W + (GRID_W - 1 - c)]
                    g[r * GRID_W + (GRID_W - 1 - c)] = tmp
                }
            }
            editedChars.add(currentChar)
            canvasView.invalidate()
            previewStrip.invalidate()
            refreshCharSelection()
        }
        addTool("Жирнее") {
            val g = grids[currentChar]!!
            val copy = g.copyOf()
            for (r in 0 until GRID_H) {
                for (c in 0 until GRID_W - 1) {
                    if (copy[r * GRID_W + c]) g[r * GRID_W + c + 1] = true
                }
            }
            editedChars.add(currentChar)
            canvasView.invalidate()
            previewStrip.invalidate()
            refreshCharSelection()
        }
        addTool("Сброс всех") {
            val fresh = rasterizeTemplate(Typeface.SANS_SERIF)
            for ((k, v) in fresh) grids[k] = v
            editedChars.clear()
            canvasView.setGrid(grids[currentChar]!!)
            previewStrip.invalidate()
            refreshCharSelection()
        }
        root.addView(toolsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 4f))

        val hintTv = TextView(ctx).apply {
            text = "Рисуй пальцем по клеткам (красная линия — низ строки). Все 139 букв А–Я, A–Z, 0–9 готовы из шаблона — перерисуй нужные и жми «Собрать .ttf»!"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }
        root.addView(hintTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 2f))

        val scroll = ScrollView(ctx).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun buildAndSave(): File? {
            return try {
                val rawName = nameInput.text?.toString()?.trim().orEmpty().ifEmpty { "MyFont" }
                val safeFile = rawName.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ_\\- ]"), "")
                    .trim().replace(' ', '_').take(32).ifEmpty { "custom_font" }
                val bytes = compileTtf(rawName, grids, currentStyle)
                val appCtx = ApplicationLoader.applicationContext ?: return null
                val dir = File(appCtx.filesDir, "mediatok_fonts")
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, "$safeFile.ttf")
                FileOutputStream(outFile).use { it.write(bytes) }
                // Verify Android FreeType/Skia parses it cleanly
                val check = Typeface.createFromFile(outFile)
                if (check == null) {
                    toast("Ошибка проверки созданного .ttf")
                    return null
                }
                lastBuiltFile = outFile
                outFile
            } catch (e: Throwable) {
                FileLog.e(e)
                toast("Ошибка сборки .ttf: ${e.message}")
                null
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("Создать свой шрифт (.ttf)")
            .setView(scroll)
            .setPositiveButton("Собрать .ttf и применить") { _, _ ->
                val f = buildAndSave() ?: return@setPositiveButton
                MediaTokFontHelper.setUiFontId("custom:" + f.name)
                toast("Шрифт «${f.name}» собран (${f.length() / 1024} КБ) и применён!")
                AlertDialog.Builder(ctx)
                    .setTitle("Шрифт собран!")
                    .setMessage("Файл ${f.name} сохранён и выбран. Перезапустить MediaTok для применения везде?")
                    .setPositiveButton("Перезапустить") { _, _ -> AppRestartHelper.triggerRebirth() }
                    .setNeutralButton("Поделиться .ttf") { _, _ ->
                        try {
                            ShareUtil.shareFile(ctx, f)
                        } catch (_: Throwable) {
                        }
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            }
            .setNeutralButton("Поделиться .ttf") { _, _ ->
                val f = lastBuiltFile ?: buildAndSave() ?: return@setNeutralButton
                try {
                    ShareUtil.shareFile(ctx, f)
                } catch (_: Throwable) {
                    toast("Файл сохранён: ${f.absolutePath}")
                }
            }
            .setNegativeButton(LocaleController.getString(R.string.Close), null)
            .show()
    }

    private fun toast(msg: String) {
        AndroidUtilities.runOnUIThread {
            val ctx = ApplicationLoader.applicationContext ?: return@runOnUIThread
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
