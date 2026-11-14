package org.vera20k

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * A viewer for the PNG/JPG files in the installation — loading screens, cameo
 * art, map previews, and whatever a mod dropped in.
 *
 * Decoding is sampled to the screen and the bitmap is kept at most one screen's
 * worth of pixels per axis: a 4096×4096 SHP export would otherwise need 64 MB
 * to show a phone-sized picture. Pinch zooms, drag pans, double tap fits again.
 */
class ImageViewerActivity : Activity() {
    private companion object {
        const val EXTRA_PATH = "path"

        /** Above this the sampler steps down; below it zooming stays sharp enough. */
        const val MAX_DIMENSION = 2048
    }

    private lateinit var image: ImageView
    private var bitmap: Bitmap? = null
    private var fitScale = 1f
    private val matrix = Matrix()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COL_BG
        window.navigationBarColor = COL_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        val path = intent?.getStringExtra(EXTRA_PATH).orEmpty()
        val file = File(path)
        if (!file.isFile) {
            finish()
            return
        }
        setContentView(buildLayout(file))
        load(file)
    }

    private fun buildLayout(file: File): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(18), dp(10))
        }

        val back = TextView(this).apply {
            text = "‹"
            sp(22f)
            gravity = Gravity.CENTER
            setTextColor(COL_TEXT)
            background = ripple(square(COL_SURFACE_HI, 11))
            contentDescription = "返回"
            setOnClickListener { finish() }
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val title = TextView(this).apply {
            text = file.name
            sp(15f)
            setTextColor(COL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val subtitle = TextView(this).apply {
            text = formatSize(file.length())
            sp(11f)
            setTextColor(COL_TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        }
        titles.addView(title)
        titles.addView(subtitle)

        header.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(0xFF07080A.toInt())
        }

        val scroller = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                applyMatrix()
                return true
            }
        })

        var lastX = 0f
        var lastY = 0f
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (fitScale <= 0f) return false
                val target = if (matrix.mapRadius(1f) / fitScale > 1.35f) 1f else 2.5f
                matrix.set(fit())
                matrix.postScale(target, target, event.x, event.y)
                applyMatrix()
                return true
            }
        })

        image.setOnTouchListener { _, event ->
            scroller.onTouchEvent(event)
            gestures.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scroller.isInProgress && event.pointerCount == 1) {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                        applyMatrix()
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
            true
        }

        root.addView(header)
        root.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return root
    }

    private fun load(file: File) {
        Thread {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            var sample = 1
            while (options.outWidth / sample > MAX_DIMENSION || options.outHeight / sample > MAX_DIMENSION) {
                sample *= 2
            }
            val decoded = try {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (decoded == null) {
                    image.visibility = ImageView.GONE
                    val message = TextView(this).apply {
                        text = "这张图片无法解码"
                        sp(13f)
                        setTextColor(COL_TEXT_DIM)
                        gravity = Gravity.CENTER
                    }
                    (image.parent as? ViewGroup)?.addView(message)
                    return@runOnUiThread
                }
                bitmap = decoded
                image.setImageBitmap(decoded)
                image.post { fitOnce() }
            }
        }.start()
    }

    private fun fitOnce() {
        if (image.width == 0 || image.height == 0) return
        matrix.set(fit())
        applyMatrix()
    }

    /** Uniform scale that puts the whole picture inside the view, centred. */
    private fun fit(): Matrix {
        val bitmap = bitmap ?: return Matrix()
        val b = Matrix()
        if (image.width == 0 || image.height == 0) return b
        val scale = minOf(
            image.width.toFloat() / bitmap.width,
            image.height.toFloat() / bitmap.height,
        )
        fitScale = scale
        b.postScale(scale, scale)
        b.postTranslate(
            (image.width - bitmap.width * scale) / 2f,
            (image.height - bitmap.height * scale) / 2f,
        )
        return b
    }

    private fun applyMatrix() {
        image.imageMatrix = matrix
        image.invalidate()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
