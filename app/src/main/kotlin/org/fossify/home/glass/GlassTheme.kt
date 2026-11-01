package org.fossify.home.glass

import android.content.Context
import android.content.res.Configuration

/**
 * Fossil glass engine — theme probe.
 *
 * Self-contained day/night detection so glass surfaces stay consistent
 * regardless of which commons helpers a given build exposes.
 */
fun Context.isNightMode(): Boolean {
    return try {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    } catch (_: Exception) {
        false
    }
}
