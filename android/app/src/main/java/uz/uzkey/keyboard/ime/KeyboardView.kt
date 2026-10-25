package uz.uzkey.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import uz.uzkey.keyboard.core.GesturePoint
import uz.uzkey.keyboard.core.KeySpec
import uz.uzkey.keyboard.core.KeyType
import uz.uzkey.keyboard.core.Suggestions
import uz.uzkey.keyboard.core.TextRules
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ShiftState { OFF, ONCE, LOCKED }

enum class ToolbarAction { EMOJI, CLIPBOARD, CONVERT, VOICE, SETTINGS, HIDE }

/** Renders the suggestion strip and the key grid, and turns touches into keyboard events. */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKeyDown(key: KeySpec)
        fun onKeyUp(key: KeySpec)
        fun onCharacter(key: KeySpec, text: String)
        fun onSpecial(key: KeySpec)
        fun onBackspaceRepeat()
        fun onSwitchKeyboardRequested()
        fun onCursorMove(steps: Int)
        fun onSuggestionPicked(text: String)
        fun onToolbarAction(action: ToolbarAction)
        fun onGesture(path: List<GesturePoint>)
    }

    private class PlacedKey(val spec: KeySpec, val touch: RectF, val visual: RectF)

    private class AlternatesPopup(
        val options: List<String>,
        val bounds: RectF,
        val columns: Int,
        val optionWidth: Float,
        val optionHeight: Float,
        var selected: Int,
    )

    private inner class Pointer(val id: Int, var key: PlacedKey?, val downX: Float, val downY: Float) {
        var consumed = false
        var longPressed = false
        var popup: AlternatesPopup? = null
        var cursorMode = false
        var cursorAnchorX = downX
        var stripTarget = -1
        var repeatCount = 0
        var gesture = false
        val path = ArrayList<GesturePoint>()
        val longPress = Runnable { onLongPress(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                repeatCount++
                listener.onBackspaceRepeat()
                mainHandler.postDelayed(this, if (repeatCount > 25) 28L else 55L)
            }
        }
    }

    private val density = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rows: List<List<KeySpec>> = emptyList()
    private val placed = ArrayList<PlacedKey>()
    private val pointers = SparseArray<Pointer>()

    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) {
            field = value
            invalidate()
        }
    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var isLetterLayer = true
    var spaceLabel = ""
    var enterIcon = Icon.RETURN
    var showKeyPopup = true
    var longPressDelay = 320L
    var heightScale = 1f
        set(value) {
            field = value
            requestLayout()
        }
    var bottomInset = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private var suggestions: Suggestions = Suggestions.NONE
    private var showConvert = false
    private var stripSlots = arrayOfNulls<String>(3)
    private var typedSlot = -1
    private val toolbarButtons = ArrayList<Pair<ToolbarAction, RectF>>()

    /** Row height before the user's size preference; smaller in landscape, larger on tablets. */
    var baseRowHeightDp = 54f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    var stripHeightDp = 46f
    var gestureTyping = false
    var voiceAvailable = false

    private val stripHeight get() = stripHeightDp * density
    private val rowHeight get() = baseRowHeightDp * density * heightScale
    private val topPadding get() = 2f * density
    private val bottomPadding get() = 5f * density
    private val sidePadding get() = 3f * density
    private val keyInsetX get() = 2.6f * density
    private val keyInsetY get() = 3.4f * density
    private val cornerRadius get() = 7.5f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val stripPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val scratch = RectF()

    fun setKeys(newRows: List<List<KeySpec>>) {
        cancelAllPointers()
        val heightChanged = newRows.size != rows.size
        rows = newRows
        if (heightChanged) requestLayout()
        relayout()
        invalidate()
        notifyAccessibilityTreeChanged()
    }

    fun setSuggestions(value: Suggestions, convertAvailable: Boolean) {
        suggestions = value
        showConvert = convertAvailable
        arrangeStrip()
        invalidate()
        notifyAccessibilityTreeChanged()
    }

    fun cancelAllPointers() {
        for (i in 0 until pointers.size()) {
            val pointer = pointers.valueAt(i)
            mainHandler.removeCallbacks(pointer.longPress)
            mainHandler.removeCallbacks(pointer.repeat)
        }
        pointers.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAllPointers()
        super.onDetachedFromWindow()
    }

    // ---- Layout ---------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = stripHeight + topPadding + rows.size * rowHeight + bottomPadding + bottomInset
        setMeasuredDimension(width, height.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
        arrangeStrip()
    }

    private fun relayout() {
        placed.clear()
        val viewWidth = width.toFloat()
        if (viewWidth <= 0f || rows.isEmpty()) return
        val contentWidth = viewWidth - 2 * sidePadding
        val maxUnits = rows.maxOf { row -> row.sumOf { (if (it.flex) 1f else it.weight).toDouble() } }.toFloat()
        val unit = contentWidth / maxUnits
        var top = stripHeight + topPadding
        rows.forEachIndexed { rowIndex, row ->
            val fixedWidth = row.filter { !it.flex }.sumOf { it.weight.toDouble() }.toFloat() * unit
            val flexCount = row.count { it.flex }
            val flexWidth = if (flexCount > 0) max(unit, (contentWidth - fixedWidth) / flexCount) else 0f
            val usedWidth = fixedWidth + flexWidth * flexCount
            var x = sidePadding + max(0f, (contentWidth - usedWidth) / 2f)
            val isLastRow = rowIndex == rows.lastIndex
            row.forEachIndexed { index, spec ->
                val keyWidth = if (spec.flex) flexWidth else spec.weight * unit
                val visual = RectF(x + keyInsetX, top + keyInsetY, x + keyWidth - keyInsetX, top + rowHeight - keyInsetY)
                val touch = RectF(
                    if (index == 0) 0f else x,
                    top,
                    if (index == row.lastIndex) viewWidth else x + keyWidth,
                    if (isLastRow) height.toFloat().coerceAtLeast(top + rowHeight) else top + rowHeight,
                )
                placed += PlacedKey(spec, touch, visual)
                x += keyWidth
            }
            top += rowHeight
        }
    }

    private fun arrangeStrip() {
        stripSlots = arrayOfNulls(3)
        typedSlot = -1
        toolbarButtons.clear()
        val candidates = suggestions.candidates
        val typed = suggestions.typed
        if (candidates.isEmpty() && typed.isEmpty()) {
            val size = 48f * density
            val top = (stripHeight - size) / 2f
            var left = 4f * density
            toolbarButtons += ToolbarAction.EMOJI to RectF(left, top, left + size, top + size)
            left += size
            toolbarButtons += ToolbarAction.CLIPBOARD to RectF(left, top, left + size, top + size)
            left += size
            if (showConvert) toolbarButtons += ToolbarAction.CONVERT to RectF(left, top, left + size, top + size)
            var right = width - 4f * density
            toolbarButtons += ToolbarAction.HIDE to RectF(right - size, top, right, top + size)
            right -= size
            toolbarButtons += ToolbarAction.SETTINGS to RectF(right - size, top, right, top + size)
            right -= size
            if (voiceAvailable) toolbarButtons += ToolbarAction.VOICE to RectF(right - size, top, right, top + size)
            return
        }
        val showTyped = typed.isNotEmpty() && !suggestions.typedIsKnown && candidates.none { it == typed }
        if (showTyped) {
            stripSlots[0] = typed
            typedSlot = 0
            stripSlots[1] = candidates.getOrNull(0)
            stripSlots[2] = candidates.getOrNull(1)
        } else {
            stripSlots[1] = candidates.getOrNull(0)
            stripSlots[0] = candidates.getOrNull(1)
            stripSlots[2] = candidates.getOrNull(2)
        }
    }

    // ---- Drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        drawStrip(canvas)
        val pressed = HashSet<PlacedKey>()
        for (i in 0 until pointers.size()) pointers.valueAt(i).let { if (!it.consumed && !it.gesture && it.stripTarget < 0) it.key?.let(pressed::add) }
        for (key in placed) drawKey(canvas, key, key in pressed)
        for (i in 0 until pointers.size()) {
            val pointer = pointers.valueAt(i)
            if (pointer.gesture) drawGestureTrail(canvas, pointer.path)
        }
        for (i in 0 until pointers.size()) {
            val pointer = pointers.valueAt(i)
            val popup = pointer.popup
            if (popup != null) {
                drawAlternates(canvas, popup)
            } else if (showKeyPopup && !pointer.consumed && !pointer.cursorMode && !pointer.gesture) {
                val key = pointer.key
                if (key != null && key.spec.type == KeyType.CHAR) drawPreview(canvas, key)
            }
        }
    }

    private fun drawStrip(canvas: Canvas) {
        if (toolbarButtons.isNotEmpty()) {
            val pressedAction = activeStripTarget()
            toolbarButtons.forEachIndexed { index, (action, rect) ->
                if (pressedAction == index) {
                    fillPaint.color = theme.keyPressed
                    canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fillPaint)
                }
                val icon = when (action) {
                    ToolbarAction.EMOJI -> Icon.EMOJI
                    ToolbarAction.CONVERT -> Icon.CONVERT
                    ToolbarAction.SETTINGS -> Icon.SETTINGS
                    ToolbarAction.HIDE -> Icon.HIDE
                    ToolbarAction.CLIPBOARD -> Icon.CLIPBOARD
                    ToolbarAction.VOICE -> Icon.MIC
                }
                Icons.draw(canvas, icon, rect.centerX(), rect.centerY(), 22f * density, theme.subText, iconPaint)
            }
            return
        }
        val slotWidth = width / 3f
        val pressedSlot = activeStripTarget()
        stripPaint.textSize = 16.5f * density
        for (slot in 0 until 3) {
            val text = stripSlots[slot] ?: continue
            val left = slot * slotWidth
            if (pressedSlot == slot) {
                fillPaint.color = theme.keyPressed
                scratch.set(left + 4f * density, 5f * density, left + slotWidth - 4f * density, stripHeight - 5f * density)
                canvas.drawRoundRect(scratch, cornerRadius, cornerRadius, fillPaint)
            }
            stripPaint.isFakeBoldText = slot == 1 && typedSlot != 1
            stripPaint.color = if (slot == typedSlot) theme.subText else theme.text
            val label = if (slot == typedSlot) "“$text”" else text
            val shown = TextUtils.ellipsize(label, stripPaint, slotWidth - 12f * density, TextUtils.TruncateAt.END)
            val baseline = stripHeight / 2f - (stripPaint.descent() + stripPaint.ascent()) / 2f
            canvas.drawText(shown, 0, shown.length, left + slotWidth / 2f, baseline, stripPaint)
        }
        fillPaint.color = theme.divider
        for (slot in 1 until 3) {
            if (stripSlots[slot - 1] == null || stripSlots[slot] == null) continue
            val x = slot * slotWidth
            canvas.drawRect(x - 0.5f * density, 13f * density, x + 0.5f * density, stripHeight - 13f * density, fillPaint)
        }
    }

    private fun activeStripTarget(): Int {
        for (i in 0 until pointers.size()) {
            val target = pointers.valueAt(i).stripTarget
            if (target >= 0) return target
        }
        return -1
    }

    private fun backgroundFor(spec: KeySpec, pressed: Boolean): Int = when (spec.type) {
        KeyType.CHAR, KeyType.SPACE -> if (pressed) theme.keyPressed else theme.key
        KeyType.ENTER -> if (pressed) theme.accentPressed else theme.accent
        KeyType.SHIFT -> when {
            pressed -> theme.specialPressed
            shiftState != ShiftState.OFF -> theme.key
            else -> theme.special
        }
        else -> if (pressed) theme.specialPressed else theme.special
    }

    private fun drawKey(canvas: Canvas, key: PlacedKey, pressed: Boolean) {
        val rect = key.visual
        val spec = key.spec
        if (theme.shadow != 0) {
            fillPaint.color = theme.shadow
            scratch.set(rect.left, rect.top + 1.2f * density, rect.right, rect.bottom + 1.2f * density)
            canvas.drawRoundRect(scratch, cornerRadius, cornerRadius, fillPaint)
        }
        fillPaint.color = backgroundFor(spec, pressed)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

        val iconSize = min(rect.height() * 0.5f, 24f * density)
        when (spec.type) {
            KeyType.CHAR -> drawCharLabel(canvas, key)
            KeyType.SPACE -> {
                if (spaceLabel.isNotEmpty()) {
                    labelPaint.textSize = 13f * density
                    labelPaint.color = theme.subText
                    labelPaint.isFakeBoldText = false
                    val shown = TextUtils.ellipsize(spaceLabel, labelPaint, rect.width() - 16f * density, TextUtils.TruncateAt.END)
                    canvas.drawText(shown, 0, shown.length, rect.centerX(), baselineFor(rect.centerY()), labelPaint)
                }
            }
            KeyType.SHIFT -> {
                val icon = when (shiftState) {
                    ShiftState.OFF -> Icon.SHIFT
                    ShiftState.ONCE -> Icon.SHIFT_ON
                    ShiftState.LOCKED -> Icon.SHIFT_LOCKED
                }
                val color = if (shiftState == ShiftState.OFF) theme.text else theme.accent
                Icons.draw(canvas, icon, rect.centerX(), rect.centerY(), iconSize, color, iconPaint)
            }
            KeyType.BACKSPACE -> Icons.draw(canvas, Icon.BACKSPACE, rect.centerX(), rect.centerY(), iconSize, theme.text, iconPaint)
            KeyType.ENTER -> Icons.draw(canvas, enterIcon, rect.centerX(), rect.centerY(), iconSize, theme.onAccent, iconPaint)
            KeyType.LANGUAGE -> Icons.draw(canvas, Icon.GLOBE, rect.centerX(), rect.centerY(), iconSize * 0.92f, theme.text, iconPaint)
            KeyType.EMOJI -> Icons.draw(canvas, Icon.EMOJI, rect.centerX(), rect.centerY(), iconSize * 0.92f, theme.text, iconPaint)
            KeyType.SYMBOLS, KeyType.SYMBOLS_MORE, KeyType.LETTERS -> {
                val text = spec.label.ifEmpty { if (spec.type == KeyType.LETTERS) "ABC" else "?123" }
                labelPaint.textSize = 14.5f * density
                labelPaint.color = theme.text
                labelPaint.isFakeBoldText = true
                canvas.drawText(text, rect.centerX(), baselineFor(rect.centerY()), labelPaint)
                labelPaint.isFakeBoldText = false
            }
        }
    }

    private fun displayText(text: String): String =
        if (isLetterLayer && shiftState != ShiftState.OFF) TextRules.uppercaseKeyText(text) else text

    private fun drawCharLabel(canvas: Canvas, key: PlacedKey) {
        val rect = key.visual
        val text = displayText(key.spec.label)
        labelPaint.color = theme.text
        labelPaint.isFakeBoldText = false
        labelPaint.textSize = when {
            text.length == 1 && isLetterLayer -> 23f * density * min(1f, 0.75f + heightScale * 0.25f)
            text.length <= 2 -> 20f * density
            else -> 16f * density
        }
        if (text.length > 1) {
            val available = rect.width() - 6f * density
            while (labelPaint.measureText(text) > available && labelPaint.textSize > 10f * density) {
                labelPaint.textSize -= 1f * density
            }
        }
        canvas.drawText(text, rect.centerX(), baselineFor(rect.centerY()), labelPaint)
        val hint = key.spec.hint
        if (hint != null) {
            labelPaint.textSize = 10.5f * density
            labelPaint.color = theme.subText
            canvas.drawText(hint, rect.right - 7f * density, rect.top + 13f * density, labelPaint)
        }
        if (key.spec.alternates.isNotEmpty() && hint == null && !isLetterLayer && key.spec.label.length == 1) {
            fillPaint.color = theme.subText
            canvas.drawCircle(rect.right - 6f * density, rect.top + 6f * density, 1.4f * density, fillPaint)
        }
    }

    private fun baselineFor(centerY: Float): Float = centerY - (labelPaint.descent() + labelPaint.ascent()) / 2f

    private fun drawPreview(canvas: Canvas, key: PlacedKey) {
        val rect = key.visual
        val previewWidth = max(rect.width() + 14f * density, 46f * density)
        val previewHeight = rect.height() * 1.18f
        var left = rect.centerX() - previewWidth / 2f
        left = left.coerceIn(2f * density, width - previewWidth - 2f * density)
        var top = rect.top - previewHeight - 6f * density
        if (top < 0f) top = 0f
        scratch.set(left, top, left + previewWidth, top + previewHeight)
        drawPopupBackground(canvas, scratch)
        labelPaint.color = theme.text
        labelPaint.isFakeBoldText = false
        labelPaint.textSize = 30f * density
        val text = displayText(key.spec.label)
        while (labelPaint.measureText(text) > previewWidth - 8f * density && labelPaint.textSize > 12f * density) {
            labelPaint.textSize -= 1f * density
        }
        canvas.drawText(text, scratch.centerX(), baselineFor(scratch.centerY()), labelPaint)
    }

    private fun drawPopupBackground(canvas: Canvas, rect: RectF) {
        fillPaint.color = 0x40000000
        scratch2.set(rect.left, rect.top + 2f * density, rect.right, rect.bottom + 2.5f * density)
        canvas.drawRoundRect(scratch2, cornerRadius * 1.3f, cornerRadius * 1.3f, fillPaint)
        fillPaint.color = theme.popup
        canvas.drawRoundRect(rect, cornerRadius * 1.3f, cornerRadius * 1.3f, fillPaint)
    }

    private val scratch2 = RectF()
    private val optionRect = RectF()

    private fun drawAlternates(canvas: Canvas, popup: AlternatesPopup) {
        drawPopupBackground(canvas, popup.bounds)
        val padding = 4f * density
        labelPaint.isFakeBoldText = false
        popup.options.forEachIndexed { index, option ->
            val column = index % popup.columns
            val row = index / popup.columns
            val left = popup.bounds.left + padding + column * popup.optionWidth
            val top = popup.bounds.top + padding + row * popup.optionHeight
            optionRect.set(left, top, left + popup.optionWidth, top + popup.optionHeight)
            if (index == popup.selected) {
                fillPaint.color = theme.accent
                canvas.drawRoundRect(optionRect, cornerRadius, cornerRadius, fillPaint)
            }
            labelPaint.color = if (index == popup.selected) theme.onAccent else theme.text
            val text = displayText(option)
            labelPaint.textSize = if (text.length <= 2) 22f * density else 16f * density
            while (labelPaint.measureText(text) > popup.optionWidth - 4f * density && labelPaint.textSize > 10f * density) {
                labelPaint.textSize -= 1f * density
            }
            canvas.drawText(text, optionRect.centerX(), baselineFor(optionRect.centerY()), labelPaint)
        }
    }

    // ---- Touch ----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                pointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    pointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                pointerUp(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    private fun stripTargetAt(x: Float, y: Float): Int {
        if (y > stripHeight) return -1
        if (toolbarButtons.isNotEmpty()) {
            return toolbarButtons.indexOfFirst { (_, rect) -> x >= rect.left - 4f * density && x <= rect.right + 4f * density }
        }
        val slot = (x / (width / 3f)).toInt().coerceIn(0, 2)
        return if (stripSlots[slot] != null) slot else -1
    }

    private fun keyAt(x: Float, y: Float): PlacedKey? {
        if (y < stripHeight) return null
        placed.firstOrNull { it.touch.contains(x, y) }?.let { return it }
        return placed.minByOrNull {
            val dx = x - it.touch.centerX()
            val dy = y - it.touch.centerY()
            dx * dx + dy * dy
        }
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        // Rolling input: a new finger commits characters still held by other fingers.
        for (i in 0 until pointers.size()) {
            val other = pointers.valueAt(i)
            val otherKey = other.key ?: continue
            if (!other.consumed && !other.gesture && other.popup == null && !other.cursorMode && otherKey.spec.type == KeyType.CHAR) {
                mainHandler.removeCallbacks(other.longPress)
                other.consumed = true
                listener.onCharacter(otherKey.spec, otherKey.spec.output)
            }
        }
        if (y < stripHeight) {
            val pointer = Pointer(id, null, x, y)
            pointer.stripTarget = stripTargetAt(x, y)
            pointers.put(id, pointer)
            invalidate()
            return
        }
        val key = keyAt(x, y) ?: return
        val pointer = Pointer(id, key, x, y)
        pointer.path += GesturePoint(x, y)
        pointers.put(id, pointer)
        listener.onKeyDown(key.spec)
        when (key.spec.type) {
            KeyType.BACKSPACE -> mainHandler.postDelayed(pointer.repeat, 380L)
            KeyType.CHAR -> if (key.spec.alternates.isNotEmpty()) mainHandler.postDelayed(pointer.longPress, longPressDelay)
            KeyType.SPACE, KeyType.LANGUAGE -> mainHandler.postDelayed(pointer.longPress, 550L)
            else -> Unit
        }
        invalidate()
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        val pointer = pointers.get(id) ?: return
        if (pointer.stripTarget >= 0) {
            if (stripTargetAt(x, y) != pointer.stripTarget) {
                pointer.stripTarget = -1
                invalidate()
            }
            return
        }
        val key = pointer.key ?: return
        val popup = pointer.popup
        if (popup != null) {
            val selected = alternateIndexAt(popup, x, y)
            if (selected != popup.selected) {
                popup.selected = selected
                invalidate()
            }
            return
        }
        if (pointer.consumed) return
        when (key.spec.type) {
            KeyType.SPACE -> {
                val step = 11f * density
                if (!pointer.cursorMode && abs(x - pointer.downX) > 16f * density) {
                    pointer.cursorMode = true
                    pointer.cursorAnchorX = x
                    mainHandler.removeCallbacks(pointer.longPress)
                    invalidate()
                }
                if (pointer.cursorMode) {
                    val steps = ((x - pointer.cursorAnchorX) / step).toInt()
                    if (steps != 0) {
                        pointer.cursorAnchorX += steps * step
                        listener.onCursorMove(steps)
                    }
                }
            }
            KeyType.CHAR -> {
                if (gestureTyping && isLetterLayer && pointers.size() == 1) {
                    pointer.path += GesturePoint(x, y)
                    if (!pointer.gesture && hypot(x - pointer.downX, y - pointer.downY) > key.visual.width() * 0.9f && keyAt(x, y) !== key) {
                        pointer.gesture = true
                        mainHandler.removeCallbacks(pointer.longPress)
                    }
                    if (pointer.gesture) {
                        invalidate()
                        return
                    }
                }
                if (!key.touch.contains(x, y)) {
                    val next = keyAt(x, y)
                    if (next != null && next !== key && next.spec.type == KeyType.CHAR) {
                        mainHandler.removeCallbacks(pointer.longPress)
                        pointer.key = next
                        if (next.spec.alternates.isNotEmpty()) mainHandler.postDelayed(pointer.longPress, longPressDelay)
                        invalidate()
                    }
                }
            }
            else -> Unit
        }
    }

    private fun pointerUp(id: Int, x: Float, y: Float) {
        val pointer = pointers.get(id) ?: return
        pointers.remove(id)
        mainHandler.removeCallbacks(pointer.longPress)
        mainHandler.removeCallbacks(pointer.repeat)
        invalidate()

        if (pointer.key == null) {
            val target = pointer.stripTarget
            if (target >= 0 && stripTargetAt(x, y) == target) {
                if (toolbarButtons.isNotEmpty()) {
                    listener.onToolbarAction(toolbarButtons[target].first)
                } else {
                    stripSlots[target]?.let(listener::onSuggestionPicked)
                }
            }
            return
        }
        val key = pointer.key ?: return
        listener.onKeyUp(key.spec)
        if (pointer.gesture) {
            pointer.path += GesturePoint(x, y)
            listener.onGesture(pointer.path)
            return
        }
        if (pointer.consumed) return
        val popup = pointer.popup
        if (popup != null) {
            popup.options.getOrNull(popup.selected)?.let { listener.onCharacter(key.spec, it) }
            return
        }
        if (pointer.longPressed || pointer.cursorMode) return
        when (key.spec.type) {
            KeyType.CHAR -> listener.onCharacter(key.spec, key.spec.output)
            KeyType.SHIFT, KeyType.BACKSPACE -> Unit
            else -> listener.onSpecial(key.spec)
        }
    }

    private fun onLongPress(pointer: Pointer) {
        if (pointers.get(pointer.id) !== pointer || pointer.consumed) return
        val key = pointer.key ?: return
        when (key.spec.type) {
            KeyType.CHAR -> {
                pointer.longPressed = true
                pointer.popup = buildPopup(key, pointer.downX)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
            KeyType.SPACE, KeyType.LANGUAGE -> {
                if (pointer.cursorMode) return
                pointer.longPressed = true
                listener.onSwitchKeyboardRequested()
            }
            else -> Unit
        }
    }

    private fun buildPopup(key: PlacedKey, touchX: Float): AlternatesPopup {
        val options = key.spec.alternates
        val optionWidth = max(key.visual.width() + 2f * density, 38f * density)
        val optionHeight = key.visual.height() * 1.05f
        val padding = 4f * density
        val maxColumns = max(1, ((width - 2 * padding - 8f * density) / optionWidth).toInt())
        val columns = min(options.size, min(maxColumns, 8))
        val rowsNeeded = (options.size + columns - 1) / columns
        val popupWidth = columns * optionWidth + 2 * padding
        val popupHeight = rowsNeeded * optionHeight + 2 * padding
        val left = (key.visual.centerX() - popupWidth / 2f).coerceIn(2f * density, width - popupWidth - 2f * density)
        val top = max(0f, key.visual.top - popupHeight - 2f * density)
        val bounds = RectF(left, top, left + popupWidth, top + popupHeight)
        val popup = AlternatesPopup(options, bounds, columns, optionWidth, optionHeight, 0)
        popup.selected = alternateIndexAt(popup, touchX, bounds.bottom - padding - optionHeight / 2f)
        return popup
    }

    private fun alternateIndexAt(popup: AlternatesPopup, x: Float, y: Float): Int {
        val padding = 4f * density
        val column = ((x - popup.bounds.left - padding) / popup.optionWidth).toInt().coerceIn(0, popup.columns - 1)
        val rowCount = (popup.options.size + popup.columns - 1) / popup.columns
        val row = ((y - popup.bounds.top - padding) / popup.optionHeight).toInt().coerceIn(0, rowCount - 1)
        return min(row * popup.columns + column, popup.options.lastIndex)
    }

    // ---- Gesture typing -------------------------------------------------------------------

    private val trailPath = android.graphics.Path()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun drawGestureTrail(canvas: Canvas, points: List<GesturePoint>) {
        if (points.size < 2) return
        trailPath.reset()
        trailPath.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) trailPath.lineTo(points[i].x, points[i].y)
        trailPaint.color = theme.accent
        trailPaint.alpha = 190
        trailPaint.strokeWidth = 4.5f * density
        canvas.drawPath(trailPath, trailPaint)
    }

    /** Centres of the letter keys (lower-case output → centre), used to decode swipes. */
    fun gestureKeyCenters(): Map<String, GesturePoint> {
        val centers = LinkedHashMap<String, GesturePoint>()
        val lastRowTop = placed.lastOrNull()?.visual?.top ?: return centers
        for (key in placed) {
            val spec = key.spec
            if (spec.type != KeyType.CHAR || key.visual.top >= lastRowTop) continue
            val output = spec.output.lowercase()
            if (output.none { it.isLetter() }) continue
            centers.putIfAbsent(output, GesturePoint(key.visual.centerX(), key.visual.centerY()))
        }
        return centers
    }

    /** Typical letter-key width in pixels. */
    fun gestureKeyWidth(): Float {
        val widths = placed.filter { it.spec.type == KeyType.CHAR }.map { it.touch.width() }.sorted()
        return if (widths.isEmpty()) 36f * density else widths[widths.size / 2]
    }

    // ---- Accessibility (TalkBack explore-by-touch, lift to type) --------------------------

    private val accessibilityManager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    private var hoveredVirtualId = INVALID_ID
    private var accessibilityFocusedId = INVALID_ID

    private fun notifyAccessibilityTreeChanged() {
        if (accessibilityManager?.isEnabled == true) {
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun virtualIdAt(x: Float, y: Float): Int {
        if (y < stripHeight) {
            val target = stripTargetAt(x, y)
            return if (target >= 0) STRIP_ID_BASE + target else INVALID_ID
        }
        val key = keyAt(x, y) ?: return INVALID_ID
        return placed.indexOf(key)
    }

    private fun virtualBounds(id: Int): RectF? {
        if (id in placed.indices) return placed[id].visual
        val target = id - STRIP_ID_BASE
        if (target < 0) return null
        if (toolbarButtons.isNotEmpty()) return toolbarButtons.getOrNull(target)?.second
        if (target !in 0..2 || stripSlots[target] == null) return null
        val slotWidth = width / 3f
        return RectF(target * slotWidth, 0f, (target + 1) * slotWidth, stripHeight)
    }

    private fun virtualLabel(id: Int): String? {
        if (id in placed.indices) {
            val spec = placed[id].spec
            return when (spec.type) {
                KeyType.CHAR -> displayText(spec.label)
                KeyType.SHIFT -> context.getString(
                    when (shiftState) {
                        ShiftState.OFF -> uz.uzkey.keyboard.R.string.a11y_shift
                        ShiftState.ONCE -> uz.uzkey.keyboard.R.string.a11y_shift_on
                        ShiftState.LOCKED -> uz.uzkey.keyboard.R.string.a11y_caps_lock
                    },
                )
                KeyType.BACKSPACE -> context.getString(uz.uzkey.keyboard.R.string.a11y_delete)
                KeyType.SPACE -> context.getString(uz.uzkey.keyboard.R.string.a11y_space)
                KeyType.ENTER -> context.getString(uz.uzkey.keyboard.R.string.a11y_enter)
                KeyType.SYMBOLS, KeyType.SYMBOLS_MORE -> context.getString(uz.uzkey.keyboard.R.string.a11y_symbols)
                KeyType.LETTERS -> context.getString(uz.uzkey.keyboard.R.string.a11y_letters)
                KeyType.LANGUAGE -> context.getString(uz.uzkey.keyboard.R.string.a11y_language, spaceLabel)
                KeyType.EMOJI -> context.getString(uz.uzkey.keyboard.R.string.a11y_emoji)
            }
        }
        val target = id - STRIP_ID_BASE
        if (target < 0) return null
        if (toolbarButtons.isNotEmpty()) {
            val action = toolbarButtons.getOrNull(target)?.first ?: return null
            return context.getString(
                when (action) {
                    ToolbarAction.EMOJI -> uz.uzkey.keyboard.R.string.a11y_emoji
                    ToolbarAction.CLIPBOARD -> uz.uzkey.keyboard.R.string.clipboard_title
                    ToolbarAction.CONVERT -> uz.uzkey.keyboard.R.string.a11y_convert
                    ToolbarAction.VOICE -> uz.uzkey.keyboard.R.string.a11y_voice
                    ToolbarAction.SETTINGS -> uz.uzkey.keyboard.R.string.settings_title
                    ToolbarAction.HIDE -> uz.uzkey.keyboard.R.string.a11y_hide
                },
            )
        }
        return stripSlots.getOrNull(target)
    }

    private fun activateVirtual(id: Int) {
        if (id in placed.indices) {
            val spec = placed[id].spec
            listener.onKeyDown(spec)
            when (spec.type) {
                KeyType.CHAR -> listener.onCharacter(spec, spec.output)
                KeyType.SHIFT, KeyType.BACKSPACE -> Unit
                else -> listener.onSpecial(spec)
            }
            listener.onKeyUp(spec)
            return
        }
        val target = id - STRIP_ID_BASE
        if (target < 0) return
        if (toolbarButtons.isNotEmpty()) {
            toolbarButtons.getOrNull(target)?.first?.let(listener::onToolbarAction)
        } else {
            stripSlots.getOrNull(target)?.let(listener::onSuggestionPicked)
        }
    }

    @Suppress("DEPRECATION")
    private fun sendVirtualEvent(id: Int, type: Int) {
        val parent = parent ?: return
        if (accessibilityManager?.isEnabled != true) return
        val event = android.view.accessibility.AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = KEY_CLASS_NAME
        virtualLabel(id)?.let { event.contentDescription = it }
        event.setSource(this, id)
        parent.requestSendAccessibilityEvent(this, event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (accessibilityManager?.isTouchExplorationEnabled != true) return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val id = virtualIdAt(event.x, event.y)
                if (id != hoveredVirtualId) {
                    if (hoveredVirtualId != INVALID_ID) sendVirtualEvent(hoveredVirtualId, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    hoveredVirtualId = id
                    if (id != INVALID_ID) sendVirtualEvent(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val id = hoveredVirtualId
                hoveredVirtualId = INVALID_ID
                if (id != INVALID_ID) {
                    sendVirtualEvent(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    // Lift-to-type: releasing the finger over a key activates it.
                    if (virtualBounds(id)?.contains(event.x, event.y) == true) activateVirtual(id)
                }
            }
        }
        return true
    }

    private val nodeProvider = object : android.view.accessibility.AccessibilityNodeProvider() {
        @Suppress("DEPRECATION")
        override fun createAccessibilityNodeInfo(virtualViewId: Int): android.view.accessibility.AccessibilityNodeInfo? {
            val host = this@KeyboardView
            if (virtualViewId == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID) {
                val info = android.view.accessibility.AccessibilityNodeInfo.obtain(host)
                onInitializeAccessibilityNodeInfo(info)
                for (i in placed.indices) info.addChild(host, i)
                val stripCount = if (toolbarButtons.isNotEmpty()) toolbarButtons.size else 3
                for (i in 0 until stripCount) if (virtualBounds(STRIP_ID_BASE + i) != null) info.addChild(host, STRIP_ID_BASE + i)
                return info
            }
            val bounds = virtualBounds(virtualViewId) ?: return null
            val info = android.view.accessibility.AccessibilityNodeInfo.obtain(host, virtualViewId)
            info.packageName = context.packageName
            info.className = KEY_CLASS_NAME
            info.contentDescription = virtualLabel(virtualViewId)
            info.setParent(host)
            val rect = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            info.setBoundsInParent(rect)
            val location = IntArray(2)
            getLocationOnScreen(location)
            rect.offset(location[0], location[1])
            info.setBoundsInScreen(rect)
            info.isEnabled = true
            info.isVisibleToUser = true
            info.isClickable = true
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            if (accessibilityFocusedId == virtualViewId) {
                info.isAccessibilityFocused = true
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            } else {
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (virtualViewId == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID) return performAccessibilityAction(action, arguments)
            return when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK -> {
                    activateVirtual(virtualViewId)
                    sendVirtualEvent(virtualViewId, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                    true
                }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    accessibilityFocusedId = virtualViewId
                    sendVirtualEvent(virtualViewId, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    true
                }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedId == virtualViewId) accessibilityFocusedId = INVALID_ID
                    sendVirtualEvent(virtualViewId, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    true
                }
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = nodeProvider

    private companion object {
        const val INVALID_ID = Int.MIN_VALUE
        const val STRIP_ID_BASE = 1000
        const val KEY_CLASS_NAME = "android.inputmethodservice.Keyboard\$Key"
    }
}
