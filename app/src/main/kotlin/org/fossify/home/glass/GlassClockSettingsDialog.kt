package org.fossify.home.glass

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.fossify.commons.views.MyTextView
import org.fossify.home.extensions.config

/**
 * Fossil glass settings — the panel behind long-pressing the home clock.
 *
 * Live sliders for the clock's optics (refraction, vibrancy, height/depth,
 * blur radius, highlight width, fill opacity), the 12/24-hour toggle and the
 * two dock pills. Every change writes Config immediately and pokes the
 * callback, so the home screen re-renders while you drag — the Void-style
 * tuning feel.
 */
class GlassClockSettingsDialog(
    activity: android.app.Activity,
    private val onGlassChanged: () -> Unit,
    private val onDismiss: (() -> Unit)? = null,
) : GlassCardDialog(activity) {

    override val dialogGravity: Int = Gravity.CENTER
    override val sidePaddingDp: Float = 22f

    private val config by lazy { activity.config }

    init {
        val rows = mutableListOf<View>()
        rows.add(buildTitle(activity.getString(org.fossify.home.R.string.glass_settings_title)))

        // clock switch
        rows.add(
            buildSwitchRow(
                activity.getString(org.fossify.home.R.string.glass_show_clock),
                config.glassClockEnabled
            ) { checked ->
                config.glassClockEnabled = checked
                onGlassChanged()
            }
        )

        rows.add(buildSlider(org.fossify.home.R.string.glass_refraction, 0, 30, (config.glassClockRefraction * 10f).toInt()) { value ->
            config.glassClockRefraction = value / 10f
            onGlassChanged()
        })
        rows.add(buildSlider(org.fossify.home.R.string.glass_vibrancy, 0, 100, (config.glassClockVibrancy * 100f).toInt()) { value ->
            config.glassClockVibrancy = value / 100f
            onGlassChanged()
        })
        rows.add(buildSlider(org.fossify.home.R.string.glass_depth, 0, 100, (config.glassClockDepth * 100f).toInt()) { value ->
            config.glassClockDepth = value / 100f
            onGlassChanged()
        })
        rows.add(buildSlider(org.fossify.home.R.string.glass_blur, 0, 60, config.glassClockBlur.toInt()) { value ->
            config.glassClockBlur = value.toFloat()
            onGlassChanged()
        })
        rows.add(buildSlider(org.fossify.home.R.string.glass_highlight, 0, 100, (config.glassClockHighlight * 100f).toInt()) { value ->
            config.glassClockHighlight = value / 100f
            onGlassChanged()
        })
        rows.add(buildSlider(org.fossify.home.R.string.glass_fill, 0, 100, (config.glassClockFill * 100f).toInt()) { value ->
            config.glassClockFill = value / 100f
            onGlassChanged()
        })

        // 12/24h toggle
        rows.add(
            buildSwitchRow(
                activity.getString(org.fossify.home.R.string.glass_clock_24h),
                config.glassClock24h
            ) { checked ->
                config.glassClock24h = checked
                onGlassChanged()
            }
        )

        // the two pills
        rows.add(buildSectionLabel(activity.getString(org.fossify.home.R.string.glass_pills_section)))
        rows.add(
            buildSwitchRow(
                activity.getString(org.fossify.home.R.string.glass_search_pill),
                config.showSearchPill
            ) { checked ->
                config.showSearchPill = checked
                onGlassChanged()
            }
        )
        rows.add(
            buildSwitchRow(
                activity.getString(org.fossify.home.R.string.glass_battery_pill),
                config.showBatteryPill
            ) { checked ->
                config.showBatteryPill = checked
                onGlassChanged()
            }
        )

        rows.add(buildSpacer())
        installCard(rows)
    }

    override fun onDismissed() {
        onDismiss?.invoke()
    }

    private fun buildSectionLabel(text: String): TextView {
        return MyTextView(activity).apply {
            this.text = text
            setTextColor(textSecondary)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20f), dp(14f), dp(20f), dp(2f))
        }
    }

    private fun buildSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), 0, dp(18f), 0)
            minimumHeight = dp(48f)
        }

        val label = MyTextView(activity).apply {
            this.text = title
            setTextColor(textPrimary)
            textSize = 15.5f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(label)

        val toggle = Switch(activity).apply {
            isChecked = checked
            isClickable = false
        }
        row.addView(toggle)
        row.setOnClickListener {
            toggle.isChecked = !toggle.isChecked
            onChange(toggle.isChecked)
        }
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSlider(
        titleRes: Int,
        min: Int,
        max: Int,
        current: Int,
        onChange: (Int) -> Unit,
    ): View {
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(6f), dp(20f), dp(2f))
        }

        val labelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = MyTextView(activity).apply {
            text = activity.getString(titleRes)
            setTextColor(textPrimary)
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        labelRow.addView(label)

        val valueLabel = MyTextView(activity).apply {
            text = current.toString()
            setTextColor(textSecondary)
            textSize = 13f
        }
        labelRow.addView(valueLabel)
        holder.addView(labelRow)

        val seek = SeekBar(activity).apply {
            this.max = max - min
            progress = (current - min).coerceIn(0, max - min)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(strokeArgb)
            progressTintList = android.content.res.ColorStateList.valueOf(
                if (darkTheme) Color.WHITE else Color.argb(220, 40, 40, 46)
            )
            thumbTintList = android.content.res.ColorStateList.valueOf(
                if (darkTheme) Color.WHITE else Color.argb(240, 40, 40, 46)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) {
                    return
                }
                valueLabel.text = (progress + min).toString()
                onChange(progress + min)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        holder.addView(seek)
        return holder
    }
}
