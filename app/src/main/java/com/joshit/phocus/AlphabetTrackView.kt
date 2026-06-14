package com.joshit.phocus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

class AlphabetTrackView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var onLetterTouchListener: ((Char, Int, Float) -> Unit)? = null

    // Tracking state for the inner moving pill
    private var currentTouchY = -1f
    private var isTouching = false
    private val thumbRect = RectF()

    init {
        // Automatically fetch the correct text color for Light/Dark mode
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val dynamicColor = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }

        // Setup the Alphabet Text
        textPaint.color = dynamicColor
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = context.resources.displayMetrics.scaledDensity * 11f // Sleek text size
        textPaint.alpha = 130 // Faded by default

        // Setup the Inner Moving Pill
        thumbPaint.color = ContextCompat.getColor(context, R.color.alphabet_thumb_color)
        thumbPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0) return

        val itemHeight = height.toFloat() / alphabet.length
        val xPos = width / 2f

        // --- 1. DRAW THE MOVING INNER PILL FIRST (so text sits on top of it) ---
        val thumbHeight = itemHeight * 2f    // YOUR CUSTOM HEIGHT
        val thumbWidth = width * 0.80f       // YOUR CUSTOM WIDTH
        val left = (width - thumbWidth) / 2f
        val right = left + thumbWidth

        // --- THE FIX: Apply physical boundaries using YOUR custom thumbHeight ---
        val padding = 8f
        val minCenterY = (thumbHeight / 2f) + padding
        val maxCenterY = height.toFloat() - (thumbHeight / 2f) - padding

        // Clamp the Y coordinate based on the boundaries
        val safeY = if (currentTouchY < 0) minCenterY else currentTouchY.coerceIn(minCenterY, maxCenterY)

        val top = safeY - (thumbHeight / 2f)
        val bottom = safeY + (thumbHeight / 2f)

        thumbRect.set(left, top, right, bottom)

        val cornerRadius = thumbWidth / 2f

        // The pill gets bright when touched, and fades back to subtle when idle
        thumbPaint.alpha = if (isTouching) 255 else 0
        canvas.drawRoundRect(thumbRect, cornerRadius, cornerRadius, thumbPaint)

        // --- 2. DRAW THE ALPHABET TEXT OVER IT ---
        for (i in alphabet.indices) {
            val yPos = (i * itemHeight) + (itemHeight / 2) - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(alphabet[i].toString(), xPos, yPos, textPaint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val itemHeight = height.toFloat() / alphabet.length
        var index = (event.y / itemHeight).toInt()

        // Prevent crashes if the user drags slightly off the top/bottom edges
        if (index < 0) index = 0
        if (index >= alphabet.length) index = alphabet.length - 1

        // --- THE FIX: Match the touch boundaries to YOUR custom thumbHeight ---
        val thumbHeight = itemHeight * 2f
        val padding = 8f
        val minCenterY = (thumbHeight / 2f) + padding
        val maxCenterY = height.toFloat() - (thumbHeight / 2f) - padding

        val clampedY = event.y.coerceIn(minCenterY, maxCenterY)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                textPaint.alpha = 255
                isTouching = true
                currentTouchY = clampedY
                invalidate()

                onLetterTouchListener?.invoke(alphabet[index], event.action, clampedY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                textPaint.alpha = 130
                isTouching = false
                currentTouchY = clampedY
                invalidate()

                onLetterTouchListener?.invoke(alphabet[index], event.action, clampedY)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}