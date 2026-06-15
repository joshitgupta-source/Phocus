package com.joshit.phocus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
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

    // Tracking state
    private var currentTouchY = -1f
    private var isTouching = false
    private val thumbRect = RectF()

    // --- OPTIMIZATION: Pre-calculated Layout Values ---
    // Moving these out of onDraw saves hundreds of math operations per second.
    private var itemHeight = 0f
    private var xPos = 0f
    private var thumbWidth = 0f
    private var thumbHeight = 0f
    private var rectLeft = 0f
    private var rectRight = 0f
    private var minCenterY = 0f
    private var maxCenterY = 0f
    private var cornerRadius = 0f

    // Pre-calculate Y coordinates for the text to eliminate math inside the drawing loop
    private val textYPositions = FloatArray(26)
    private val letterCache = Array(26) { alphabet[it].toString() }

    init {
        // Automatically fetch the correct text color for Light/Dark mode
        val typedValue = TypedValue()
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

    // This runs ONCE when the view is created or resized, not every frame.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h == 0) return

        itemHeight = h.toFloat() / alphabet.length
        xPos = w / 2f

        thumbHeight = itemHeight * 2f    // YOUR CUSTOM HEIGHT
        thumbWidth = w * 0.80f           // YOUR CUSTOM WIDTH

        rectLeft = (w - thumbWidth) / 2f
        rectRight = rectLeft + thumbWidth
        cornerRadius = thumbWidth / 2f

        val padding = 8f
        minCenterY = (thumbHeight / 2f) + padding
        maxCenterY = h.toFloat() - (thumbHeight / 2f) - padding

        // Pre-calculate exact Y coordinates for every letter
        val textOffset = ((textPaint.descent() + textPaint.ascent()) / 2)
        for (i in 0..25) {
            textYPositions[i] = (i * itemHeight) + (itemHeight / 2f) - textOffset
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0) return

        // --- 1. DRAW THE THUMB PILL ---
        if (currentTouchY < 0) currentTouchY = minCenterY // Initialize safely

        val top = currentTouchY - (thumbHeight / 2f)
        val bottom = currentTouchY + (thumbHeight / 2f)

        // Use pre-calculated left/right margins
        thumbRect.set(rectLeft, top, rectRight, bottom)

        thumbPaint.alpha = if (isTouching) 255 else 0
        canvas.drawRoundRect(thumbRect, cornerRadius, cornerRadius, thumbPaint)

        // --- 2. DRAW THE ALPHABET TEXT ---
        // Loop math is entirely gone. Just pulling from the pre-calculated arrays.
        for (i in 0..25) {
            canvas.drawText(letterCache[i], xPos, textYPositions[i], textPaint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val y = event.y

        // Fast bounds checking (clamps between 0 and 25 instantly)
        val index = ((y / itemHeight).toInt()).coerceIn(0, 25)

        // Lock Y coordinate directly using pre-calculated boundaries
        currentTouchY = y.coerceIn(minCenterY, maxCenterY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!isTouching) {
                    textPaint.alpha = 255
                    isTouching = true
                }
                invalidate()
                onLetterTouchListener?.invoke(alphabet[index], event.actionMasked, currentTouchY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTouching) {
                    textPaint.alpha = 130
                    isTouching = false
                }
                invalidate()
                onLetterTouchListener?.invoke(alphabet[index], event.actionMasked, currentTouchY)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}