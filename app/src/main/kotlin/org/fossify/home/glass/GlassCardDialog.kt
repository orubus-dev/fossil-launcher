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
 * prompts — floats on a [LiquidGlassPanel]: the launcher content behind the
 * popup is captured, blurred and refracted through a rounded-rect lens with
 * chromatic dispersion and a specular rim (GPU tier on Android 13+, an
 * identical software render everywhere else). Rows slide up from the bottom
 * edge like liquid rising, and presses ripple as liquid highlights.
 */
abstract class GlassCardDialog(
    protected val activity: Activity,
) : Dialog(activity, R.style.GlassDialogTheme) {

    protected val darkTheme: Boolean by lazy { activity.isNightMode() }

    private var glassPanel: LiquidGlassPanel? = null
    private var innerCard: LinearLayout? = null

    val cornerRadius: Float
        get() = dp(28f).toFloat()

    val fillArgb: Int
        get() = if (darkTheme) Color.argb(70, 24, 24, 27) else Color.argb(84, 252, 252, 255)

    val strokeArgb: Int
        get() = if (darkTheme) Color.argb(34, 255, 255, 255) else Color.argb(46, 0, 0, 0)

    val textPrimary: Int
        get() = if (darkTheme) Color.WHITE else Color.argb(228, 12, 12, 14)

    val textSecondary: Int
        get() = if (darkTheme) Color.argb(178, 255, 255, 255) else Color.argb(178, 12, 12, 14)

    val dangerColor: Int
        get() = Color.rgb(235, 74, 78)

    /** Window dim; the backdrop composite dims by the same amount. */
    protected open val backdropDim: Float = 0.28f

    /** Bottom-anchored sheets by default; centered dialogs override to CENTER. */
    protected open val dialogGravity: Int = Gravity.BOTTOM

    /** Horizontal inset of the glass panel from the window edge. */
    protected open val sidePaddingDp: Float = 14f

    fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
        ).toInt()
    }

    /**
     * Builds the glass panel, fills it with [content] rows and installs it
     * as this dialog's view. Rows longer than ~65% of the screen scroll.
     */
    protected fun installCard(content: List<View>) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.forEach { card.addView(it) }
        innerCard = card

        val style = LiquidGlassPanel.GlassStyle(
            cornerRadiusPx = cornerRadius,
            params = GlassRefraction.Params(
                refraction = 15f,
                dispersion = 0.35f,
                edgeWidth = 26f,
                specular = 0.9f,
                tint = fillArgb,
                alpha = 1f,
            ),
            blurRadius = 26f,
            panelInset = dp(20f).toFloat(),
            fallbackFill = if (darkTheme) Color.argb(216, 24, 24, 27) else Color.argb(214, 252, 252, 255),
            stroke = strokeArgb,
        )
        val panel = LiquidGlassPanel(activity, style)
        panel.contentLayout.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        glassPanel = panel

        val maxCardHeight = (activity.resources.displayMetrics.heightPixels * 0.68f).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(
                activity.resources.displayMetrics.widthPixels - dp(sidePaddingDp * 2),
                View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(maxCardHeight, View.MeasureSpec.AT_MOST)
        )
        val needsScroll = card.measuredHeight > maxCardHeight

        val root = FrameLayout(activity).apply {
            setPadding(dp(sidePaddingDp), 0, dp(sidePaddingDp), dp(14f))
            clipChildren = false
            clipToPadding = false
        }

        if (needsScroll) {
            val scroller = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            scroller.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            panel.contentLayout.removeAllViews()
            panel.contentLayout.addView(scroller, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            dialogGravity
        ))

        setContentView(root)
        setupWindow(panel, card.measuredHeight)
    }

    private fun setupWindow(panel: LiquidGlassPanel, measuredCardHeight: Int) {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(dialogGravity)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // cross-window blur where available — complements the panel
                    setBackgroundBlurRadius(64)
                } catch (_: Exception) {
                }
            }
            setDimAmount(backdropDim)
            setWindowAnimations(0)
        }

        // capture the launcher content BEFORE the dialog covers it
        GlassBackdrop.captureContent(activity)

        // approximate the panel's screen rect for the backdrop request
        val metrics = activity.resources.displayMetrics
        val padX = dp(sidePaddingDp)
        val cardHeight = measuredCardHeight.takeIf { it > 0 } ?: dp(220f)
        val screenRect: android.graphics.RectF = when (dialogGravity) {
            Gravity.CENTER -> {
                val centerX = metrics.widthPixels / 2f
                val centerY = metrics.heightPixels / 2f
                android.graphics.RectF(
                    centerX - (metrics.widthPixels / 2f - padX),
                    centerY - cardHeight / 2f,
                    centerX + (metrics.widthPixels / 2f - padX),
                    centerY + cardHeight / 2f
                )
            }

            else -> {
                val padBottom = dp(14f)
                android.graphics.RectF(
                    padX.toFloat(),
                    (metrics.heightPixels - padBottom - cardHeight).toFloat(),
                    (metrics.widthPixels - padX).toFloat(),
                    (metrics.heightPixels - padBottom).toFloat()
                )
            }
        }
        panel.loadBackdrop(activity, screenRect, backdropDim)
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

    /** Trailing breathing room at the bottom of a glass card. */
    protected fun buildSpacer(): View {
        return View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10f)
            )
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

    /** Slide the panel up from the bottom edge, like liquid rising. */
    protected open fun animateIn() {
        val panel = glassPanel ?: return
        panel.translationY = dp(72f).toFloat()
        panel.alpha = 0f
        panel.scaleX = 0.97f
        panel.scaleY = 0.97f
        panel.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(DecelerateInterpolator(1.8f))
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

        val panel = glassPanel
        if (panel == null) {
            super.dismiss()
        } else {
            panel.animate()
                .translationY(dp(48f).toFloat())
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(AccelerateInterpolator(1.4f))
                .withEndAction {
                    GlassBackdrop.invalidateContent()
                    super.dismiss()
                }
                .start()
        }
    }

    protected open fun onDismissed() {}

    /** Convenience wrapper for loading a vector icon resource. */
    protected fun iconOf(resId: Int): Drawable? =
        ContextCompat.getDrawable(activity, resId)
}
