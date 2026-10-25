package uz.uzkey.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import uz.uzkey.keyboard.core.EmojiCategory
import uz.uzkey.keyboard.core.EmojiData
import kotlin.math.abs
import kotlin.math.max

/** Scrollable emoji grid with category tabs and a bottom bar (ABC, space, backspace). */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onEmojiBackspace()
        fun onEmojiSpace()
        fun onEmojiClose()
        fun onEmojiKeyFeedback()
    }

    private class Section(val category: EmojiCategory, val firstRow: Int, val rowCount: Int)

    private val density = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var velocityTracker: VelocityTracker? = null

    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) {
            field = value
            invalidate()
        }
    var panelHeight = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private var categories: List<EmojiCategory> = emptyList()
    private var sections: List<Section> = emptyList()
    private var columns = 8
    private var totalRows = 0
    private var scrollOffset = 0f

    private val tabHeight get() = 44f * density
    private val barHeight get() = 50f * density
    private val cellHeight get() = 46f * density
    private val gridTop get() = tabHeight
    private val gridBottom get() = height - barHeight

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedZone = Zone.NONE
    private var pressedIndex = -1
    private var backspaceRepeats = 0

    private enum class Zone { NONE, TAB, GRID, ABC, SPACE, BACKSPACE }

    private val backspaceRepeat: Runnable = object : Runnable {
        override fun run() {
            backspaceRepeats++
            listener.onEmojiBackspace()
            mainHandler.postDelayed(this, if (backspaceRepeats > 20) 30L else 60L)
        }
    }

    fun setCategories(recent: List<String>, all: List<EmojiCategory>) {
        val list = ArrayList<EmojiCategory>(all.size + 1)
        if (recent.isNotEmpty()) list += EmojiCategory(EmojiData.RECENT_ID, EmojiData.RECENT_ICON, recent)
        list += all
        categories = list
        rebuildSections()
        invalidate()
    }

    fun scrollToStart() {
        scroller.forceFinished(true)
        scrollOffset = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), panelHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildSections()
    }

    private fun rebuildSections() {
        if (width == 0) return
        columns = max(6, (width / (44f * density)).toInt())
        var row = 0
        sections = categories.map { category ->
            val rows = (category.emojis.size + columns - 1) / columns
            Section(category, row, rows).also { row += rows }
        }
        totalRows = row
        clampScroll()
    }

    private fun maxScroll(): Float = max(0f, totalRows * cellHeight - (gridBottom - gridTop))

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll())
    }

    private fun currentSectionIndex(): Int {
        val firstVisibleRow = (scrollOffset / cellHeight + 0.5f).toInt()
        val index = sections.indexOfLast { it.firstRow <= firstVisibleRow }
        return index.coerceAtLeast(0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        drawGrid(canvas)
        drawTabs(canvas)
        drawBottomBar(canvas)
    }

    private fun drawTabs(canvas: Canvas) {
        fillPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), tabHeight, fillPaint)
        if (sections.isEmpty()) return
        val tabWidth = width / sections.size.toFloat()
        val active = currentSectionIndex()
        emojiPaint.textSize = 20f * density
        sections.forEachIndexed { index, section ->
            val centerX = tabWidth * index + tabWidth / 2f
            if (index == active) {
                fillPaint.color = theme.accent
                canvas.drawRoundRect(centerX - 12f * density, tabHeight - 4f * density, centerX + 12f * density, tabHeight - 1.5f * density, 2f * density, 2f * density, fillPaint)
            }
            emojiPaint.alpha = if (index == active) 255 else 150
            canvas.drawText(section.category.icon, centerX, tabHeight / 2f - (emojiPaint.descent() + emojiPaint.ascent()) / 2f, emojiPaint)
        }
        emojiPaint.alpha = 255
        fillPaint.color = theme.divider
        canvas.drawRect(0f, tabHeight - 0.5f * density, width.toFloat(), tabHeight + 0.5f * density, fillPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, gridTop, width.toFloat(), gridBottom)
        val cellWidth = width / columns.toFloat()
        emojiPaint.textSize = 27f * density
        val baselineOffset = -(emojiPaint.descent() + emojiPaint.ascent()) / 2f
        val firstRow = (scrollOffset / cellHeight).toInt()
        val lastRow = ((scrollOffset + (gridBottom - gridTop)) / cellHeight).toInt() + 1
        for (section in sections) {
            if (section.firstRow + section.rowCount < firstRow || section.firstRow > lastRow) continue
            section.category.emojis.forEachIndexed { index, emoji ->
                val row = section.firstRow + index / columns
                if (row < firstRow || row > lastRow) return@forEachIndexed
                val column = index % columns
                val top = gridTop + row * cellHeight - scrollOffset
                val centerX = column * cellWidth + cellWidth / 2f
                if (pressedZone == Zone.GRID && pressedIndex == globalIndex(section, index)) {
                    fillPaint.color = theme.keyPressed
                    rect.set(centerX - cellWidth / 2f + 2f * density, top + 2f * density, centerX + cellWidth / 2f - 2f * density, top + cellHeight - 2f * density)
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, fillPaint)
                }
                canvas.drawText(emoji, centerX, top + cellHeight / 2f + baselineOffset, emojiPaint)
            }
        }
        canvas.restore()
    }

    private fun globalIndex(section: Section, index: Int): Int = section.firstRow * columns + index

    private fun barRects(): Triple<RectF, RectF, RectF> {
        val top = gridBottom + 4f * density
        val bottom = height - 6f * density
        val sideWidth = width * 0.18f
        val abc = RectF(6f * density, top, sideWidth, bottom)
        val backspace = RectF(width - sideWidth, top, width - 6f * density, bottom)
        val space = RectF(sideWidth + 8f * density, top, width - sideWidth - 8f * density, bottom)
        return Triple(abc, space, backspace)
    }

    private fun drawBottomBar(canvas: Canvas) {
        val (abc, space, backspace) = barRects()
        val radius = 7.5f * density
        fillPaint.color = if (pressedZone == Zone.ABC) theme.specialPressed else theme.special
        canvas.drawRoundRect(abc, radius, radius, fillPaint)
        fillPaint.color = if (pressedZone == Zone.SPACE) theme.keyPressed else theme.key
        canvas.drawRoundRect(space, radius, radius, fillPaint)
        fillPaint.color = if (pressedZone == Zone.BACKSPACE) theme.specialPressed else theme.special
        canvas.drawRoundRect(backspace, radius, radius, fillPaint)
        labelPaint.textSize = 14.5f * density
        labelPaint.color = theme.text
        canvas.drawText("ABC", abc.centerX(), abc.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint)
        Icons.draw(canvas, Icon.BACKSPACE, backspace.centerX(), backspace.centerY(), 22f * density, theme.text, iconPaint)
    }

    private fun zoneAt(x: Float, y: Float): Pair<Zone, Int> {
        if (y < tabHeight) {
            if (sections.isEmpty()) return Zone.NONE to -1
            val index = (x / (width / sections.size.toFloat())).toInt().coerceIn(0, sections.lastIndex)
            return Zone.TAB to index
        }
        if (y >= gridBottom) {
            val (abc, space, backspace) = barRects()
            return when {
                x <= abc.right + 4f * density -> Zone.ABC to 0
                x >= backspace.left - 4f * density -> Zone.BACKSPACE to 0
                x in space.left..space.right -> Zone.SPACE to 0
                else -> Zone.NONE to -1
            }
        }
        val row = ((y - gridTop + scrollOffset) / cellHeight).toInt()
        val column = (x / (width / columns.toFloat())).toInt().coerceIn(0, columns - 1)
        val section = sections.lastOrNull { it.firstRow <= row } ?: return Zone.NONE to -1
        val indexInSection = (row - section.firstRow) * columns + column
        if (indexInSection >= section.category.emojis.size) return Zone.NONE to -1
        return Zone.GRID to globalIndex(section, indexInSection)
    }

    private fun emojiForGlobalIndex(index: Int): String? {
        val row = index / columns
        val section = sections.lastOrNull { it.firstRow <= row } ?: return null
        return section.category.emojis.getOrNull(index - section.firstRow * columns)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
                val (zone, index) = zoneAt(event.x, event.y)
                pressedZone = zone
                pressedIndex = index
                if (zone != Zone.NONE && zone != Zone.GRID) listener.onEmojiKeyFeedback()
                if (zone == Zone.BACKSPACE) {
                    backspaceRepeats = 0
                    listener.onEmojiBackspace()
                    mainHandler.postDelayed(backspaceRepeat, 380L)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val inGrid = downY in gridTop..gridBottom
                if (inGrid && !dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    pressedZone = Zone.NONE
                }
                if (dragging) {
                    scrollOffset -= event.y - lastY
                    clampScroll()
                    invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(backspaceRepeat)
                velocityTracker?.let { tracker ->
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000)
                    val velocity = tracker.yVelocity
                    if (dragging && abs(velocity) > minFling) {
                        scroller.fling(0, scrollOffset.toInt(), 0, -velocity.toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                    tracker.recycle()
                }
                velocityTracker = null
                if (!dragging) {
                    val (zone, index) = zoneAt(event.x, event.y)
                    if (zone == pressedZone && index == pressedIndex) handleTap(zone, index)
                }
                pressedZone = Zone.NONE
                pressedIndex = -1
                dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(backspaceRepeat)
                velocityTracker?.recycle()
                velocityTracker = null
                pressedZone = Zone.NONE
                dragging = false
                invalidate()
            }
        }
        return true
    }

    private fun handleTap(zone: Zone, index: Int) {
        when (zone) {
            Zone.TAB -> {
                val section = sections.getOrNull(index) ?: return
                scroller.forceFinished(true)
                scrollOffset = section.firstRow * cellHeight
                clampScroll()
            }
            Zone.GRID -> emojiForGlobalIndex(index)?.let {
                listener.onEmojiKeyFeedback()
                listener.onEmoji(it)
            }
            Zone.ABC -> listener.onEmojiClose()
            Zone.SPACE -> listener.onEmojiSpace()
            Zone.BACKSPACE, Zone.NONE -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(backspaceRepeat)
        super.onDetachedFromWindow()
    }
}
