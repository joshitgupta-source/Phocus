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
import kotlin.math.abs

class AlphabetTrackView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val alphabet = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var onLetterTouchListener: ((Char, Int, Float) -> Unit)? = null

    private var currentTouchY = -1f
    private var lastDrawnY = -1f
    private var lastAnnouncedIndex = -1
    private var isTouching = false
    private val thumbRect = RectF()

    private var startX = 0f
    private var startY = 0f
    private var hasConfirmedScroll = false
    private var touchSlop = 0f

    private var itemHeight = 0f
    private var xPos = 0f
    private var thumbWidth = 0f
    private var thumbHeight = 0f
    private var rectLeft = 0f
    private var rectRight = 0f
    private var minCenterY = 0f
    private var maxCenterY = 0f
    private var cornerRadius = 0f


    private val textYPositions = FloatArray(alphabet.length)
    private val letterCache = Array(alphabet.length) { alphabet[it].toString() }

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
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f,
            context.resources.displayMetrics
        )
        textPaint.alpha = 130

        thumbPaint.color = ContextCompat.getColor(context, R.color.alphabet_thumb_color)
        thumbPaint.style = Paint.Style.FILL

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

        for (i in alphabet.indices) {
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

        for (i in alphabet.indices) {
            canvas.drawText(letterCache[i], xPos, textYPositions[i], textPaint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val y = event.y

        val index = ((y / itemHeight).toInt()).coerceIn(0, alphabet.length - 1)
        currentTouchY = y.coerceIn(minCenterY, maxCenterY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y

                val edgeZone = 24f * context.resources.displayMetrics.density


                val isSafeFromEdges = event.x > edgeZone && event.x < (width - edgeZone)
                hasConfirmedScroll = isSafeFromEdges

                if (hasConfirmedScroll) {

                    parent?.requestDisallowInterceptTouchEvent(true)
                    triggerVisuals(index, event.actionMasked)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasConfirmedScroll) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)

                    if (dy > touchSlop && dy > dx) {
                        hasConfirmedScroll = true

                        parent?.requestDisallowInterceptTouchEvent(true)
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


                val dx = abs(event.x - startX)
                val dy = abs(event.y - startY)
                if (event.actionMasked == MotionEvent.ACTION_UP && dx < touchSlop && dy < touchSlop) {
                    performClick()
                }

                invalidate()
                lastDrawnY = -1f // Reset cache
                lastAnnouncedIndex = -1

                onLetterTouchListener?.invoke(alphabet[index], event.actionMasked, currentTouchY)
                return true
            }
        }
        return super.onTouchEvent(event)
    }


    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun triggerVisuals(index: Int, action: Int) {
        if (!isTouching) {
            textPaint.alpha = 255
            isTouching = true
        }


        if (index != lastAnnouncedIndex) {
            lastAnnouncedIndex = index
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            contentDescription = alphabet[index].toString()
        }


        if (abs(currentTouchY - lastDrawnY) > 1f) {
            lastDrawnY = currentTouchY
            invalidate()
        }

        onLetterTouchListener?.invoke(alphabet[index], action, currentTouchY)
    }
}