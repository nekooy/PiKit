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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the agent process alive and privileged while the UI is backgrounded.
 *
 * The agent is a long-lived child process that can be mid-turn for minutes; an
 * Activity-scoped owner would have it killed as soon as the user switched apps.
 * This service holds a foreground notification so the OS leaves the process
 * alone, and deliberately owns no state of its own — [PiAgentSession] is the
 * single source of truth, so the UI can bind, unbind and re-attach freely.
 *
 * ## Why the shade entry only exists while the UI does not
 *
 * The report "去除 agent is running 的通知" is about a persistent entry that
 * said nothing the reader did not already know — they were *in* the app watching
 * the turn. A foreground service exists *because* of its notification, so the
 * notification cannot be deleted while the claim is held; what it can do is
 * appear only when the claim is doing work. [onUiVisible] drops the shade entry
 * the moment a window is in front (the Activity is already keeping the process
 * alive), and the status collector puts it back when the UI leaves. The
 * keep-alive is unchanged; the noise is not.
 *
 * When the entry *is* up it is silent: one short line of text so the shade
 * entry is not a blank icon, and nothing more. A user who wants even that gone
 * turns the *Agent* channel off in system settings — `startForeground` still
 * succeeds and the service stays a foreground service.
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
 * notification the moment it is not running (or the UI covers it), and tears
 * itself down when the agent is deliberately stopped.
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

        if (intent?.action == ACTION_UI_VISIBLE) {
            uiVisible.set(intent.getBooleanExtra(EXTRA_VISIBLE, true))
            syncNotification()
            return START_STICKY
        }

        // A service entered through `startForegroundService` owes the system a
        // `startForeground` call inside its deadline (5 s on Android 12+), and
        // it owes one even while the agent is still `Stopped` — which is the
        // state a cold launch is in, while the runtime image unpacks. Waiting
        // for `Running` before promoting is what turned a first-run install into
        // `ForegroundServiceDidNotStartInTimeException`. Promote first, then let
        // [syncNotification] take the notification away again if the agent is
        // not actually up *or* the UI already covers it.
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
     * Shows the ongoing notification only while the agent is actually up *and*
     * the UI is not already in front.
     *
     * Called from [onStartCommand], from the status collector and from
     * [onUiVisible], so the shade never keeps claiming anything across a crash,
     * a deliberate stop, or the user simply looking at the app.
     */
    private fun syncNotification() {
        val status = PiAgentSession.of(this).agent.value
        val active = status is AgentStatus.Starting || status is AgentStatus.Running
        if (active && !uiVisible.get()) {
            promoteToForeground()
        } else {
            stopForegroundCompat()
        }
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

    /**
     * The ongoing keep-alive marker.
     *
     * `PRIORITY_MIN` + a `MIN`-importance channel means no peek, no sound, no
     * badge — it sits at the bottom of the shade as the price of the foreground
     * claim. The title and body are one short English sentence so a shade entry
     * with nothing on it does not read as a rendering fault; a user who wants
     * even that gone turns the *Agent* channel off in system settings —
     * `startForeground` still succeeds and the service stays a foreground
     * service.
     */
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stopAgent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "pi.kit.mob.action.STOP_AGENT"
        const val ACTION_UI_VISIBLE = "pi.kit.mob.action.UI_VISIBLE"
        const val EXTRA_VISIBLE = "visible"

        private const val CHANNEL_ID = "pikit_agent"
        private const val NOTIFICATION_ID = 1

        /** True while a window of this app is in front. */
        private val uiVisible = AtomicBoolean(false)

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
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
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

        /**
         * Tells the service whether a window is in front, so the shade entry
         * appears only when the keep-alive claim is actually doing work.
         *
         * [MainActivity] calls this from `onStart`/`onStop`. A service that is
         * not up yet ignores the message: the next `start`/`syncNotification`
         * reads the flag for itself.
         *
         * Also the moment to hold (or drop) the background wake lock: the turn
         * lock is owned by [PiAgentSession] and only covers a live turn, while
         * the "息屏后 terminated" report is about the process going away
         * *between* turns as well. A partial lock while the UI is gone and the
         * agent is up keeps the child's stdout drained for as long as the user
         * has actually left.
         */
        fun onUiVisible(context: Context, visible: Boolean) {
            uiVisible.set(visible)
            syncBackgroundWakeLock(context, visible)
            val intent = Intent(context, PiAgentService::class.java)
                .setAction(ACTION_UI_VISIBLE)
                .putExtra(EXTRA_VISIBLE, visible)
            runCatching { context.startService(intent) }
        }

        /**
         * A partial wake lock held while the UI is gone and the agent is up.
         *
         * Separate from the turn lock on purpose: that one is acquired on
         * `agent_start` and released on `agent_settled`, so a screen-off with
         * nothing streaming had nothing holding the CPU. This one is the
         * "user left the app" lock, and it is what the keep-alive is for.
         */
        private var backgroundWakeLock: android.os.PowerManager.WakeLock? = null

        private fun syncBackgroundWakeLock(context: Context, uiGone: Boolean) {
            val agentUp = runCatching {
                PiAgentSession.of(context).agent.value
            }.getOrNull().let {
                it is AgentStatus.Starting || it is AgentStatus.Running
            }
            if (uiGone && agentUp) {
                val lock = backgroundWakeLock ?: (context.getSystemService(Context.POWER_SERVICE)
                    as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pikit:agent-bg")
                    .also {
                        it.setReferenceCounted(false)
                        backgroundWakeLock = it
                    }
                // Timed for the same reason the turn lock is: a lock the app
                // forgets to release must not outlive a day. Re-acquired on
                // every return to the background.
                if (!lock.isHeld) lock.acquire(BACKGROUND_WAKE_LOCK_MAX_MS)
            } else {
                backgroundWakeLock?.takeIf { it.isHeld }?.release()
            }
        }

        private const val BACKGROUND_WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L

        fun stop(context: Context) {
            context.startService(
                Intent(context, PiAgentService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
