package org.fossify.home.glass

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.fossify.commons.extensions.showKeyboard
import org.fossify.home.R

/**
 * The liquid glass replacement for the framework PopupMenu: every icon
 * long-press action and the home screen menu appear as a frosted glass
 * action sheet with real blur, specular stroke and press ripples.
 */
class GlassMenuDialog(
    activity: Activity,
    title: String?,
    items: List<Item>,
    private val onDismiss: (() -> Unit)? = null,
) : GlassCardDialog(activity) {

    data class Item(
        val title: String,
        val icon: Drawable? = null,
        val danger: Boolean = false,
        val dividedAbove: Boolean = false,
        val onClick: () -> Unit,
    )

    init {
        val rows = mutableListOf<View>()
        title?.takeIf { it.isNotBlank() }?.let { rows.add(buildTitle(it)) }
        items.forEach { item ->
            if (item.dividedAbove) {
                rows.add(buildDivider())
            }
            rows.add(buildRow(item.title, item.icon, item.danger, item.onClick))
        }
        rows.add(buildSpacer())
        installCard(rows)
    }

    override fun onDismissed() {
        onDismiss?.invoke()
    }
}

/**
 * Fossil liquid glass rename/input dialog: a centered glass card with a
 * translucent text field, replacing the framework AlertDialog rename prompt.
 */
class GlassInputDialog(
    activity: Activity,
    title: String,
    initialText: String,
    private val onDismiss: (() -> Unit)? = null,
    private val onConfirm: (String) -> Unit,
) : GlassCardDialog(activity) {

    private lateinit var input: android.widget.EditText

    override val dialogGravity: Int = Gravity.CENTER
    override val sidePaddingDp: Float = 28f

    init {
        val rows = mutableListOf<View>()
        rows.add(buildTitle(title))

        input = android.widget.EditText(activity).apply {
            setText(initialText)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            textSize = 16f
            setSingleLine(true)
            val pad = dp(14f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(
                    if (darkTheme) Color.argb(46, 255, 255, 255)
                    else Color.argb(28, 0, 0, 0)
                )
                setStroke(dp(1f), strokeArgb)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16f), dp(8f), dp(16f), dp(4f)) }
        }
        rows.add(input)

        rows.add(buildDivider())
        rows.add(
            buildRow(activity.getString(org.fossify.commons.R.string.cancel), null, false) {
                onDismiss?.invoke()
            }
        )
        rows.add(
            buildRow(activity.getString(org.fossify.commons.R.string.ok), null, false) {
                onConfirm(input.text?.toString()?.trim().orEmpty())
            }
        )
        rows.add(buildSpacer())
        installCard(rows)

        input.post {
            input.requestFocus()
            try {
                activity.showKeyboard(input)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDismissed() {
        onDismiss?.invoke()
    }
}

/**
 * Fossil liquid glass single-choice dialog: replaces the commons
 * RadioGroupDialog so the settings pickers live on the same glass system as
 * every other popup.
 */
class GlassRadioDialog(
    activity: Activity,
    title: String,
    items: List<org.fossify.commons.models.RadioItem>,
    checkedId: Int,
    private val onDismiss: (() -> Unit)? = null,
    private val onSelect: (Any) -> Unit,
) : GlassCardDialog(activity) {

    override val dialogGravity: Int = Gravity.CENTER
    override val sidePaddingDp: Float = 28f

    private val selected = java.util.concurrent.atomic.AtomicInteger(checkedId)

    init {
        val rows = mutableListOf<View>()
        rows.add(buildTitle(title))

        items.forEach { item ->
            val isSelected = item.id == checkedId
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18f), 0, dp(18f), 0)
                background = rowRipple()
                minimumHeight = dp(50f)
                isClickable = true
                isFocusable = true
            }

            val radio = android.widget.RadioButton(activity).apply {
                isChecked = isSelected
                isClickable = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(radio)

            val label = org.fossify.commons.views.MyTextView(activity).apply {
                text = item.title
                setTextColor(textPrimary)
                textSize = 16f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = dp(14f)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            row.addView(label)

            row.setOnClickListener {
                selected.set(item.id)
                radio.isChecked = true
                row.postDelayed({
                    dismiss()
                    onSelect(item.value)
                }, 90L)
            }
            rows.add(row)
        }

        rows.add(buildDivider())
        rows.add(
            buildRow(activity.getString(org.fossify.commons.R.string.cancel), null, false) {
                onDismiss?.invoke()
            }
        )
        rows.add(buildSpacer())
        installCard(rows)
    }

    override fun onDismissed() {
        onDismiss?.invoke()
    }
}

/**
 * Fossil wallpaper selector: current wallpaper preview plus gallery import
 * and the system picker, all on liquid glass.
 */
class WallpaperPickerDialog(
    activity: Activity,
    private val onPickFromGallery: () -> Unit,
    private val onOpenSystemPicker: () -> Unit,
    private val onDismiss: (() -> Unit)? = null,
) : GlassCardDialog(activity) {

    init {
        val rows = mutableListOf<View>()
        rows.add(buildTitle(activity.getString(org.fossify.commons.R.string.wallpapers)))
        rows.add(buildPreview())
        rows.add(buildLabel(activity.getString(R.string.wallpaper_current)))
        rows.add(
            buildRow(
                title = activity.getString(R.string.wallpaper_pick_from_gallery),
                icon = iconOf(R.drawable.ic_photo_vector),
                danger = false
            ) { onPickFromGallery() }
        )
        rows.add(buildDivider())
        rows.add(
            buildRow(
                title = activity.getString(R.string.wallpaper_open_system_picker),
                icon = iconOf(R.drawable.ic_wallpaper_vector),
                danger = false
            ) { onOpenSystemPicker() }
        )
        rows.add(buildSpacer())
        installCard(rows)
    }

    private fun buildLabel(text: String): android.view.View {
        return org.fossify.commons.views.MyTextView(activity).apply {
            this.text = text
            setTextColor(textSecondary)
            textSize = 13.5f
            setPadding(dp(20f), dp(10f), dp(20f), dp(2f))
        }
    }

    private fun buildPreview(): android.view.View {
        val size = activity.resources.displayMetrics
        val height = dp(150f)
        val preview = android.widget.ImageView(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height
            ).apply {
                setMargins(dp(16f), dp(6f), dp(16f), dp(4f))
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18f).toFloat())
                }
            }
            val wallpaper = GlassWallpapers.getSnapshot(activity)
            if (wallpaper != null) {
                setImageBitmap(wallpaper)
            } else {
                setImageResource(R.drawable.placeholder_drawable)
                GlassWallpapers.prefetch(activity)
            }
        }
        return preview
    }

    override fun onDismissed() {
        onDismiss?.invoke()
    }
}
