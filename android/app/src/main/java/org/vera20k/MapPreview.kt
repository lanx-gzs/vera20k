package org.vera20k

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.io.BufferedReader
import java.io.IOException
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

/**
 * What a map file says about itself, read without touching the tile data.
 *
 * A `.map` / `.mpr` / `.yrm` is an INI: `[Map]` carries Width and Height,
 * `[Waypoints]` 0..7 are the player start cells, and `[Units]`, `[Structures]`
 * and `[Triggers]` carry the rest. `[IsoMapPack5]` holds the packed tile array
 * and is skipped — its base64 lines are long, and decoding it needs an LZO
 * decompressor the app does not carry.
 *
 * World-Altering Editor (GPLv3, (C) Rami Pasanen / CnCNet) is the reference for
 * how these sections are laid out; see docs/Manual.md there for the field list.
 * This reader is original work, not a port of its code.
 */
internal data class MapInfo(
    val width: Int,
    val height: Int,
    val name: String?,
    val theater: String?,
    val starts: List<Pair<Int, Int>>,
    val units: Int,
    val infantry: Int,
    val aircraft: Int,
    val structures: Int,
    val terrain: Int,
    val triggers: Int,
    val houses: Int,
    val tags: Int,
    val lighting: String?,
    /** Sections the file actually carries, in the order they appeared. */
    val sections: List<String>,
    val parsed: Boolean,
    val note: String?,
)

internal object MapFile {
    fun read(file: File): MapInfo {
        val extension = file.name.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension == "mmx") {
            return MapInfo(
                0, 0, null, null, emptyList(),
                0, 0, 0, 0, 0, 0, 0, 0, null,
                emptyList(), false, "压缩地图（.mmx），预览需要先解压",
            )
        }
        var width = 0
        var height = 0
        var name: String? = null
        var theater: String? = null
        val starts = mutableListOf<Pair<Int, Int>>()
        var units = 0
        var infantry = 0
        var aircraft = 0
        var structures = 0
        var terrain = 0
        var triggers = 0
        var houses = 0
        var tags = 0
        var ambient: String? = null
        var section = ""
        val sections = mutableListOf<String>()
        try {
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.ISO_8859_1)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // Packed tile data and long base64 runs are of no use here.
                    if (line.length > 512) continue
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(";")) continue
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        section = trimmed.substring(1, trimmed.length - 1).lowercase(Locale.US)
                        sections += section
                        continue
                    }
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val key = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    when (section) {
                        "map" -> when (key.lowercase(Locale.US)) {
                            "width" -> width = value.toIntOrNull() ?: width
                            "height" -> height = value.toIntOrNull() ?: height
                            "theater" -> theater = value
                        }
                        "basic" -> if (key.equals("Name", true)) name = value
                        "waypoints" -> {
                            val index = key.toIntOrNull()
                            if (index != null && index in 0..7) {
                                val parts = value.split(',')
                                val x = parts.getOrNull(0)?.trim()?.toIntOrNull()
                                val y = parts.getOrNull(1)?.trim()?.toIntOrNull()
                                if (x != null && y != null) starts += x to y
                            }
                        }
                        "units" -> units++
                        "infantry" -> infantry++
                        "aircraft" -> aircraft++
                        "structures" -> structures++
                        "terrain" -> terrain++
                        "triggers" -> triggers++
                        "tags" -> tags++
                        "houses" -> houses++
                        "lighting" -> if (key.equals("Ambient", true)) ambient = value
                    }
                }
            }
        } catch (error: IOException) {
            return MapInfo(
                0, 0, null, null, emptyList(),
                0, 0, 0, 0, 0, 0, 0, 0, null,
                emptyList(), false, "读不出来：${error.message}",
            )
        }
        if (width <= 0 || height <= 0) {
            return MapInfo(
                0, 0, name, theater, starts,
                units, infantry, aircraft, structures, terrain, triggers, houses, tags, ambient,
                sections, false, "没有读到 [Map] 的宽高，可能不是标准地图",
            )
        }
        return MapInfo(
            width, height, name, theater, starts,
            units, infantry, aircraft, structures, terrain, triggers, houses, tags, ambient,
            sections, true, null,
        )
    }
}

/**
 * A schematic of the playfield: the map's outline, a light grid and the player
 * start cells. Deliberately not a terrain render — that needs the game's own
 * tile art from `*.mix`, which this app never carries.
 */
internal class MapPreviewView(context: Context, private val info: MapInfo) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101318.toInt()
        style = Paint.Style.FILL
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1FFFFFFF
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val start = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_ACCENT
        style = Paint.Style.FILL
    }

    /** Retail player colours, so a start dot says which slot it belongs to. */
    private val playerColors = intArrayOf(
        0xFFEFB44C.toInt(), 0xFF7FB3E8.toInt(), 0xFF6BD08A.toInt(), 0xFFE8837A.toInt(),
        0xFFC9A7E8.toInt(), 0xFF94D4D0.toInt(), 0xFFF4B393.toInt(), 0xFF9BA2AC.toInt(),
    )
    private val startHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33EFB44C
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COL_TEXT_DIM
        textSize = context.resources.displayMetrics.density * 10f
    }
    private val round = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val ratio = if (info.width > 0 && info.height > 0) {
            info.height.toFloat() / info.width.toFloat()
        } else {
            0.55f
        }
        val height = (width * ratio).toInt().coerceIn(
            (resources.displayMetrics.density * 120).toInt(),
            (resources.displayMetrics.density * 420).toInt(),
        )
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = resources.displayMetrics.density * 6f
        val frame = RectF(pad, pad, width - pad, height - pad)
        round.reset()
        round.addRoundRect(frame, pad * 2, pad * 2, Path.Direction.CW)
        canvas.drawRoundRect(frame, pad * 2, pad * 2, fill)
        canvas.drawRoundRect(frame, pad * 2, pad * 2, outline)

        if (!info.parsed || info.width <= 0 || info.height <= 0) return

        // Keep the map's aspect inside the frame.
        val inner = RectF(frame)
        val mapRatio = info.width.toFloat() / info.height.toFloat()
        val frameRatio = inner.width() / inner.height()
        if (mapRatio > frameRatio) {
            val targetHeight = inner.width() / mapRatio
            val dy = (inner.height() - targetHeight) / 2f
            inner.top += dy
            inner.bottom -= dy
        } else {
            val targetWidth = inner.height() * mapRatio
            val dx = (inner.width() - targetWidth) / 2f
            inner.left += dx
            inner.right -= dx
        }

        // A 4×4 grid: enough to read the shape without pretending to be terrain.
        for (i in 1 until 4) {
            val x = inner.left + inner.width() * i / 4f
            val y = inner.top + inner.height() * i / 4f
            canvas.drawLine(x, inner.top, x, inner.bottom, grid)
            canvas.drawLine(inner.left, y, inner.right, y, grid)
        }
        canvas.drawRect(inner, outline)

        val radius = resources.displayMetrics.density * 5f
        for ((slot, cell) in info.starts.withIndex()) {
            val (x, y) = cell
            val cx = inner.left + inner.width() * (x.coerceIn(0, info.width - 1).toFloat() / info.width)
            val cy = inner.top + inner.height() * (y.coerceIn(0, info.height - 1).toFloat() / info.height)
            start.color = playerColors[slot % playerColors.size]
            canvas.drawCircle(cx, cy, radius * 2f, startHalo)
            canvas.drawCircle(cx, cy, radius, start)
            canvas.drawText("${slot + 1}", cx + radius + pad, cy - radius + pad, label)
        }

        canvas.drawText("示意 · 非地形渲染 · 圆点=开始位置", inner.left + pad, inner.bottom - pad, label)
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }
}
