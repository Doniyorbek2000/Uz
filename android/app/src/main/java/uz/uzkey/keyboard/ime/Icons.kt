package uz.uzkey.keyboard.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

enum class Icon { SHIFT, SHIFT_ON, SHIFT_LOCKED, BACKSPACE, RETURN, SEARCH, SEND, NEXT, DONE, GLOBE, EMOJI, SETTINGS, HIDE, CONVERT, CLIPBOARD, MIC, PIN, CLOSE }

/** Keyboard glyphs drawn from simple geometry on a 24×24 grid so every theme can tint them. */
object Icons {

    private val path = Path()
    private val oval = RectF()

    fun draw(canvas: Canvas, icon: Icon, centerX: Float, centerY: Float, size: Float, color: Int, paint: Paint) {
        val scale = size / 24f
        canvas.save()
        canvas.translate(centerX - size / 2f, centerY - size / 2f)
        canvas.scale(scale, scale)
        paint.color = color
        paint.strokeWidth = 1.9f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.isAntiAlias = true
        when (icon) {
            Icon.SHIFT, Icon.SHIFT_ON, Icon.SHIFT_LOCKED -> {
                path.reset()
                path.moveTo(12f, 3.5f)
                path.lineTo(20.5f, 12.5f)
                path.lineTo(15.5f, 12.5f)
                path.lineTo(15.5f, if (icon == Icon.SHIFT_LOCKED) 16.5f else 19.5f)
                path.lineTo(8.5f, if (icon == Icon.SHIFT_LOCKED) 16.5f else 19.5f)
                path.lineTo(8.5f, 12.5f)
                path.lineTo(3.5f, 12.5f)
                path.close()
                paint.style = if (icon == Icon.SHIFT) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
                if (icon == Icon.SHIFT_LOCKED) {
                    paint.style = Paint.Style.FILL_AND_STROKE
                    canvas.drawRect(8.5f, 19f, 15.5f, 20.5f, paint)
                }
            }
            Icon.BACKSPACE -> {
                path.reset()
                path.moveTo(8f, 5f)
                path.lineTo(21f, 5f)
                path.lineTo(21f, 19f)
                path.lineTo(8f, 19f)
                path.lineTo(2.5f, 12f)
                path.close()
                paint.style = Paint.Style.STROKE
                canvas.drawPath(path, paint)
                canvas.drawLine(11.5f, 9f, 17.5f, 15f, paint)
                canvas.drawLine(17.5f, 9f, 11.5f, 15f, paint)
            }
            Icon.RETURN -> {
                paint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(19.5f, 5.5f)
                path.lineTo(19.5f, 13f)
                path.lineTo(5f, 13f)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(9f, 9f)
                path.lineTo(5f, 13f)
                path.lineTo(9f, 17f)
                canvas.drawPath(path, paint)
            }
            Icon.SEARCH -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(10.5f, 10.5f, 6f, paint)
                canvas.drawLine(15f, 15f, 20f, 20f, paint)
            }
            Icon.SEND -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                path.reset()
                path.moveTo(3.5f, 20f)
                path.lineTo(21f, 12f)
                path.lineTo(3.5f, 4f)
                path.lineTo(5.5f, 10.5f)
                path.lineTo(13f, 12f)
                path.lineTo(5.5f, 13.5f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.NEXT -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(4f, 12f, 19.5f, 12f, paint)
                path.reset()
                path.moveTo(13.5f, 6f)
                path.lineTo(19.5f, 12f)
                path.lineTo(13.5f, 18f)
                canvas.drawPath(path, paint)
            }
            Icon.DONE -> {
                paint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(4.5f, 12.5f)
                path.lineTo(9.5f, 17.5f)
                path.lineTo(19.5f, 6.5f)
                canvas.drawPath(path, paint)
            }
            Icon.GLOBE -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                oval.set(8f, 3.5f, 16f, 20.5f)
                canvas.drawOval(oval, paint)
                canvas.drawLine(3.5f, 12f, 20.5f, 12f, paint)
                canvas.drawLine(5f, 7.5f, 19f, 7.5f, paint)
                canvas.drawLine(5f, 16.5f, 19f, 16.5f, paint)
            }
            Icon.EMOJI -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                path.reset()
                path.moveTo(8f, 14f)
                path.quadTo(12f, 18.5f, 16f, 14f)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(9f, 10f, 1.3f, paint)
                canvas.drawCircle(15f, 10f, 1.3f, paint)
            }
            Icon.SETTINGS -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(12f, 12f, 3f, paint)
                canvas.drawCircle(12f, 12f, 6.5f, paint)
                paint.strokeWidth = 2.6f
                for (i in 0 until 8) {
                    val angle = Math.PI * i / 4.0
                    val cos = Math.cos(angle).toFloat()
                    val sin = Math.sin(angle).toFloat()
                    canvas.drawLine(12f + cos * 7f, 12f + sin * 7f, 12f + cos * 9.5f, 12f + sin * 9.5f, paint)
                }
            }
            Icon.HIDE -> {
                paint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(6f, 9.5f)
                path.lineTo(12f, 15.5f)
                path.lineTo(18f, 9.5f)
                canvas.drawPath(path, paint)
            }
            Icon.CONVERT -> {
                paint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(4f, 8f)
                path.lineTo(18.5f, 8f)
                path.moveTo(15f, 4.5f)
                path.lineTo(18.5f, 8f)
                path.lineTo(15f, 11.5f)
                path.moveTo(20f, 16f)
                path.lineTo(5.5f, 16f)
                path.moveTo(9f, 12.5f)
                path.lineTo(5.5f, 16f)
                path.lineTo(9f, 19.5f)
                canvas.drawPath(path, paint)
            }
            Icon.CLIPBOARD -> {
                paint.style = Paint.Style.STROKE
                oval.set(5f, 5f, 19f, 21f)
                canvas.drawRoundRect(oval, 2f, 2f, paint)
                oval.set(9f, 3f, 15f, 7f)
                paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawRoundRect(oval, 1f, 1f, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(8.5f, 11f, 15.5f, 11f, paint)
                canvas.drawLine(8.5f, 15f, 13.5f, 15f, paint)
            }
            Icon.MIC -> {
                paint.style = Paint.Style.STROKE
                oval.set(9f, 3f, 15f, 15f)
                canvas.drawRoundRect(oval, 3f, 3f, paint)
                path.reset()
                path.moveTo(5.5f, 11f)
                path.quadTo(5.5f, 18f, 12f, 18f)
                path.quadTo(18.5f, 18f, 18.5f, 11f)
                canvas.drawPath(path, paint)
                canvas.drawLine(12f, 18f, 12f, 21f, paint)
            }
            Icon.PIN -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                path.reset()
                path.moveTo(9f, 3.5f)
                path.lineTo(15f, 3.5f)
                path.lineTo(14f, 10f)
                path.lineTo(17.5f, 14f)
                path.lineTo(6.5f, 14f)
                path.lineTo(10f, 10f)
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(12f, 14f, 12f, 20.5f, paint)
            }
            Icon.CLOSE -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(7f, 7f, 17f, 17f, paint)
                canvas.drawLine(17f, 7f, 7f, 17f, paint)
            }
        }
        canvas.restore()
    }
}
