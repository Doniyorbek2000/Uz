package uz.uzkey.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import uz.uzkey.keyboard.R

/** Clipboard history: tap to paste, long-press to pin, ✕ to remove. */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onClipPaste(text: String)
        fun onClipRemove(text: String)
        fun onClipTogglePin(text: String)
        fun onClipClear()
        fun onClipClose()
        fun onClipFeedback()
    }

    private val density = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(list)
    }
    private val title = TextView(context)
    private val clearButton = TextView(context)
    private val closeButton = TextView(context)
    private val emptyText = TextView(context)

    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) {
            field = value
            applyTheme()
        }
    var panelHeight = 0
        set(value) {
            field = value
            requestLayout()
        }

    private var items: List<ClipboardHistory.Item> = emptyList()

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        title.text = context.getString(R.string.clipboard_title)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.paint.isFakeBoldText = true
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        clearButton.text = context.getString(R.string.clipboard_clear)
        styleHeaderButton(clearButton) {
            listener.onClipFeedback()
            listener.onClipClear()
        }
        header.addView(clearButton)
        closeButton.text = "ABC"
        styleHeaderButton(closeButton) {
            listener.onClipFeedback()
            listener.onClipClose()
        }
        header.addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))

        emptyText.text = context.getString(R.string.clipboard_empty)
        emptyText.gravity = Gravity.CENTER
        emptyText.setPadding(dp(24), dp(24), dp(24), dp(24))
        val body = FrameLayout(context)
        body.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(emptyText, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        applyTheme()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY))
    }

    fun setItems(newItems: List<ClipboardHistory.Item>) {
        items = newItems
        list.removeAllViews()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) list.addView(row(item))
        scroll.scrollTo(0, 0)
    }

    private fun styleHeaderButton(view: TextView, onClick: () -> Unit) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        view.paint.isFakeBoldText = true
        view.gravity = Gravity.CENTER
        view.minWidth = dp(64)
        view.setPadding(dp(12), dp(8), dp(12), dp(8))
        view.setOnClickListener { onClick() }
    }

    private fun row(item: ClipboardHistory.Item): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(theme.key)
            setPadding(dp(12), dp(10), dp(4), dp(10))
        }
        if (item.pinned) row.addView(IconView(context, Icon.PIN, theme.accent), LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        val text = TextView(context).apply {
            this.text = item.text.replace('\n', ' ')
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val remove = IconView(context, Icon.CLOSE, theme.subText).apply {
            contentDescription = context.getString(R.string.clipboard_remove)
            setOnClickListener {
                listener.onClipFeedback()
                listener.onClipRemove(item.text)
            }
        }
        row.addView(remove, LayoutParams(dp(40), dp(40)))
        row.setOnClickListener {
            listener.onClipFeedback()
            listener.onClipPaste(item.text)
        }
        row.setOnLongClickListener {
            listener.onClipFeedback()
            listener.onClipTogglePin(item.text)
            true
        }
        val container = FrameLayout(context).apply { setPadding(dp(8), dp(3), dp(8), dp(3)) }
        container.addView(row)
        return container
    }

    private fun applyTheme() {
        setBackgroundColor(theme.background)
        title.setTextColor(theme.text)
        emptyText.setTextColor(theme.subText)
        clearButton.setTextColor(theme.text)
        clearButton.background = rounded(theme.special)
        closeButton.setTextColor(theme.text)
        closeButton.background = rounded(theme.special)
        if (items.isNotEmpty()) setItems(items)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 10f * density
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    @SuppressLint("ViewConstructor")
    private class IconView(context: Context, private val icon: Icon, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height) * 0.6f
            Icons.draw(canvas, icon, width / 2f, height / 2f, size, color, paint)
        }
    }
}
