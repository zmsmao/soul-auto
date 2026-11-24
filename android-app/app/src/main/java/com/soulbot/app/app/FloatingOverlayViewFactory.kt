package com.soulbot.app.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.soulbot.app.accessibility.UiRegion

internal data class FloatingOverlayViews(
    val root: LinearLayout,
    val expanded: LinearLayout,
    val collapsed: LinearLayout,
    val statusArea: LinearLayout,
    val textColumn: LinearLayout,
    val statusTitle: TextView,
    val nextAction: TextView,
    val action: TextView,
    val statusDot: View,
    val collapsedActionIcon: ImageView,
    val layoutParams: WindowManager.LayoutParams,
)

internal interface FloatingOverlayViewHost {
    val context: Context
    fun dp(value: Int): Int
    fun safeOverlayRegion(): UiRegion
    fun overlayParams(x: Int, y: Int): WindowManager.LayoutParams
}

internal class FloatingOverlayViewFactory(private val host: FloatingOverlayViewHost) {
    private fun roundedShape(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = host.dp(radiusDp).toFloat()
        setColor(color)
        strokeColor?.let { setStroke(host.dp(1), it) }
    }

    fun roundedRipple(color: Int, radiusDp: Int): RippleDrawable {
        val content = roundedShape(color, radiusDp)
        val mask = roundedShape(Color.WHITE, radiusDp)
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            content,
            mask,
        )
    }

    fun dotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun create(): FloatingOverlayViews {
        val root = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = host.dp(10).toFloat()
            background = roundedShape(
                Color.parseColor("#F225262A"),
                28,
                Color.parseColor("#26FFFFFF"),
            )
        }

        val expanded = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
        }

        val statusArea = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(48)
            setPadding(host.dp(8), 0, host.dp(8), 0)
            contentDescription = "SoulBot 状态，可拖动悬浮窗"
        }

        val dot = View(host.context).apply {
            background = dotDrawable(Color.parseColor("#8B5CF6"))
        }
        statusArea.addView(dot, LinearLayout.LayoutParams(host.dp(8), host.dp(8)).apply {
            marginEnd = host.dp(10)
        })

        val textColumn = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = host.dp(120)
        }
        val statusTitle = TextView(host.context).apply {
            text = "已停止"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
        }
        val nextAction = TextView(host.context).apply {
            text = "点击启动"
            textSize = 11f
            setTextColor(Color.parseColor("#C7C9D1"))
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = host.dp(160)
            setPadding(0, host.dp(3), 0, 0)
        }
        textColumn.addView(statusTitle)
        textColumn.addView(nextAction)
        statusArea.addView(textColumn)
        expanded.addView(statusArea)

        val action = TextView(host.context).apply {
            text = "启动"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = host.dp(88)
            minHeight = host.dp(48)
            setPadding(host.dp(14), 0, host.dp(14), 0)
            compoundDrawablePadding = host.dp(8)
            background = roundedRipple(Color.parseColor("#6D28D9"), 24)
            contentDescription = "启动自动助手"
            isClickable = true
            isFocusable = true
        }
        expanded.addView(action, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            host.dp(48),
        ).apply {
            marginStart = host.dp(8)
        })

        val collapsed = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(host.dp(12), 0, host.dp(12), 0)
            background = roundedRipple(Color.TRANSPARENT, 28)
            contentDescription = "展开 SoulBot 悬浮窗"
            isClickable = true
            isFocusable = true
        }
        val collapsedStateIcon = ImageView(host.context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        collapsed.addView(collapsedStateIcon, LinearLayout.LayoutParams(host.dp(20), host.dp(20)))

        root.addView(expanded)
        root.addView(collapsed, LinearLayout.LayoutParams(host.dp(56), host.dp(56)))

        val safeRegion = host.safeOverlayRegion()
        val lp = host.overlayParams(
            safeRegion.right - host.dp(80),
            safeRegion.top + safeRegion.height / 2 - host.dp(40),
        )
        return FloatingOverlayViews(
            root = root,
            expanded = expanded,
            collapsed = collapsed,
            statusArea = statusArea,
            textColumn = textColumn,
            statusTitle = statusTitle,
            nextAction = nextAction,
            action = action,
            statusDot = dot,
            collapsedActionIcon = collapsedStateIcon,
            layoutParams = lp,
        )
    }

}

internal class ActionIconDrawable(
    private val stop: Boolean,
    private val sizePx: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = sizePx * 0.28f
        if (stop) {
            canvas.drawRoundRect(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius,
                sizePx * 0.09f,
                sizePx * 0.09f,
                paint,
            )
        } else {
            val path = Path().apply {
                moveTo(cx - radius * 0.72f, cy - radius)
                lineTo(cx + radius, cy)
                lineTo(cx - radius * 0.72f, cy + radius)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx
}
