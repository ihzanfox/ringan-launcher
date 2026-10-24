package id.ringan.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Teks ala papan split-flap bandara: tiap huruf punya kotak sendiri dan berbalik acak
 * sebentar sebelum berhenti di huruf baru. Hanya digambar ulang selama animasi berjalan.
 */
class FlapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val cellCount: Int
    private val cellWidth: Float
    private val cellHeight: Float
    private val cellGap: Float
    private val cellRadius: Float

    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val rect = RectF()

    private val shown: CharArray
    private val target: CharArray
    private val flipsLeft: IntArray

    var textColor: Int
        get() = textPaint.color
        set(value) {
            textPaint.color = value
            invalidate()
        }

    private val step = object : Runnable {
        override fun run() {
            if (advance()) postDelayed(this, FRAME_MS)
            invalidate()
        }
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.FlapView)
        cellCount = a.getInt(R.styleable.FlapView_flapCells, 1)
        cellWidth = a.getDimension(R.styleable.FlapView_flapCellWidth, 20f)
        cellHeight = a.getDimension(R.styleable.FlapView_flapCellHeight, 30f)
        cellGap = a.getDimension(R.styleable.FlapView_flapCellGap, 2f)
        textPaint.textSize = a.getDimension(R.styleable.FlapView_flapTextSize, 18f)
        textPaint.color = a.getColor(R.styleable.FlapView_flapTextColor, 0xFFFFFFFF.toInt())
        a.recycle()

        cellRadius = cellWidth * 0.12f
        topPaint.color = context.colorOf(R.color.flap_top)
        bottomPaint.color = context.colorOf(R.color.flap_bottom)
        dividerPaint.color = context.colorOf(R.color.flap_divider)

        shown = CharArray(cellCount) { ' ' }
        target = CharArray(cellCount) { ' ' }
        flipsLeft = IntArray(cellCount)
    }

    /** Teks dipotong atau diberi spasi agar pas dengan jumlah kotak. */
    fun setText(text: CharSequence, animate: Boolean = true) {
        val upper = text.toString().uppercase(Locale.getDefault())
        var changed = false
        for (i in 0 until cellCount) {
            val c = upper.getOrElse(i) { ' ' }
            target[i] = c
            if (!animate) {
                shown[i] = c
                flipsLeft[i] = 0
            } else if (shown[i] != c) {
                // Kotak di kanan berhenti belakangan, seperti papan sungguhan.
                flipsLeft[i] = BASE_FLIPS + i
                changed = true
            }
        }
        contentDescription = text
        removeCallbacks(step)
        if (changed) post(step)
        invalidate()
    }

    private fun advance(): Boolean {
        var running = false
        for (i in 0 until cellCount) {
            if (flipsLeft[i] == 0) continue
            flipsLeft[i]--
            shown[i] = if (flipsLeft[i] == 0) target[i] else FLAP_CHARS[Random.nextInt(FLAP_CHARS.length)]
            running = running || flipsLeft[i] > 0
        }
        return running
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = paddingLeft + paddingRight + (cellCount * cellWidth + (cellCount - 1) * cellGap).roundToInt()
        val height = paddingTop + paddingBottom + cellHeight.roundToInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val top = paddingTop.toFloat()
        val middle = top + cellHeight / 2
        val baseline = middle - (textPaint.descent() + textPaint.ascent()) / 2
        for (i in 0 until cellCount) {
            val left = paddingLeft + i * (cellWidth + cellGap)
            rect.set(left, top, left + cellWidth, top + cellHeight)
            canvas.drawRoundRect(rect, cellRadius, cellRadius, bottomPaint)
            canvas.save()
            canvas.clipRect(left, top, left + cellWidth, middle)
            canvas.drawRoundRect(rect, cellRadius, cellRadius, topPaint)
            canvas.restore()
            if (shown[i] != ' ') canvas.drawText(shown, i, 1, left + cellWidth / 2, baseline, textPaint)
            canvas.drawRect(left, middle - DIVIDER_PX / 2, left + cellWidth, middle + DIVIDER_PX / 2, dividerPaint)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(step)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FLAP_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        const val BASE_FLIPS = 5
        const val FRAME_MS = 40L
        const val DIVIDER_PX = 2f
    }
}
