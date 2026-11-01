# Fossil Launcher (GPL-3.0 fork of FossifyOrg/Launcher)
#
# Upstream Fossify commons shows a randomized "counterfeit app" warning to any
# build whose applicationId does not start with org.fossify. Fossil is a legal
# GPL-3.0 fork under its own identity (app.orubus.fossil.launcher), so the
# upstream anti-piracy nag is a false positive here. R8 strips every call site
# of the warning from release builds (debug builds are developer-only).
#
# The warning is an extension function compiled into ActivityKt; stripping the
# static method's side effects removes the dialog at every trigger site
# (BaseSimpleActivity.onCreate, startCustomizationActivity, CustomizationActivity)
# without touching any other commons behavior.
-assumenosideeffects class org.fossify.commons.extensions.ActivityKt {
    void showModdedAppWarning(org.fossify.commons.activities.BaseSimpleActivity);
}

# Fossil: keep liquid-glass reflection helpers intact
-keep class org.fossify.home.glass.** { *; }
