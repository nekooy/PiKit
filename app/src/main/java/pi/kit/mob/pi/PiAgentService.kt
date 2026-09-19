package pi.kit.mob.pi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import pi.kit.mob.R
import pi.kit.mob.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Keeps the agent process alive and privileged while the UI is backgrounded.
 *
 * The agent is a long-lived child process that can be mid-turn for minutes; an
 * Activity-scoped owner would have it killed as soon as the user switched apps.
 * This service holds a foreground notification so the OS leaves the process
 * alone, and deliberately owns no state of its own — [PiAgentSession] is the
 * single source of truth, so the UI can bind, unbind and re-attach freely.
 */
class PiAgentService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch {
                PiAgentSession.of(this@PiAgentService).stopAgent()
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        promoteToForeground()

        lifecycleScope.launch {
            val session = PiAgentSession.of(this@PiAgentService)
            val started = session.startAgent()
            if (!started) {
                // Nothing to supervise; drop the notification rather than
                // leaving a permanent "running" lie in the shade.
                stopForegroundCompat()
                stopSelf()
            }
        }

        // Not START_STICKY: a restart with no Intent and no user present would
        // relaunch an agent nobody asked for.
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAgent = PendingIntent.getService(
            this,
            1,
            Intent(this, PiAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_agent_title))
            .setContentText(getString(R.string.notification_agent_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stopAgent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "pi.kit.mob.action.STOP_AGENT"

        private const val CHANNEL_ID = "pikit_agent"
        private const val NOTIFICATION_ID = 1

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, PiAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PiAgentService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
