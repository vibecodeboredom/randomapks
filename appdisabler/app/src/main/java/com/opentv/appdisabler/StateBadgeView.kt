package com.opentv.appdisabler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * The row's icon: a single circle split diagonally down the middle,
 * 50/50 — one half red with a white "no-go" cross (✕) for disabled,
 * the other half green with a white check mark (✓) for enabled.
 *
 * It is also the state indicator: the half that matches the app's
 * current state is drawn at full strength, the other half is dimmed,
 * so the icon reads at a glance as "enabled" or "disabled".
 * The switch on the row shows the same state; the icon is the
 * at-a-glance version of it.
 */
class StateBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** True when the app this row stands for is currently disabled. */
    private var isAppDisabled = false

    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EF4444")
    }
    private val greenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#22C55E")
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val clipPath = Path()
    private val greenHalf = Path()
    private val redHalf = Path()

    /** Dimmed alpha for the half that does not match the current state. */
    private val dimAlpha = 90

    fun setDisabled(disabled: Boolean) {
        if (isAppDisabled == disabled) return
        isAppDisabled = disabled
        contentDescription = if (disabled) "Disabled" else "Enabled"
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = minOf(w, h).toFloat()
        if (s <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = s / 2f
        // Circle the icon is drawn inside.
        clipPath.reset()
        clipPath.addCircle(cx, cy, r, Path.Direction.CW)
        // Diagonal from the top-left corner to the bottom-right
        // corner of the bounding square splits the circle in two.
        // Upper-right triangle: green (enabled). Lower-left: red.
        greenHalf.reset()
        greenHalf.moveTo(0f, 0f)
        greenHalf.lineTo(w.toFloat(), 0f)
        greenHalf.lineTo(w.toFloat(), h.toFloat())
        greenHalf.close()
        redHalf.reset()
        redHalf.moveTo(0f, 0f)
        redHalf.lineTo(0f, h.toFloat())
        redHalf.lineTo(w.toFloat(), h.toFloat())
        redHalf.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val greenActive = !isAppDisabled
        val redActive = isAppDisabled

        canvas.save()
        canvas.clipPath(clipPath)
        // The active half is full strength, the other is dimmed.
        greenFill.alpha = if (greenActive) 255 else dimAlpha
        redFill.alpha = if (redActive) 255 else dimAlpha
        canvas.drawPath(greenHalf, greenFill)
        canvas.drawPath(redHalf, redFill)

        // Symbols, white, centred in each half-disc. The half-disc
        // centroid sits ~0.15 * size off centre along the diagonal.
        val off = s * 0.15f
        val cx = width / 2f
        val cy = height / 2f
        val half = s * 0.13f
        symbolPaint.strokeWidth = s * 0.085f

        // Check mark in the green (upper-right) half.
        symbolPaint.alpha = if (greenActive) 255 else dimAlpha
        val gx = cx + off
        val gy = cy - off
        canvas.drawLine(gx - 0.85f * half, gy + 0.05f * half, gx - 0.2f * half, gy + 0.65f * half, symbolPaint)
        canvas.drawLine(gx - 0.2f * half, gy + 0.65f * half, gx + 0.85f * half, gy - 0.65f * half, symbolPaint)

        // Cross in the red (lower-left) half.
        symbolPaint.alpha = if (redActive) 255 else dimAlpha
        val rx = cx - off
        val ry = cy + off
        canvas.drawLine(rx - 0.65f * half, ry - 0.65f * half, rx + 0.65f * half, ry + 0.65f * half, symbolPaint)
        canvas.drawLine(rx + 0.65f * half, ry - 0.65f * half, rx - 0.65f * half, ry + 0.65f * half, symbolPaint)
        canvas.restore()
    }
}
