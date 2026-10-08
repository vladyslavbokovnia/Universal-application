package im.manus.universalhost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** График выборок (например RSSI в dBm): новые значения появляются справа и сдвигают линию влево. */
class MonitorView(context: Context) : View(context) {

    companion object {
        private const val CAPACITY = 120    // столько выборок помещается по ширине
    }

    private var samples = IntArray(0)
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 200, 120)
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 48, 48)
        strokeWidth = 1f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 10f * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun setSamples(values: IntArray) {
        samples = values
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.rgb(16, 16, 16))
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0..4) {
            val y = h * i / 4f
            c.drawLine(0f, y, w, y, grid)
        }
        val s = samples
        if (s.size < 2) {
            c.drawText("нет данных", 8f * density, h / 2f, label)
            return
        }
        val minV = min(-100, s.minOrNull()!! - 5)
        val maxV = max(-30, s.maxOrNull()!! + 5)
        val range = (maxV - minV).toFloat().coerceAtLeast(1f)
        c.drawText("$maxV", 4f * density, 12f * density, label)
        c.drawText("$minV", 4f * density, h - 4f * density, label)

        val path = Path()
        val step = w / (CAPACITY - 1)
        val startX = w - (s.size - 1) * step
        var lastX = 0f
        var lastY = 0f
        for (i in s.indices) {
            val x = startX + i * step
            val y = h - ((s[i] - minV) / range) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
            lastY = y
        }
        c.drawPath(path, line)
        c.drawCircle(lastX, lastY, 4f * density, dot)
    }
}
