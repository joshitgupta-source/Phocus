package com.joshit.phocus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

    // --- NEW: GESTURE CONFLICT ENGINE VARIABLES ---
    private var startX = 0f
    private var startY = 0f
    private var hasConfirmedScroll = false
    private var touchSlop = 0f

    // Pre-calculated Layout Values
    private var itemHeight = 0f
    private var xPos = 0f
    private var thumbWidth = 0f
    private var thumbHeight = 0f
    private var rectLeft = 0f
    private var rectRight = 0f
    private var minCenterY = 0f
    private var maxCenterY = 0f
    private var cornerRadius = 0f

    private val textYPositions = FloatArray(26)
    private val letterCache = Array(26) { alphabet[it].toString() }

    init {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val dynamicColor = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }

        textPaint.color = dynamicColor
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = context.resources.displayMetrics.scaledDensity * 11f
        textPaint.alpha = 130

        thumbPaint.color = ContextCompat.getColor(context, R.color.alphabet_thumb_color)
        thumbPaint.style = Paint.Style.FILL

        // THE FIX: Get the exact pixel distance Android considers an "accidental wiggle"
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h == 0) return

        itemHeight = h.toFloat() / alphabet.length
        xPos = w / 2f

        thumbHeight = itemHeight * 2f
        thumbWidth = w * 0.80f

        rectLeft = (w - thumbWidth) / 2f
        rectRight = rectLeft + thumbWidth
        cornerRadius = thumbWidth / 2f

        val padding = 8f
        minCenterY = (thumbHeight / 2f) + padding
        maxCenterY = h.toFloat() - (thumbHeight / 2f) - padding

        val textOffset = ((textPaint.descent() + textPaint.ascent()) / 2)
        for (i in 0..25) {
            textYPositions[i] = (i * itemHeight) + (itemHeight / 2f) - textOffset
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0) return

        if (currentTouchY < 0) currentTouchY = minCenterY

        val top = currentTouchY - (thumbHeight / 2f)
        val bottom = currentTouchY + (thumbHeight / 2f)

        thumbRect.set(rectLeft, top, rectRight, bottom)

        thumbPaint.alpha = if (isTouching) 255 else 0
        canvas.drawRoundRect(thumbRect, cornerRadius, cornerRadius, thumbPaint)

        for (i in 0..25) {
            canvas.drawText(letterCache[i], xPos, textYPositions[i], textPaint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val y = event.y
        val index = ((y / itemHeight).toInt()).coerceIn(0, 25)
        currentTouchY = y.coerceIn(minCenterY, maxCenterY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y

                // THE FIX: Define the 24dp Android Back Gesture Danger Zone
                val edgeZone = 24f * context.resources.displayMetrics.density

                // If they touch inside the view but OUTSIDE the Danger Zone, activate instantly!
                hasConfirmedScroll = event.x < (width - edgeZone)

                if (hasConfirmedScroll) {
                    triggerVisuals(index, event.actionMasked)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasConfirmedScroll) {
                    // They touched the Danger Zone. Check if they are pulling DOWN/UP instead of LEFT.
                    val dx = Math.abs(event.x - startX)
                    val dy = Math.abs(event.y - startY)

                    // If vertical movement exceeds the slop threshold, it's a confirmed scroll!
                    if (dy > touchSlop && dy > dx) {
                        hasConfirmedScroll = true
                    }
                }

                if (hasConfirmedScroll) {
                    triggerVisuals(index, event.actionMasked)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hasConfirmedScroll = false
                if (isTouching) {
                    textPaint.alpha = 130
                    isTouching = false
                }
                invalidate()

                // Always tell MainActivity to kill the bubble if the system takes over
                onLetterTouchListener?.invoke(alphabet[index], event.actionMasked, currentTouchY)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun triggerVisuals(index: Int, action: Int) {
        if (!isTouching) {
            textPaint.alpha = 255
            isTouching = true
        }
        invalidate()
        onLetterTouchListener?.invoke(alphabet[index], action, currentTouchY)
    }
}