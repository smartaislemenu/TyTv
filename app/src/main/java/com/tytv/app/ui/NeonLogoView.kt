package com.tytv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * The TYTV boot splash: the same neon artwork as the app icon and
 * the TV banner, drawn at runtime so the glow is real blur rather
 * than a flat vector — layered blue neon behind bold TYTV lettering
 * on a designed dark ground, with a neon underline bar.
 *
 * It shows only while the app is actually loading (MainActivity
 * fades it out the moment the first content renders) — never on a
 * timer, never as a fake delay.
 */
class NeonLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        // Blur shadows need a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val backgroundPaint = Paint()
    private val glowPaint = Paint()
    private val haloPaint = Paint().apply {
        color = Color.parseColor("#1D4ED8")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val tubePaint = Paint().apply {
        color = Color.parseColor("#38BDF8")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val corePaint = Paint().apply {
        color = Color.parseColor("#F0F9FF")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint().apply {
        color = Color.parseColor("#38BDF8")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Designed ground: dark navy gradient, lighter at the top.
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#0B1220"), Color.parseColor("#020617"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // Ambient glow behind the lettering.
        glowPaint.shader = RadialGradient(
            w / 2f, h * 0.46f, w * 0.42f,
            intArrayOf(Color.parseColor("#3B1D4ED8"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        // Lettering sized to ~72% of the width.
        val text = "TYTV"
        var textSize = h * 0.3f
        tubePaint.textSize = textSize
        val measured = tubePaint.measureText(text)
        if (measured > 0f) {
            textSize *= (w * 0.72f) / measured
        }
        haloPaint.textSize = textSize
        tubePaint.textSize = textSize
        corePaint.textSize = textSize

        val centerY = h * 0.46f
        val textY = centerY - (tubePaint.ascent() + tubePaint.descent()) / 2f

        haloPaint.setShadowLayer(textSize * 0.28f, 0f, 0f, Color.parseColor("#1D4ED8"))
        canvas.drawText(text, w / 2f, textY, haloPaint)
        tubePaint.setShadowLayer(textSize * 0.12f, 0f, 0f, Color.parseColor("#0EA5E9"))
        canvas.drawText(text, w / 2f, textY, tubePaint)
        corePaint.clearShadowLayer()
        canvas.drawText(text, w / 2f, textY, corePaint)

        // Neon underline bar under the lettering.
        val barWidth = w * 0.28f
        val barHeight = (h * 0.008f).coerceAtLeast(3f)
        val barTop = textY + tubePaint.descent() + h * 0.04f
        canvas.drawRoundRect(
            RectF(
                w / 2f - barWidth / 2f, barTop,
                w / 2f + barWidth / 2f, barTop + barHeight
            ),
            barHeight / 2f, barHeight / 2f, barPaint
        )
    }
}
