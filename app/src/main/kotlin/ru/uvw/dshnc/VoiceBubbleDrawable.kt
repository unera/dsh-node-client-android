package ru.uvw.dshnc

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A chat bubble: a rounded rectangle with a small tail in one bottom corner.
 *
 * `GradientDrawable` cannot do this — it draws a rectangle, an oval or a ring and nothing with a
 * path — so the shape is a path: four rounded corners and, when the message ends a run of one
 * speaker, a triangle in the corner that points at the sender. The tail is what makes a series of
 * remarks read as one person talking, which is why only the last message of a run gets it.
 *
 * @param color the fill colour.
 * @param radius corner radius in pixels.
 * @param tailDp the size of the tail in pixels; 0 draws a plain rounded rectangle.
 * @param outgoing true for the person's own messages: the tail is on the right.
 */
class VoiceBubbleDrawable (
    private val color: Int,
    private val radius: Float,
    private val tail: Float,
    private val outgoing: Boolean,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@VoiceBubbleDrawable.color
        style = Paint.Style.FILL
    }
    private val shape = Path()
    private val rect = RectF()

    override fun draw (canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        shape.reset()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        // the body stops short of the tail, so the triangle is what reaches the corner
        val bodyWidth = if (tail > 0f) w - tail else w
        rect.set(0f, 0f, bodyWidth, h)
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW)
        if (tail > 0f) {
            if (outgoing) {
                // right-bottom: from the body's edge out to the corner and down
                shape.moveTo(bodyWidth - radius / 2f, h)
                shape.lineTo(w, h)
                shape.lineTo(bodyWidth, h - tail)
            } else {
                // left-bottom, mirrored
                shape.moveTo(radius / 2f, h)
                shape.lineTo(0f, h)
                shape.lineTo(tail, h - tail)
            }
            shape.close()
        }
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.drawPath(shape, paint)
        canvas.restore()
    }

    override fun setAlpha (alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter (colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("deprecated in the framework, still required by Drawable")
    override fun getOpacity (): Int = PixelFormat.TRANSLUCENT
}
