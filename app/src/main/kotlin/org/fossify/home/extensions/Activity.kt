package org.fossify.home.extensions

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.R as CommonsR
import org.fossify.home.R
import org.fossify.home.activities.SettingsActivity
import org.fossify.home.glass.GlassMenuDialog
import org.fossify.home.helpers.ITEM_TYPE_FOLDER
import org.fossify.home.helpers.ITEM_TYPE_ICON
import org.fossify.home.helpers.ITEM_TYPE_WIDGET
import org.fossify.home.helpers.UNINSTALL_APP_REQUEST_CODE
import org.fossify.home.interfaces.ItemMenuListener
import org.fossify.home.models.HomeScreenGridItem

/**
 * Fossil v1.0.2 launch path.
 *
 * With a source rect (the tapped icon or the open folder panel) the launched
 * app is revealed by the system's clip-reveal animation expanding out of that
 * rect — the fluid, non-buggy variant of the reference video transition.
 * Without a rect it falls back to the legacy scale/fade animation.
 */
fun Activity.launchApp(
    packageName: String,
    activityName: String,
    sourceView: View? = null,
    sourceBounds: Rect? = null,
) {
    val options = when {
        sourceView != null && sourceView.width > 0 && sourceView.height > 0 -> {
            ActivityOptions.makeClipRevealAnimation(
                sourceView, 0, 0, sourceView.width, sourceView.height
            ).toBundle()
        }

        sourceBounds != null && sourceBounds.width() > 0 && sourceBounds.height() > 0 -> {
            (this as? org.fossify.home.activities.MainActivity)?.obtainLaunchAnchor(sourceBounds)
                ?.let { anchor ->
                    ActivityOptions.makeClipRevealAnimation(
                        anchor, 0, 0, sourceBounds.width(), sourceBounds.height()
                    ).toBundle()
                }
        }

        else -> null
    } ?: ActivityOptions.makeCustomAnimation(
        this,
        R.anim.fossil_app_open,
        R.anim.fossil_app_open_exit
    ).toBundle()

    (this as? org.fossify.home.activities.MainActivity)?.onAppLaunchingStarted()

    try {
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = packageName
            component = ComponentName.unflattenFromString("$packageName/$activityName")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            startActivity(this, options)
        }
    } catch (e: Exception) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            startActivity(launchIntent, options)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun Activity.launchAppInfo(packageName: String) {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        startActivity(this)
    }
}

fun Activity.canAppBeUninstalled(packageName: String): Boolean {
    return try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    } catch (ignored: Exception) {
        false
    }
}

fun Activity.uninstallApp(packageName: String) {
    Intent(Intent.ACTION_DELETE).apply {
        data = Uri.fromParts("package", packageName, null)
        startActivityForResult(this, UNINSTALL_APP_REQUEST_CODE)
    }
}

/**
 * Fossil liquid glass menu: the icon long-press actions live on a frosted
 * glass sheet instead of the framework PopupMenu. Same visibility rules and
 * callbacks as before, now with real blur and liquid ripples.
 */
fun Activity.showGridItemGlassMenu(
    gridItem: HomeScreenGridItem,
    isOnAllAppsFragment: Boolean,
    listener: ItemMenuListener,
): GlassMenuDialog {
    val items = mutableListOf<GlassMenuDialog.Item>()

    if (gridItem.type == ITEM_TYPE_ICON) {
        items.add(
            GlassMenuDialog.Item(
                title = getString(R.string.app_info),
                icon = getDrawable(CommonsR.drawable.ic_info_vector),
                onClick = { listener.appInfo(gridItem) }
            )
        )
    }

    if (gridItem.type == ITEM_TYPE_ICON && isOnAllAppsFragment) {
        items.add(
            GlassMenuDialog.Item(
                title = getString(CommonsR.string.hide),
                icon = getDrawable(CommonsR.drawable.ic_hide_vector),
                onClick = { listener.hide(gridItem) }
            )
        )
    }

    if (gridItem.type == ITEM_TYPE_WIDGET) {
        items.add(
            GlassMenuDialog.Item(
                title = getString(CommonsR.string.resize),
                icon = getDrawable(R.drawable.ic_resize_vector),
                onClick = { listener.resize(gridItem) }
            )
        )
    }

    if ((gridItem.type == ITEM_TYPE_ICON || gridItem.type == ITEM_TYPE_FOLDER)
        && !isOnAllAppsFragment
    ) {
        items.add(
            GlassMenuDialog.Item(
                title = getString(CommonsR.string.rename),
                icon = getDrawable(CommonsR.drawable.ic_rename_vector),
                onClick = { listener.rename(gridItem) }
            )
        )
    }

    if (!isOnAllAppsFragment) {
        items.add(
            GlassMenuDialog.Item(
                title = getString(CommonsR.string.remove),
                icon = getDrawable(CommonsR.drawable.ic_cross_vector),
                onClick = { listener.remove(gridItem) }
            )
        )
    }

    if (gridItem.type == ITEM_TYPE_ICON
        && canAppBeUninstalled(gridItem.packageName)
        && gridItem.packageName != packageName
    ) {
        items.add(
            GlassMenuDialog.Item(
                title = getString(R.string.uninstall),
                icon = getDrawable(org.fossify.commons.R.drawable.ic_delete_vector),
                danger = true,
                onClick = { listener.uninstall(gridItem) }
            )
        )
    }

    // app shortcuts get their own liquid glass section
    val launcherApps =
        applicationContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val shortcuts = if (launcherApps.hasShortcutHostPermission()) {
        try {
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                        or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                        or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            ).setPackage(gridItem.packageName)
            launcherApps.getShortcuts(query, Process.myUserHandle())
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }

    if (!shortcuts.isNullOrEmpty()) {
        var addedFirstShortcut = false
        shortcuts.forEach { shortcutInfo ->
            val iconSize = resources.getDimensionPixelSize(R.dimen.menu_icon_size)
            val iconDrawable = try {
                launcherApps.getShortcutIconDrawable(
                    shortcutInfo, resources.displayMetrics.densityDpi
                )
            } catch (_: Exception) {
                null
            }
            items.add(
                GlassMenuDialog.Item(
                    title = shortcutInfo.getLabel(),
                    icon = (iconDrawable ?: Color.TRANSPARENT.toDrawable())
                        .toBitmap(width = iconSize, height = iconSize)
                        .toDrawable(resources),
                    dividedAbove = !addedFirstShortcut,
                    onClick = {
                        listener.onAnyClick()
                        try {
                            launcherApps.startShortcut(
                                shortcutInfo.`package`,
                                shortcutInfo.id,
                                Rect(),
                                null,
                                Process.myUserHandle()
                            )
                        } catch (_: Exception) {
                        }
                    }
                )
            )
            addedFirstShortcut = true
        }
    }

    return GlassMenuDialog(
        activity = this,
        title = gridItem.title.takeIf { it.isNotBlank() },
        items = items
    ) {
        listener.onDismiss()
    }
}
