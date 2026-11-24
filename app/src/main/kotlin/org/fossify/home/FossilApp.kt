package org.fossify.home

import org.fossify.commons.FossifyApp
import org.fossify.home.helpers.CrashGuard

/**
 * Fossil application class — installs the crash capture guard as early as
 * possible so even startup crashes leave a readable trace behind.
 */
class FossilApp : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
