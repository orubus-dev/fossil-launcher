package org.fossify.home.glass

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import org.fossify.commons.views.MyTextView
import org.fossify.home.R
import org.fossify.home.glass.isNightMode

/**
 * Fossil liquid glass dialogs.
 *
 * Every launcher popup — long-press menus, the wallpaper selector, rename
 * prompts — floats on the same frosted glass card: real background blur on
 * Android 12+, a translucent fill with a specular stroke, a bottom-anchored
 * slide-up entrance and press ripples that read as liquid highlights.
 */
abstract class GlassCardDialog(
    protected val activity: Activity,
) : Dialog(activity, R.style.GlassDialogTheme) {

    protected val darkTheme: Boolean by lazy { activity.isNightMode() }

    /** Root card; assigned by [installCard] during content setup. */
    private var cardView: LinearLayout? = null

    val cornerRadius: Float
        get() = dp(28f).toFloat()

    val fillArgb: Int
        get() = if (darkTheme) Color.argb(216, 24, 24, 27) else Color.argb(214, 252, 252, 255)

    val strokeArgb: Int
        get() = if (darkTheme) Color.argb(34, 255, 255, 255) else Color.argb(46, 0, 0, 0)

    val textPrimary: Int
        get() = if (darkTheme) Color.WHITE else Color.argb(228, 12, 12, 14)

    val textSecondary: Int
        get() = if (darkTheme) Color.argb(178, 255, 255, 255) else Color.argb(178, 12, 12, 14)

    val dangerColor: Int
        get() = Color.rgb(235, 74, 78)

    fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
        ).toInt()
    }

    /**
     * Builds the frosted card, fills it with [content] rows and installs it
     * as this dialog's view. Rows longer than ~65% of the screen scroll.
     */
    protected fun installCard(content: List<View>) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(fillArgb)
                cornerRadius = this@GlassCardDialog.cornerRadius
                setStroke(dp(1f), strokeArgb)
            }
            elevation = dp(14f).toFloat()
        }
        content.forEach { card.addView(it) }
        cardView = card

        val maxCardHeight = (activity.resources.displayMetrics.heightPixels * 0.68f).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(
                activity.resources.displayMetrics.widthPixels - dp(28f),
                View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(maxCardHeight, View.MeasureSpec.AT_MOST)
        )
        val needsScroll = card.measuredHeight > maxCardHeight

        val root = FrameLayout(activity).apply {
            setPadding(dp(14f), 0, dp(14f), dp(14f))
        }

        if (needsScroll) {
            val scroller = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            scroller.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            root.addView(scroller, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
        } else {
            root.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
        }

        setContentView(root)
        setupWindow()
    }

    private fun setupWindow() {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // real blur of the launcher + wallpaper behind the card
                    setBackgroundBlurRadius(88)
                } catch (_: Exception) {
                }
            }
            setDimAmount(0.34f)
            setWindowAnimations(R.style.GlassDialogWindowAnimation)
        }
    }

    protected fun rowRipple(): Drawable {
        val content = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(18f).toFloat()
        }
        val rippleColor = if (darkTheme) {
            Color.argb(46, 255, 255, 255)
        } else {
            Color.argb(38, 0, 0, 0)
        }
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleColor),
            content,
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18f).toFloat()
            }
        )
    }

    protected fun buildRow(
        title: String,
        icon: Drawable?,
        danger: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), 0, dp(18f), 0)
            background = rowRipple()
            minimumHeight = dp(54f)
            isClickable = true
            isFocusable = true
        }

        if (icon != null) {
            val iconView = ImageView(activity).apply {
                val size = dp(22f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                setImageDrawable(icon.mutate())
                setColorFilter(if (danger) dangerColor else textSecondary)
            }
            row.addView(iconView)
        }

        val label = MyTextView(activity).apply {
            text = title
            setTextColor(if (danger) dangerColor else textPrimary)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginStart = if (icon != null) dp(16f) else 0
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        row.addView(label)

        row.setOnClickListener {
            dismiss()
            onClick()
        }
        return row
    }

    protected fun buildDivider(): View {
        return View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f))
            ).apply {
                marginStart = dp(16f)
                marginEnd = dp(16f)
            }
            setBackgroundColor(strokeArgb)
        }
    }

    protected fun buildTitle(text: String): MyTextView {
        return MyTextView(activity).apply {
            this.text = text
            setTextColor(textPrimary)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20f), dp(18f), dp(20f), dp(6f))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    override fun show() {
        super.show()
        animateIn()
    }

    /** Slide the card up from the bottom edge, like liquid rising. */
    protected open fun animateIn() {
        val card = cardView ?: return
        card.translationY = dp(64f).toFloat()
        card.alpha = 0f
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private var dismissStarted = false

    override fun dismiss() {
        if (dismissStarted) {
            return
        }
        dismissStarted = true
        try {
            onDismissed()
        } catch (_: Exception) {
        }

        val card = cardView
        if (card == null) {
            super.dismiss()
        } else {
            card.animate()
                .translationY(dp(48f).toFloat())
                .alpha(0f)
                .setDuration(150L)
                .setInterpolator(AccelerateInterpolator(1.4f))
                .withEndAction { super.dismiss() }
                .start()
        }
    }

    protected open fun onDismissed() {}

    /** Convenience wrapper for loading a vector icon resource. */
    protected fun iconOf(resId: Int): Drawable? =
        ContextCompat.getDrawable(activity, resId)
}
