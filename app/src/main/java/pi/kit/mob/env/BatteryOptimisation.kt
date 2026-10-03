package pi.kit.mob.env

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The battery-optimisation exemption: whether this app holds it, how to ask for it,
 * and whether it has been asked for.
 *
 * ## What the exemption is for
 *
 * The foreground service and the turn's wake lock keep a *running* turn alive while
 * the screen is off — but neither survives a power manager that has already decided
 * to reclaim the whole process, which is the report "息屏后会直接 terminated". The
 * exemption is the only lever the OS offers for that, and it is the platform's own
 * dialog: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` puts a yes/no box on screen
 * for this package, and an app cannot grant it to itself.
 *
 * ## Why the ask lives here rather than on the page that shows it
 *
 * Two callers need the same four answers and neither owns them: the first-launch
 * dialog in `PiKitRoot` asks once, and the agent page's keep-alive row reports the
 * state for ever after. The state is a fact about the *install* — it outlives the
 * page, and the system settings app can change it while the app is in the
 * background — so it is read at the call site rather than cached in a flow, and the
 * one thing that *is* persisted here is the record of having asked, which is what
 * makes the first-launch dialog a one-time event instead of a nag.
 */
object BatteryOptimisation {

    /** Preference file the one-time ask is recorded in. */
    const val PREFS_NAME = "pikit_power"

    /**
     * Whether the OS is allowed to leave this app alone in the background.
     *
     * Below M every app is exempt by construction — there is no optimisation to opt
     * out of — so the answer is true rather than "the API does not exist". A device
     * that cannot answer at all reads as *not* exempt, which is the safe way round:
     * the row then offers the ask, and an ask that cannot be honoured leaves the row
     * saying the same thing it said before.
     */
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Whether the first-launch dialog has already been answered.
     *
     * Recorded on the *answer*, not on the showing, and never reset: an install that
     * reopened this on every launch would be nagging, and the agent page's row is
     * always there for anyone who dismissed it by mistake.
     */
    fun hasAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * Whether the first-launch dialog is still owed.
     *
     * An install that already holds the exemption is not asked: there is nothing to
     * grant, and a dialog that leads to a system box already answering "allowed"
     * reads as a bug.
     */
    fun needsAsk(context: Context): Boolean = !isExempt(context) && !hasAsked(context)

    /**
     * Asks the system for the exemption.
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the one that shows a yes/no
     * dialog for *this* package; the bare `ACTION_IGNORE_BATTERY_OPTIMIZATIONS`
     * settings screen is the fallback for a device that refuses the direct ask (some
     * OEMs do, and one Android 16 emulator image answers
     * `ActivityNotFoundException` for it). Failing even to open either is silent:
     * nothing was granted, the row keeps saying so, and there is no useful second
     * thing the app can do about it.
     *
     * `NEW_TASK` because the caller may be the application context — the first-launch
     * dialog's is the composition's, which is an activity's today and need not stay
     * one — and because the settings screen is a different task's activity anyway.
     */
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Spelled as a literal because some SDK stubs do not surface the constant
        // even though the activity exists.
        val fallback = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATIONS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }
            .onFailure { runCatching { context.startActivity(fallback) } }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val KEY_ASKED = "asked"
}
