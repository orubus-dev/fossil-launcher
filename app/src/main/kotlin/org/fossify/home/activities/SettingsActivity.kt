package org.fossify.home.activities

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isSPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import org.fossify.home.BuildConfig
import org.fossify.home.R
import org.fossify.home.databinding.ActivitySettingsBinding
import org.fossify.home.extensions.config
import org.fossify.home.glass.GlassRadioDialog
import org.fossify.home.glass.GlassWallpapers
import org.fossify.home.glass.isNightMode
import org.fossify.home.helpers.MAX_COLUMN_COUNT
import org.fossify.home.helpers.MAX_ROW_COUNT
import org.fossify.home.helpers.MIN_COLUMN_COUNT
import org.fossify.home.helpers.MIN_ROW_COUNT
import org.fossify.home.helpers.HOME_MODE_ALL_APPS_ON_HOME
import org.fossify.home.helpers.HOME_MODE_CLASSIC
import org.fossify.home.helpers.HOME_MODE_DRAWER_ONLY
import org.fossify.home.receivers.LockDeviceAdminReceiver
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {

    private val binding by viewBinding(ActivitySettingsBinding::inflate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        applyGlassBlur()
        applyGlassSkin()
        setupEdgeToEdge(padBottomSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
        setupOptionsMenu()
    }

    /**
     * Fossil liquid glass settings: blur the wallpaper behind the window so the
     * frosted settings card sits on real depth. No-ops where blur is unsupported.
     */
    private fun applyGlassBlur() {
        if (!isSPlus()) {
            return
        }
        try {
            // No-op on devices where cross-window blur is unavailable or disabled.
            window.setBackgroundBlurRadius(110)
        } catch (_: Exception) {
        }
    }

    /**
     * Fossil glass skin: the settings page is laid over an actual blurred-out
     * sample of the current wallpaper, so even devices without cross-window
     * blur get the full frosted look. The scrim adapts to day/night so the
     * glass card keeps its contrast on both light and dark wallpapers.
     */
    private fun applyGlassSkin() {
        binding.settingsAppbar.background = null
        binding.settingsToolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.settingsWallpaperScrim.setBackgroundColor(
            if (isNightMode()) 0x59000000.toInt() else 0x38FFFFFF
        )
        ensureBackgroundThread {
            val snapshot = GlassWallpapers.getSnapshot(applicationContext) ?: return@ensureBackgroundThread
            val blurred = GlassWallpapers.blurSample(
                snapshot,
                resources.displayMetrics.widthPixels / 2,
                resources.displayMetrics.heightPixels / 2
            )
            runOnUiThread {
                binding.settingsWallpaperBackdrop.setImageBitmap(blurred)
                binding.settingsWallpaperBackdrop.animate().alpha(1f).setDuration(300L).start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)
        applyGlassSkin()
        refreshMenuItems()

        setupCustomizeColors()
        setupUseEnglish()
        setupDoubleTapToLock()
        setupCloseAppDrawerOnOtherAppOpen()
        setupOpenKeyboardOnAppDrawer()
        setupGlassIcons()
        setupDrawerColumnCount()
        setupDrawerSearchBar()
        setupShowDrawerAppLabels()
        setupHomeRowCount()
        setupHomeColumnCount()
        setupHomeScreenMode()
        setupShowHomeAppLabels()
        setupLanguage()
        setupManageHiddenIcons()
        updateTextColors(binding.settingsHolder)

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsDrawerSettingsLabel,
            binding.settingsHomeScreenLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.about -> launchAbout()
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.settingsToolbar.menu.apply {
            findItem(R.id.more_apps_from_us).isVisible =
                resources.getBoolean(org.fossify.commons.R.bool.is_google_play_build)
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolder.beVisibleIf(
            beVisible = (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )

        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupDoubleTapToLock() {
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        binding.settingsDoubleTapToLock.isChecked = devicePolicyManager.isAdminActive(
            ComponentName(this, LockDeviceAdminReceiver::class.java)
        )

        binding.settingsDoubleTapToLockHolder.setOnClickListener {
            val isLockDeviceAdminActive = devicePolicyManager.isAdminActive(
                ComponentName(this, LockDeviceAdminReceiver::class.java)
            )
            if (isLockDeviceAdminActive) {
                devicePolicyManager.removeActiveAdmin(
                    ComponentName(this, LockDeviceAdminReceiver::class.java)
                )
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, LockDeviceAdminReceiver::class.java)
                )
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.lock_device_admin_hint)
                )
                startActivity(intent)
            }
        }
    }

    private fun setupOpenKeyboardOnAppDrawer() {
        binding.settingsOpenKeyboardOnAppDrawerHolder.beVisibleIf(config.showSearchBar)
        binding.settingsOpenKeyboardOnAppDrawer.isChecked = config.autoShowKeyboardInAppDrawer
        binding.settingsOpenKeyboardOnAppDrawerHolder.setOnClickListener {
            binding.settingsOpenKeyboardOnAppDrawer.toggle()
            config.autoShowKeyboardInAppDrawer = binding.settingsOpenKeyboardOnAppDrawer.isChecked
        }
    }

    private fun setupCloseAppDrawerOnOtherAppOpen() {
        binding.settingsCloseAppDrawerOnOtherApp.isChecked = config.closeAppDrawer
        binding.settingsCloseAppDrawerOnOtherAppHolder.setOnClickListener {
            binding.settingsCloseAppDrawerOnOtherApp.toggle()
            config.closeAppDrawer = binding.settingsCloseAppDrawerOnOtherApp.isChecked
        }
    }

    private fun setupGlassIcons() {
        binding.settingsGlassIcons.isChecked = config.drawerGlassIcons
        binding.settingsGlassIconsHolder.setOnClickListener {
            binding.settingsGlassIcons.toggle()
            config.drawerGlassIcons = binding.settingsGlassIcons.isChecked
        }
    }

    private fun setupDrawerColumnCount() {
        val currentColumnCount = config.drawerColumnCount
        binding.settingsDrawerColumnCount.text = currentColumnCount.toString()
        binding.settingsDrawerColumnCountHolder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in 1..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            GlassRadioDialog(
                activity = this,
                title = getString(org.fossify.commons.R.string.column_count),
                items = items,
                checkedId = currentColumnCount
            ) { picked ->
                val newColumnCount = picked as Int
                if (currentColumnCount != newColumnCount) {
                    config.drawerColumnCount = newColumnCount
                    setupDrawerColumnCount()
                }
            }
        }
    }

    private fun setupDrawerSearchBar() {
        val showSearchBar = config.showSearchBar
        binding.settingsShowSearchBar.isChecked = showSearchBar
        binding.settingsDrawerSearchHolder.setOnClickListener {
            binding.settingsShowSearchBar.toggle()
            config.showSearchBar = binding.settingsShowSearchBar.isChecked
            binding.settingsOpenKeyboardOnAppDrawerHolder.beVisibleIf(config.showSearchBar)
        }
    }

    private fun setupShowDrawerAppLabels() {
        binding.settingsShowDrawerAppLabels.isChecked = config.showDrawerAppLabels
        binding.settingsShowDrawerAppLabelsHolder.setOnClickListener {
            binding.settingsShowDrawerAppLabels.toggle()
            config.showDrawerAppLabels = binding.settingsShowDrawerAppLabels.isChecked
        }
    }

    private fun setupHomeRowCount() {
        val currentRowCount = config.homeRowCount
        binding.settingsHomeScreenRowCount.text = currentRowCount.toString()
        binding.settingsHomeScreenRowCountHolder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_ROW_COUNT..MAX_ROW_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.row_counts, i, i
                        )
                    )
                )
            }

            GlassRadioDialog(
                activity = this,
                title = getString(org.fossify.commons.R.string.row_count),
                items = items,
                checkedId = currentRowCount
            ) { picked ->
                val newRowCount = picked as Int
                if (currentRowCount != newRowCount) {
                    config.homeRowCount = newRowCount
                    setupHomeRowCount()
                }
            }
        }
    }

    private fun setupHomeColumnCount() {
        val currentColumnCount = config.homeColumnCount
        binding.settingsHomeScreenColumnCount.text = currentColumnCount.toString()
        binding.settingsHomeScreenColumnCountHolder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_COLUMN_COUNT..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            GlassRadioDialog(
                activity = this,
                title = getString(org.fossify.commons.R.string.column_count),
                items = items,
                checkedId = currentColumnCount
            ) { picked ->
                val newColumnCount = picked as Int
                if (currentColumnCount != newColumnCount) {
                    config.homeColumnCount = newColumnCount
                    setupHomeColumnCount()
                }
            }
        }
    }

    private fun setupShowHomeAppLabels() {
        binding.settingsShowHomeAppLabels.isChecked = config.showHomeAppLabels
        binding.settingsShowHomeAppLabelsHolder.setOnClickListener {
            binding.settingsShowHomeAppLabels.toggle()
            config.showHomeAppLabels = binding.settingsShowHomeAppLabels.isChecked
        }
    }

    /** Home screen layout: classic / all-apps-as-home / drawer-only home. */
    private fun setupHomeScreenMode() {
        val currentMode = config.homeScreenMode
        binding.settingsHomeScreenMode.text = getString(
            when (currentMode) {
                HOME_MODE_ALL_APPS_ON_HOME -> R.string.home_mode_all_apps
                HOME_MODE_DRAWER_ONLY -> R.string.home_mode_drawer_only
                else -> R.string.home_mode_classic
            }
        )
        binding.settingsHomeScreenModeHolder.setOnClickListener {
            val items = ArrayList<RadioItem>().apply {
                add(RadioItem(HOME_MODE_CLASSIC, getString(R.string.home_mode_classic)))
                add(RadioItem(HOME_MODE_ALL_APPS_ON_HOME, getString(R.string.home_mode_all_apps)))
                add(RadioItem(HOME_MODE_DRAWER_ONLY, getString(R.string.home_mode_drawer_only)))
            }

            GlassRadioDialog(
                activity = this,
                title = getString(R.string.home_screen_layout),
                items = items,
                checkedId = currentMode
            ) { picked ->
                val newMode = picked as Int
                if (currentMode != newMode) {
                    config.homeScreenMode = newMode
                    setupHomeScreenMode()
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        binding.settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
        binding.settingsLanguageHolder.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun setupManageHiddenIcons() {
        binding.settingsManageHiddenIconsHolder.setOnClickListener {
            startActivity(Intent(this, HiddenIconsActivity::class.java))
        }
    }

    private fun launchAbout() {
        val licenses = 0L
        val faqItems = ArrayList<FAQItem>()

        if (resources.getBoolean(org.fossify.commons.R.bool.is_google_play_build)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }
}
