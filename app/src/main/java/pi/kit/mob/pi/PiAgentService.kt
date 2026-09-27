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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the agent process alive and privileged while the UI is backgrounded.
 *
 * The agent is a long-lived child process that can be mid-turn for minutes; an
 * Activity-scoped owner would have it killed as soon as the user switched apps.
 * This service holds a foreground notification so the OS leaves the process
 * alone, and deliberately owns no state of its own — [PiAgentSession] is the
 * single source of truth, so the UI can bind, unbind and re-attach freely.
 *
 * ## Why there is no in-app switch for the notification
 *
 * The shade entry is not a cosmetic preference: a foreground service exists
 * *because* of its notification, so an in-app "hide it" switch would either
 * have to drop the foreground claim (and with it the keep-alive) or lie about
 * a service being silent when Android 12+ forbids that. A user who does not
 * want to see it turns the channel off in system settings instead — the
 * service stays a foreground service and the notification is merely hidden,
 * which is strictly better than anything an in-app switch could offer.
 *
 * ## Why the service tracks [AgentStatus]
 *
 * It used to decide everything once, in [onStartCommand]: promote, then stop
 * if that one `startAgent` returned false. Two things were wrong with that.
 * A start that later succeeded from the UI — the Agent page's Restart, a
 * `scheduleRestart` after a settings edit, the one automatic recovery after a
 * crash — never brought the service back, so the turn that needed keep-alive
 * most was the one running without it. And a deliberate stop from the Agent
 * page left the shade claiming "running". The collector below is the fix: the
 * service stays as long as the agent has something to supervise, drops the
 * notification the moment it is not running, and tears itself down when the
 * agent is deliberately stopped.
 */
class PiAgentService : LifecycleService() {

    private var stopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Drop the shade entry (and the foreground claim with it) when the agent
        // is not actually running, and take the whole service down when a stop
        // the user asked for lands. `sawActive` skips the initial `Stopped` a
        // cold service sees before its own `startAgent` has been called — that
        // one must not race `onStartCommand` into `stopSelf`.
        lifecycleScope.launch {
            var sawActive = false
            PiAgentSession.of(this@PiAgentService).agent.collect { status ->
                when (status) {
                    is AgentStatus.Stopped -> {
                        if (sawActive) {
                            stopForegroundCompat()
                            // Debounced, because `scheduleRestart` and
                            // `restartAgent` stop the agent and start it again
                            // in one breath and a `stopSelf` landing between
                            // the two would tear down the service the start
                            // just asked for. A start inside the window cancels
                            // this job; a stop that stays stopped still dies.
                            stopJob?.cancel()
                            stopJob = lifecycleScope.launch {
                                delay(STOP_DEBOUNCE_MS)
                                stopSelf()
                            }
                        }
                    }
                    is AgentStatus.Starting, is AgentStatus.Running -> {
                        sawActive = true
                        stopJob?.cancel()
                        syncNotification()
                    }
                    is AgentStatus.Failed -> {
                        sawActive = true
                        stopJob?.cancel()
                        // Honest shade: a process that is not answering is not
                        // "running". The service itself stays up for the one
                        // automatic restart, and that restart's `startAgent`
                        // puts the notification back.
                        stopForegroundCompat()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch {
                PiAgentSession.of(this@PiAgentService).stopAgent()
                stopForegroundCompat()
                stopSelf()
            }
            // A deliberate stop must not be undone by a sticky restart.
            return START_NOT_STICKY
        }

        // A service entered through `startForegroundService` owes the system a
        // `startForeground` call inside its deadline (5 s on Android 12+), and
        // it owes one even while the agent is still `Stopped` — which is the
        // state a cold launch is in, while the runtime image unpacks. Waiting
        // for `Running` before promoting is what turned a first-run install into
        // `ForegroundServiceDidNotStartInTimeException`. Promote first, then let
        // [syncNotification] take the notification away again if the agent is
        // not actually up; a brief shade entry that says "running" during
        // `Starting` is the cheaper lie.
        promoteToForeground()

        syncNotification()

        lifecycleScope.launch {
            val session = PiAgentSession.of(this@PiAgentService)
            val started = session.startAgent()
            if (!started) {
                // Nothing to supervise; drop the notification rather than
                // leaving a permanent "running" lie in the shade. The collector
                // covers the case where a later start succeeds from the UI.
                stopForegroundCompat()
                stopSelf()
            }
        }

        // Sticky: if the OS reclaims the process mid-turn, the service comes
        // back and `startAgent` restores the remembered session. A null intent
        // after that reclaim is not "nobody asked for an agent" — it is "the
        // agent was already supposed to be running" (the report of a turn
        // interrupted by 切后台/锁屏). The explicit stop path above is the one
        // that must stay `START_NOT_STICKY`.
        return START_STICKY
    }

    /**
     * Shows the ongoing notification only while the agent is actually up.
     *
     * Called from [onStartCommand] and from the status collector, so the shade
     * never keeps claiming "running" across a crash or a deliberate stop.
     */
    private fun syncNotification() {
        val status = PiAgentSession.of(this).agent.value
        val active = status is AgentStatus.Starting || status is AgentStatus.Running
        if (active) promoteToForeground() else stopForegroundCompat()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
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

        /**
         * How long a deliberate stop waits before the service dies, so a
         * stop-then-start in one coroutine (the two restart helpers) is one
         * life of the service rather than a death and a birth.
         */
        private const val STOP_DEBOUNCE_MS = 500L

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

        /** Brings the supervising service up, or re-syncs one that already is. */
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
