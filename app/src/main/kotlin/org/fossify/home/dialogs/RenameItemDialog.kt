package org.fossify.home.dialogs

import android.app.Activity
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.home.extensions.homeScreenGridItemsDB
import org.fossify.home.glass.GlassInputDialog
import org.fossify.home.models.HomeScreenGridItem

/**
 * Fossil liquid glass rename: the framework AlertDialog is replaced by a
 * centered [GlassInputDialog]; the public contract (activity + item +
 * callback) is unchanged.
 */
class RenameItemDialog(
    val activity: Activity,
    val item: HomeScreenGridItem,
    val callback: () -> Unit,
) {

    init {
        GlassInputDialog(
            activity = activity,
            title = activity.getString(org.fossify.commons.R.string.rename),
            initialText = item.title,
            onConfirm = { newTitle ->
                if (newTitle.isNotEmpty()) {
                    ensureBackgroundThread {
                        val result = activity.homeScreenGridItemsDB.updateItemTitle(newTitle, item.id!!)
                        if (result == 1) {
                            activity.runOnUiThread { callback() }
                        } else {
                            activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                        }
                    }
                } else {
                    activity.toast(org.fossify.commons.R.string.value_cannot_be_empty)
                }
            }
        ).show()
    }
}
