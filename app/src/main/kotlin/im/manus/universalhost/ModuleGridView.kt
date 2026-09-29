package im.manus.universalhost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Сетка значков без подписей. Долгое нажатие поднимает значок; отпустить можно
 * - в центре другого значка — слияние (папка или добавление в папку),
 * - на другой ячейке — перестановка,
 * - на кнопке «назад» (внутри папки) — вынести из папки.
 * Анимаций нет: значок просто следует за пальцем.
 */
class ModuleGridView(context: Context) : ViewGroup(context) {

    class Cell(
        val id: String,
        val isFolder: Boolean,
        val icons: List<Bitmap?>,
        val dimmed: Boolean,
        /** Папка с собственной картинкой рисуется одним большим значком. */
        val single: Boolean
    )

    interface Listener {
        fun onTap(index: Int)
        fun onReorder(from: Int, to: Int)
        fun onMerge(from: Int, target: Int)
        fun onDropOut(from: Int)
    }

    var listener: Listener? = null
    var dropOutView: View? = null
    var scroller: ScrollView? = null

    private val density = resources.displayMetrics.density
    private val cellPx = (88 * density).toInt()
    private val cells = ArrayList<Cell>()
    private val views = ArrayList<CellView>()
    private var cols = 3
    private var gapPx = 0

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downIndex = -1
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false
    private var longPressed = false

    private var dragging = false
    private var dragIndex = -1
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var startOwnX = 0f
    private var startOwnY = 0f
    private var hoverTarget = -1
    private var hoverMerge = false
    private var overDropOut = false
    private val loc = IntArray(2)

    private val longPress = Runnable { startDrag() }
    private val autoScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val sv = scroller
            if (sv != null) {
                sv.getLocationOnScreen(loc)
                val top = loc[1]
                val bottom = top + sv.height
                val edge = 72 * density
                var dy = 0
                if (lastRawY < top + edge) dy = -(((top + edge - lastRawY) / edge) * 24 * density).toInt() - 2
                else if (lastRawY > bottom - edge) dy = (((lastRawY - (bottom - edge)) / edge) * 24 * density).toInt() + 2
                if (dy != 0) {
                    val before = sv.scrollY
                    sv.scrollBy(0, dy)
                    if (sv.scrollY != before) updateDrag()
                }
            }
            postDelayed(this, 16L)
        }
    }

    fun setCells(newCells: List<Cell>) {
        cancelDrag()
        cells.clear()
        cells.addAll(newCells)
        while (views.size > cells.size) removeView(views.removeAt(views.size - 1))
        while (views.size < cells.size) {
            val v = CellView(context)
            views.add(v)
            addView(v)
        }
        for (i in cells.indices) views[i].bind(cells[i])
        requestLayout()
    }

    // ------------------------------------------------------------ раскладка

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val minGap = (10 * density).toInt()
        cols = max(1, (w - minGap) / (cellPx + minGap))
        gapPx = (w - cols * cellPx) / (cols + 1)
        val rows = if (cells.isEmpty()) 0 else (cells.size + cols - 1) / cols
        val h = if (rows == 0) 0 else gapPx + rows * (cellPx + gapPx)
        val cm = MeasureSpec.makeMeasureSpec(cellPx, MeasureSpec.EXACTLY)
        for (v in views) v.measure(cm, cm)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in views.indices) {
            val x = gapPx + (i % cols) * (cellPx + gapPx)
            val y = gapPx + (i / cols) * (cellPx + gapPx)
            views[i].layout(x, y, x + cellPx, y + cellPx)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        for (i in views.indices) {
            val v = views[i]
            if (x >= v.left && x < v.right && y >= v.top && y < v.bottom) return i
        }
        return -1
    }

    // ------------------------------------------------------------ касания

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downIndex = indexAt(e.x, e.y)
                downRawX = e.rawX
                downRawY = e.rawY
                lastRawX = e.rawX
                lastRawY = e.rawY
                moved = false
                longPressed = false
                if (downIndex >= 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = e.rawX
                lastRawY = e.rawY
                if (dragging) {
                    updateDrag()
                } else if (!moved && hypot(e.rawX - downRawX, e.rawY - downRawY) > slop) {
                    moved = true
                    removeCallbacks(longPress)    // дальше жест забирает ScrollView
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    finishDrag()
                } else if (!moved && !longPressed && downIndex >= 0) {
                    listener?.onTap(downIndex)
                }
                downIndex = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                cancelDrag()
                downIndex = -1
                return true
            }
        }
        return true
    }

    private fun startDrag() {
        if (downIndex !in views.indices || moved) return
        longPressed = true
        dragging = true
        dragIndex = downIndex
        parent?.requestDisallowInterceptTouchEvent(true)
        getLocationOnScreen(loc)
        startOwnX = downRawX - loc[0]
        startOwnY = downRawY - loc[1]
        views[dragIndex].translationZ = 12 * density
        hoverTarget = -1
        hoverMerge = false
        overDropOut = false
        updateDrag()
        post(autoScroll)
    }

    private fun updateDrag() {
        if (!dragging || dragIndex !in views.indices) return
        val v = views[dragIndex]
        getLocationOnScreen(loc)
        val tx = (lastRawX - loc[0]) - startOwnX
        val ty = (lastRawY - loc[1]) - startOwnY
        v.translationX = tx
        v.translationY = ty
        val cx = v.left + cellPx / 2f + tx
        val cy = v.top + cellPx / 2f + ty

        var target = -1
        var merge = false
        for (i in views.indices) {
            if (i == dragIndex) continue
            val t = views[i]
            if (cx >= t.left && cx < t.right && cy >= t.top && cy < t.bottom) {
                target = i
                val d = hypot(cx - (t.left + cellPx / 2f), cy - (t.top + cellPx / 2f))
                merge = d < cellPx * 0.28f
                break
            }
        }
        if (target != hoverTarget || merge != hoverMerge) {
            if (hoverTarget in views.indices) views[hoverTarget].highlight = false
            hoverTarget = target
            hoverMerge = merge
            if (target in views.indices) views[target].highlight = merge
        }

        val out = dropOutView
        val over = out != null && out.visibility == View.VISIBLE && run {
            val r = Rect()
            out.getGlobalVisibleRect(r) && r.contains(lastRawX.toInt(), lastRawY.toInt())
        }
        if (over != overDropOut) {
            overDropOut = over
            out?.alpha = if (over) 1f else 0.6f
        }
    }

    private fun finishDrag() {
        val from = dragIndex
        val target = hoverTarget
        val merge = hoverMerge
        val out = overDropOut
        val draggedIsFolder = from in cells.indices && cells[from].isFolder
        resetDrag()
        val l = listener ?: return
        when {
            out -> l.onDropOut(from)
            target >= 0 && merge && !draggedIsFolder -> l.onMerge(from, target)
            target >= 0 -> l.onReorder(from, target)
        }
    }

    private fun cancelDrag() {
        removeCallbacks(longPress)
        if (dragging) resetDrag()
    }

    private fun resetDrag() {
        dragging = false
        removeCallbacks(autoScroll)
        for (v in views) {
            v.translationX = 0f
            v.translationY = 0f
            v.translationZ = 0f
            v.highlight = false
        }
        dropOutView?.alpha = 0.6f
        dragIndex = -1
        hoverTarget = -1
        hoverMerge = false
        overDropOut = false
    }

    // ------------------------------------------------------------ ячейка

    private inner class CellView(ctx: Context) : View(ctx) {
        private var cell: Cell? = null
        var highlight = false
            set(value) {
                if (field != value) { field = value; invalidate() }
            }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val src = Rect()

        fun bind(c: Cell) {
            cell = c
            highlight = false
            invalidate()
        }

        private fun drawFit(canvas: Canvas, b: Bitmap?, dst: RectF) {
            if (b == null || b.isRecycled) return
            val s = min(dst.width() / b.width, dst.height() / b.height)
            val w = b.width * s
            val h = b.height * s
            val l = dst.left + (dst.width() - w) / 2f
            val t = dst.top + (dst.height() - h) / 2f
            src.set(0, 0, b.width, b.height)
            canvas.drawBitmap(b, src, RectF(l, t, l + w, t + h), bmp)
        }

        override fun onDraw(canvas: Canvas) {
            val c = cell ?: return
            val a = if (c.dimmed) 90 else 255
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = 18 * density
            bg.color = Color.rgb(26, 26, 26)
            bg.alpha = a
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, bg)
            stroke.color = Color.WHITE
            stroke.alpha = a
            stroke.strokeWidth = (if (highlight) 4f else 1.5f) * density
            val half = stroke.strokeWidth / 2f
            rect.set(half, half, w - half, h - half)
            canvas.drawRoundRect(rect, radius, radius, stroke)
            bmp.alpha = a

            if (!c.isFolder || c.single) {
                val pad = 14 * density
                drawFit(canvas, c.icons.firstOrNull(), RectF(pad, pad, w - pad, h - pad))
                if (c.isFolder) {                     // рамка, чтобы папку с картинкой было видно
                    stroke.strokeWidth = 1.5f * density
                    val inset = 6 * density
                    rect.set(inset, inset, w - inset, h - inset)
                    canvas.drawRoundRect(rect, radius * 0.7f, radius * 0.7f, stroke)
                }
            } else {
                val pad = 12 * density
                val gap = 6 * density
                val slot = (w - 2 * pad - gap) / 2f
                stroke.strokeWidth = 1f * density
                for (k in 0 until min(4, c.icons.size)) {
                    val l = pad + (k % 2) * (slot + gap)
                    val t = pad + (k / 2) * (slot + gap)
                    val dst = RectF(l, t, l + slot, t + slot)
                    drawFit(canvas, c.icons[k], RectF(l + 3 * density, t + 3 * density, l + slot - 3 * density, t + slot - 3 * density))
                    canvas.drawRoundRect(dst, 8 * density, 8 * density, stroke)
                }
            }
        }
    }
}
