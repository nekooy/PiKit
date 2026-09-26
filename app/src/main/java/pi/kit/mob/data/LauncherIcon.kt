package pi.kit.mob.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Which of the app's two launcher icons the home screen shows.
 *
 * ## Why a choice of icon is a choice of entry point
 *
 * Android reads an app's launcher icon out of its **manifest**. There is no API
 * that changes one at run time — the only lever the platform offers is enabling and
 * disabling *components* (`setComponentEnabledSetting`), which moves the entry
 * rather than repainting it. So the app declares one `activity-alias` per icon,
 * both targeting [pi.kit.mob.ui.MainActivity], and [applyLauncherIcon] enables
 * exactly one of them: the icon the home screen draws is the `android:icon` of the
 * alias that is on. `AndroidManifest.xml` carries the aliases and the reasoning
 * behind their shape.
 *
 * ## What it does not move
 *
 * The **application's** icon (`android:icon` on `<application>`) is untouched, so
 * the recent-tasks card, the app's own page in system settings and the
 * "uninstall" dialog keep the black icon whichever way this is set. That is
 * deliberate and not half a feature: those surfaces have one icon and no
 * per-entry notion, and the alternative — repainting every one of them — is the
 * app rewriting its own identity rather than offering a preference.
 *
 * [DEFAULT] is [DARK], which is also the alias the manifest declares enabled, so a
 * fresh install shows the black icon and writes no component state at all.
 */
enum class LauncherIcon(
    /** What is written to preferences; an unrecognised code resolves to [DEFAULT]. */
    val code: String,
    /**
     * The `activity-alias` in the manifest, named *relative* to the package.
     *
     * Relative because the package name is the one fact this file must not spell
     * again: the application id is `pi.kit.mob` by a hard constraint (the bundled
     * runtime is relocated by a length-preserving byte rewrite), and a literal
     * here would be a second copy of it that a rename would leave behind.
     */
    val alias: String,
    /**
     * Whether the manifest declares that alias enabled.
     *
     * Needed because `getComponentEnabledSetting` answers
     * `COMPONENT_ENABLED_STATE_DEFAULT` for a component nobody has touched, and
     * that means "whatever the manifest said", not "enabled". Without this the
     * first launch of a fresh install would write the state it already has.
     */
    val declaredEnabled: Boolean,
) {
    /** Black field, white mark — the icon the app shipped with. */
    DARK("dark", "ui.LauncherDark", declaredEnabled = true),

    /** White field, black mark. */
    LIGHT("light", "ui.LauncherLight", declaredEnabled = false),
    ;

    companion object {
        val DEFAULT = DARK

        fun fromCode(code: String?): LauncherIcon =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * Makes the home screen show [icon], and only it.
 *
 * Idempotent: a call that finds the components already in the state it wants
 * writes nothing, which is what makes it safe to run on every launch — the
 * preference is the truth and this makes the installed components agree with it.
 *
 * Enabled before disabled, never the other way round. The other order leaves a
 * moment in which neither alias is on, and a launcher that notices is a launcher
 * that has just been told the app has no entry point.
 *
 * `DONT_KILL_APP`: switching cosmetics is not a reason to tear down a running
 * agent. The cost is that the *task* the change is made from belongs to an alias
 * that is now off, and a launcher is free to forget where it put the icon — which
 * is what the row's own footnote warns about.
 */
fun applyLauncherIcon(context: Context, icon: LauncherIcon) {
    val appContext = context.applicationContext
    val packageManager = appContext.packageManager

    applyAlias(packageManager, appContext, icon, enabled = true)
    LauncherIcon.entries.filterNot { it == icon }.forEach { other ->
        applyAlias(packageManager, appContext, other, enabled = false)
    }
}

private fun applyAlias(
    packageManager: PackageManager,
    context: Context,
    icon: LauncherIcon,
    enabled: Boolean,
) {
    val component = ComponentName(
        context.packageName,
        "${context.packageName}.${icon.alias}",
    )
    val current = when (packageManager.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        else -> icon.declaredEnabled
    }
    if (current == enabled) return

    runCatching {
        packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }.onFailure { error ->
        // The one cause worth a name: an alias this enum knows and the manifest
        // does not, which is a rename that was made in one of the two files.
        Log.w(TAG, "no ${icon.alias} in the manifest — the launcher icon cannot be switched", error)
    }
}

private const val TAG = "PiKitLauncherIcon"
