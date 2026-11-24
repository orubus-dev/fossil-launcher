package org.fossify.home.helpers

import android.content.Context
import android.text.format.DateFormat
import org.fossify.commons.helpers.BaseConfig
import org.fossify.home.R

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var wasHomeScreenInit: Boolean
        get() = prefs.getBoolean(WAS_HOME_SCREEN_INIT, false)
        set(wasHomeScreenInit) = prefs.edit().putBoolean(WAS_HOME_SCREEN_INIT, wasHomeScreenInit).apply()

    var homeColumnCount: Int
        get() = prefs.getInt(HOME_COLUMN_COUNT, COLUMN_COUNT)
        set(homeColumnCount) = prefs.edit().putInt(HOME_COLUMN_COUNT, homeColumnCount).apply()

    var homeRowCount: Int
        get() = prefs.getInt(HOME_ROW_COUNT, ROW_COUNT)
        set(homeRowCount) = prefs.edit().putInt(HOME_ROW_COUNT, homeRowCount).apply()

    var drawerColumnCount: Int
        get() = prefs.getInt(DRAWER_COLUMN_COUNT, context.resources.getInteger(R.integer.portrait_column_count))
        set(drawerColumnCount) = prefs.edit().putInt(DRAWER_COLUMN_COUNT, drawerColumnCount).apply()

    var showSearchBar: Boolean
        get() = prefs.getBoolean(SHOW_SEARCH_BAR, true)
        set(showSearchBar) = prefs.edit().putBoolean(SHOW_SEARCH_BAR, showSearchBar).apply()

    var closeAppDrawer: Boolean
        get() = prefs.getBoolean(CLOSE_APP_DRAWER, false)
        set(closeAppDrawer) = prefs.edit().putBoolean(CLOSE_APP_DRAWER, closeAppDrawer).apply()

    var autoShowKeyboardInAppDrawer: Boolean
        get() = prefs.getBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, false)
        set(autoShowKeyboardInAppDrawer) = prefs.edit()
            .putBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, autoShowKeyboardInAppDrawer).apply()

    var showDrawerAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_DRAWER_APP_LABELS, true)
        set(showDrawerAppLabels) = prefs.edit().putBoolean(SHOW_DRAWER_APP_LABELS, showDrawerAppLabels).apply()

    var showHomeAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_HOME_APP_LABELS, true)
        set(showHomeAppLabels) = prefs.edit().putBoolean(SHOW_HOME_APP_LABELS, showHomeAppLabels).apply()

    var drawerGlassIcons: Boolean
        get() = prefs.getBoolean(DRAWER_GLASS_ICONS, true)
        set(drawerGlassIcons) = prefs.edit().putBoolean(DRAWER_GLASS_ICONS, drawerGlassIcons).apply()

    /**
     * Home screen layout: classic grid + drawer, all-apps-as-home, or a
     * minimal home that keeps widgets and the dock but hides app icons.
     */
    var homeScreenMode: Int
        get() = prefs.getInt(HOME_SCREEN_MODE, HOME_MODE_CLASSIC)
        set(homeScreenMode) = prefs.edit().putInt(HOME_SCREEN_MODE, homeScreenMode).apply()

    // ---- liquid glass v1.0.3: clock + dock pills ---------------------------

    var glassClockEnabled: Boolean
        get() = prefs.getBoolean(GLASS_CLOCK_ENABLED, true)
        set(glassClockEnabled) = prefs.edit().putBoolean(GLASS_CLOCK_ENABLED, glassClockEnabled).apply()

    var glassClock24h: Boolean
        get() = prefs.getBoolean(GLASS_CLOCK_24H, DateFormat.is24HourFormat(context))
        set(glassClock24h) = prefs.edit().putBoolean(GLASS_CLOCK_24H, glassClock24h).apply()

    /** Edge refraction strength of the clock lens, 0..30. */
    var glassClockRefraction: Float
        get() = prefs.getFloat(GLASS_CLOCK_REFRACTION, 14f)
        set(glassClockRefraction) = prefs.edit().putFloat(GLASS_CLOCK_REFRACTION, glassClockRefraction).apply()

    /** Translucent white tint strength over the backdrop, 0..1. */
    var glassClockVibrancy: Float
        get() = prefs.getFloat(GLASS_CLOCK_VIBRANCY, 0.35f)
        set(glassClockVibrancy) = prefs.edit().putFloat(GLASS_CLOCK_VIBRANCY, glassClockVibrancy.coerceIn(0f, 1f)).apply()

    /** Lens ring depth (edge width), 0..1. */
    var glassClockDepth: Float
        get() = prefs.getFloat(GLASS_CLOCK_DEPTH, 0.4f)
        set(glassClockDepth) = prefs.edit().putFloat(GLASS_CLOCK_DEPTH, glassClockDepth.coerceIn(0f, 1f)).apply()

    /** Backdrop blur radius in px, 0..60. */
    var glassClockBlur: Float
        get() = prefs.getFloat(GLASS_CLOCK_BLUR, 14f)
        set(glassClockBlur) = prefs.edit().putFloat(GLASS_CLOCK_BLUR, glassClockBlur.coerceIn(0f, 60f)).apply()

    /** Specular highlight strength, 0..1. */
    var glassClockHighlight: Float
        get() = prefs.getFloat(GLASS_CLOCK_HIGHLIGHT, 0.85f)
        set(glassClockHighlight) = prefs.edit().putFloat(GLASS_CLOCK_HIGHLIGHT, glassClockHighlight.coerceIn(0f, 1f)).apply()

    /** Fill opacity of the frosted layer, 0..1. */
    var glassClockFill: Float
        get() = prefs.getFloat(GLASS_CLOCK_FILL, 0.5f)
        set(glassClockFill) = prefs.edit().putFloat(GLASS_CLOCK_FILL, glassClockFill.coerceIn(0f, 1f)).apply()

    var showSearchPill: Boolean
        get() = prefs.getBoolean(SHOW_SEARCH_PILL, true)
        set(showSearchPill) = prefs.edit().putBoolean(SHOW_SEARCH_PILL, showSearchPill).apply()

    var showBatteryPill: Boolean
        get() = prefs.getBoolean(SHOW_BATTERY_PILL, true)
        set(showBatteryPill) = prefs.edit().putBoolean(SHOW_BATTERY_PILL, showBatteryPill).apply()
}
