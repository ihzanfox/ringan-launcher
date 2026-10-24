package id.ringan.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ikon cuaca beranimasi yang digambar langsung ke Canvas, tanpa gambar atau library.
 * Awan bergoyang secepat angin yang sebenarnya, dan banyaknya tetes mengikuti intensitas hujan.
 * Hanya digambar ulang saat jendelanya terlihat, 20 kali per detik: gerakannya lambat, jadi
 * tetap halus, dan TV tidak perlu menyusun ulang layar 30 kali per detik.
 * Semua koordinat memakai kotak 48x48 yang diskalakan ke ukuran view.
 */
class WeatherView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Scene { SUN, MOON, PARTLY_DAY, PARTLY_NIGHT, CLOUD, RAIN, STORM, SNOW, FOG }

    private var scene = Scene.CLOUD
    private var windKmh = 0f
    private var intensity = 0f
    private var animating = false

    /** False untuk ikon kecil (prakiraan per jam): cukup satu gambar diam, tanpa beban animasi. */
    var animated = true
        set(value) {
            field = value
            invalidate()
        }
    private val startedAt = SystemClock.uptimeMillis()

    private val sunPaint = fill(R.color.amber)
    private val glowPaint = fill(R.color.amber)
    private val rayPaint = stroke(R.color.amber, 2.4f)
    private val starPaint = fill(R.color.flap_text)
    private val cloudPaint = fill(R.color.cloud)
    private val darkCloudPaint = fill(R.color.cloud_shade)
    private val rainPaint = stroke(R.color.rain, 1.8f)
    private val snowPaint = fill(R.color.cloud)
    private val fogPaint = stroke(R.color.cloud, 2.6f)

    private val cloudPath = Path()
    private val moonPath = Path()
    private val moonCut = Path()
    private val boltPath = Path().apply {
        moveTo(26f, 25f)
        lineTo(20f, 35f)
        lineTo(24.5f, 35f)
        lineTo(22f, 45f)
        lineTo(31f, 31f)
        lineTo(26.5f, 31f)
        lineTo(29.5f, 25f)
        close()
    }

    /** [intensity] 0..1 menentukan banyaknya tetes hujan atau salju. */
    fun setWeather(scene: Scene, windKmh: Float, intensity: Float) {
        this.scene = scene
        this.windKmh = windKmh
        this.intensity = intensity.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        animating = visibility == VISIBLE
        if (animating) invalidate()
    }

    override fun onDetachedFromWindow() {
        animating = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val t = if (animated) (SystemClock.uptimeMillis() - startedAt) / 1000f else STILL_FRAME_S
        val unit = min(width, height) / GRID
        canvas.save()
        canvas.translate((width - unit * GRID) / 2, (height - unit * GRID) / 2)
        canvas.scale(unit, unit)
        when (scene) {
            Scene.SUN -> drawSun(canvas, 24f, 24f, 9f, t)
            Scene.MOON -> drawMoon(canvas, 24f, 24f, 11f, t, withStars = true)
            Scene.PARTLY_DAY -> {
                drawSun(canvas, 17f, 16f, 7f, t)
                drawCloud(canvas, 28f + sway(t, 1.5f), 31f, 1f, cloudPaint)
            }
            Scene.PARTLY_NIGHT -> {
                drawMoon(canvas, 18f, 16f, 8f, t, withStars = false)
                drawCloud(canvas, 28f + sway(t, 1.5f), 31f, 1f, cloudPaint)
            }
            Scene.CLOUD -> {
                drawCloud(canvas, 18f + sway(t + 1.3f, 2f), 20f, 0.75f, darkCloudPaint)
                drawCloud(canvas, 27f + sway(t, 2.5f), 30f, 1f, cloudPaint)
            }
            Scene.RAIN -> {
                drawRain(canvas, t)
                drawCloud(canvas, 24f + sway(t, 1.5f), 19f, 1.05f, cloudPaint)
            }
            Scene.STORM -> {
                drawRain(canvas, t)
                drawCloud(canvas, 24f + sway(t, 1.5f), 19f, 1.05f, darkCloudPaint)
                drawBolt(canvas, t)
            }
            Scene.SNOW -> {
                drawSnow(canvas, t)
                drawCloud(canvas, 24f + sway(t, 1.5f), 19f, 1.05f, cloudPaint)
            }
            Scene.FOG -> drawFog(canvas, t)
        }
        canvas.restore()
        if (animating && animated) postInvalidateDelayed(FRAME_MS)
    }

    /** Goyangan kiri-kanan; makin kencang anginnya, makin cepat. */
    private fun sway(t: Float, amplitude: Float) = sin(t * (0.5f + windKmh / 15f)) * amplitude

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val pulse = (sin(t * 2f) + 1f) / 2f
        glowPaint.alpha = (40 + 40 * pulse).toInt()
        canvas.drawCircle(cx, cy, r * 1.55f + pulse * 1.5f, glowPaint)
        rayPaint.strokeWidth = r * 0.27f
        val turn = t * SUN_DEGREES_PER_SECOND
        for (i in 0 until 8) {
            val angle = Math.toRadians((turn + i * 45f).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            canvas.drawLine(cx + dx * r * 1.35f, cy + dy * r * 1.35f, cx + dx * r * 1.8f, cy + dy * r * 1.8f, rayPaint)
        }
        canvas.drawCircle(cx, cy, r, sunPaint)
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, withStars: Boolean) {
        moonPath.reset()
        moonPath.addCircle(cx, cy, r, Path.Direction.CW)
        moonCut.reset()
        moonCut.addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW)
        moonPath.op(moonCut, Path.Op.DIFFERENCE)
        canvas.drawPath(moonPath, sunPaint)
        if (!withStars) return
        for (i in STARS.indices step 2) {
            val twinkle = (sin(t * 1.7f + i * 1.1f) + 1f) / 2f
            starPaint.alpha = (60 + 195 * twinkle).toInt()
            canvas.drawCircle(STARS[i], STARS[i + 1], 0.9f + twinkle * 0.9f, starPaint)
        }
    }

    /** Awan dari tiga lingkaran dan alas membulat; lebarnya sekitar 28 satuan pada [scale] 1. */
    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, scale: Float, paint: Paint) {
        val s = scale
        cloudPath.reset()
        cloudPath.addCircle(cx - 7f * s, cy + 1f * s, 6f * s, Path.Direction.CW)
        cloudPath.addCircle(cx + 1f * s, cy - 3f * s, 8.5f * s, Path.Direction.CW)
        cloudPath.addCircle(cx + 9f * s, cy + 2f * s, 5.5f * s, Path.Direction.CW)
        cloudPath.addRoundRect(cx - 13f * s, cy + 1f * s, cx + 14.5f * s, cy + 7.5f * s, 3.25f * s, 3.25f * s, Path.Direction.CW)
        canvas.drawPath(cloudPath, paint)
    }

    private fun drawRain(canvas: Canvas, t: Float) {
        val drops = 3 + (intensity * 5).toInt()
        // Angin memiringkan tetes hujan.
        val slant = (windKmh / 40f).coerceAtMost(0.5f)
        for (i in 0 until drops) {
            val x = 13f + i * (22f / (drops - 1))
            val phase = (t * 1.6f + i * 0.37f) % 1f
            val y = 27f + phase * 16f
            rainPaint.alpha = (255 * (1f - phase * 0.6f)).toInt()
            canvas.drawLine(x - slant * (y - 27f), y, x - slant * (y + 4f - 27f), y + 4f, rainPaint)
        }
    }

    private fun drawSnow(canvas: Canvas, t: Float) {
        val flakes = 3 + (intensity * 4).toInt()
        for (i in 0 until flakes) {
            val phase = (t * 0.5f + i * 0.29f) % 1f
            val x = 13f + i * (22f / (flakes - 1)) + sin(t * 2f + i) * 1.5f
            snowPaint.alpha = (255 * (1f - phase * 0.5f)).toInt()
            canvas.drawCircle(x, 28f + phase * 16f, 1.4f, snowPaint)
        }
    }

    /** Kilat dua kali berturut-turut tiap beberapa detik. */
    private fun drawBolt(canvas: Canvas, t: Float) {
        val p = t % BOLT_PERIOD_S
        if (p < 0.12f || p in 0.22f..0.32f) canvas.drawPath(boltPath, sunPaint)
    }

    private fun drawFog(canvas: Canvas, t: Float) {
        for (i in 0 until 4) {
            val dx = sin(t * 0.8f + i * 1.3f) * 3f
            val y = 14f + i * 7f
            val inset = if (i % 2 == 0) 6f else 10f
            canvas.drawLine(inset + dx, y, GRID - inset + dx, y, fogPaint)
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = context.colorOf(color)
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = context.colorOf(color)
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    private companion object {
        const val GRID = 48f
        const val FRAME_MS = 50L
        const val SUN_DEGREES_PER_SECOND = 25f
        const val BOLT_PERIOD_S = 3.5f

        /** Saat diam, gambar diambil di momen yang memperlihatkan tetes hujan dan kilat. */
        const val STILL_FRAME_S = 0.05f

        /** Posisi bintang (x, y) di sekitar bulan. */
        val STARS = floatArrayOf(39f, 10f, 41f, 31f, 8f, 36f)
    }
}
