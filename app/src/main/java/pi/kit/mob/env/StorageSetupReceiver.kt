package pi.kit.mob.env

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pi.kit.mob.pi.PiHost

/**
 * Handles the broadcast `termux-setup-storage` sends.
 *
 * Both this app and upstream Termux ship the same shell wrapper, and it does not
 * create any links itself — it broadcasts
 * `com.<package>.app.reload_style` with `storage` and expects the app to do the
 * work. Since the storage page is gone (§14) this is the *only* way to ask for the
 * farm again without a relaunch, which is exactly what the command is for.
 */
class StorageSetupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || intent.getStringExtra(ACTION) != "storage") return

        val host = PiHost.of(context.applicationContext)
        if (!StorageAccess.isGranted()) {
            // The command cannot raise the system page itself, so it reports
            // what is missing rather than failing silently.
            Log.w(TAG, "Storage access has not been granted; ignoring $ACTION")
            return
        }
        host.syncStorageLinks()
    }

    private companion object {
        const val TAG = "PiKitStorage"

        /**
         * Literal, not `context.packageName + ...`: the bundled runtime is
         * relocated to this application id at build time, so the shell wrapper
         * in the image always sends exactly this action.
         */
        const val ACTION = "pi.kit.mob.app.reload_style"
    }
}
