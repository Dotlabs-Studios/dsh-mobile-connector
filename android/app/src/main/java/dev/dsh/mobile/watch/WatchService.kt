package dev.dsh.mobile.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.dsh.mobile.MainActivity
import dev.dsh.mobile.R
import dev.dsh.mobile.data.SessionStore
import dev.dsh.mobile.data.SseParser
import dev.dsh.mobile.data.WatchEvent
import dev.dsh.mobile.data.watchEvent
import dev.dsh.mobile.ui.AppVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps one SSE connection to the PC and turns the frames that need a person into
 * notifications.
 *
 * This is the difference between a remote control and a remote control that is
 * plugged in: an approval raised while the phone is in a pocket used to be invisible
 * until the app was opened, and the PC gave up waiting after two minutes. The app
 * polls when it is on screen; this service is what covers the hours when it is not.
 *
 * The connection is deliberately the *same* `/mobile/events` route the app never
 * used: the host already decides what is worth broadcasting, and this only reads
 * three of its frames. Everything here is best-effort — a failure reconnects with a
 * backoff, and a failure that lasts leaves the phone exactly as it was before.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The call currently holding the stream open, so it can be cancelled on stop. */
    @Volatile
    private var call: okhttp3.Call? = null

    /** Whether a stream loop is already running, so a second start is a no-op. */
    @Volatile
    private var streaming: Boolean = false

    /** The streaming client: no read timeout, because a quiet feed is a healthy one. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Ids already announced, so one approval does not buzz on every reconnect. */
    private val announced = LinkedHashSet<String>()

    /** When each session's last turn-end notification was posted, by session id. */
    private val lastTurnEnd = LinkedHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(FOREGROUND_ID, listeningNotification())
        if (!streaming) stream()
        return START_STICKY
    }

    override fun onDestroy() {
        call?.cancel()
        call = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Open the event stream and keep it open, reconnecting with a backoff.
     *
     * The loop ends only when the service does or when the pairing is gone: a phone
     * that lost its token must not sit in a reconnect loop against a host that will
     * answer 401 forever.
     */
    private fun stream() {
        val store = SessionStore(this)
        val host = store.host
        val token = store.token
        if (host.isNullOrBlank() || token.isNullOrBlank()) {
            stopSelf()
            return
        }
        val request = Request.Builder()
            .url("$host/mobile/events")
            .header("authorization", "Bearer $token")
            .header("accept", "text/event-stream")
            .build()
        streaming = true
        scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    val attempt = client.newCall(request)
                    call = attempt
                    attempt.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("stream refused: ${response.code}")
                        backoff = 1_000L
                        val source = response.body?.source() ?: throw IOException("stream has no body")
                        val parser = SseParser()
                        while (isActive) {
                            val line = source.readUtf8Line() ?: break
                            val payload = parser.feed(line) ?: continue
                            val event = watchEvent(payload) ?: continue
                            announce(event, store)
                        }
                    }
                } catch (_: IOException) {
                    // The link dropped, or the host restarted: wait and try again.
                } catch (_: IllegalStateException) {
                    // The stream was cancelled because the service is stopping.
                } finally {
                    call = null
                }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * Turn one event into a notification, skipping anything already announced.
     *
     * @param event the event decoded from a frame.
     * @param store the pairing, for the "turn ended" decision.
     */
    private fun announce(event: WatchEvent, store: SessionStore) {
        when (event) {
            is WatchEvent.Approval -> {
                if (!remember(event.approvalId)) return
                notify(
                    id = event.approvalId.hashCode(),
                    title = getString(R.string.watch_approval_title),
                    body = getString(R.string.watch_approval_body, event.toolName) + promptSuffix(event.prompt),
                )
            }

            is WatchEvent.Question -> {
                if (!remember(event.questionId)) return
                notify(
                    id = event.questionId.hashCode(),
                    title = getString(R.string.watch_question_title),
                    body = event.text.ifBlank { getString(R.string.watch_question_fallback) },
                )
            }

            is WatchEvent.TurnEnded -> {
                // A turn the user is watching end does not need a notification on
                // top of the transcript they are already reading.
                if (AppVisibility.visible) return
                if (!turnEndWorthTelling(event.sessionId)) return
                notify(
                    id = ("turn:" + event.sessionId).hashCode(),
                    title = getString(R.string.watch_turn_title),
                    body = getString(R.string.watch_turn_body),
                )
            }
        }
    }

    /**
     * Whether a session's turn end is worth a notification.
     *
     * One turn ending is one notification: a host that states the status twice, or
     * a reconnect that repeats it, must not buzz twice. The window is short on
     * purpose — a second turn ten minutes later is news again.
     *
     * @param sessionId the session whose turn ended.
     * @return true when a notification should be posted.
     */
    private fun turnEndWorthTelling(sessionId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastTurnEnd[sessionId]
        if (last != null && now - last < TURN_END_COOLDOWN_MS) return false
        lastTurnEnd[sessionId] = now
        while (lastTurnEnd.size > MAX_ANNOUNCED) {
            val oldest = lastTurnEnd.keys.firstOrNull() ?: break
            lastTurnEnd.remove(oldest)
        }
        return true
    }

    /** Keep an id in the announced set, bounded so a long night cannot grow it. */
    private fun remember(id: String): Boolean {
        if (!announced.add(id)) return false
        while (announced.size > MAX_ANNOUNCED) {
            val oldest = announced.firstOrNull() ?: break
            announced.remove(oldest)
        }
        return true
    }

    /** Add the host's own sentence to a body, when it wrote one. */
    private fun promptSuffix(prompt: String): String =
        if (prompt.isBlank()) "" else "\n" + prompt

    /**
     * Post one attention notification.
     *
     * @param id a stable id, so the same approval updates rather than duplicates.
     * @param title the notification's title.
     * @param body its text.
     */
    private fun notify(id: Int, title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(id, attentionNotification(title, body))
    }

    /** The ongoing notification, which is the service's own "I am listening" state. */
    private fun listeningNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(getString(R.string.watch_ongoing_title))
            .setContentText(getString(R.string.watch_ongoing_body))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.watch_stop), stopIntent())
            .build()

    /** One notification that asks for a person. */
    private fun attentionNotification(title: String, body: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp())
            .build()

    /** A tap on any notification opens the app. */
    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The stop action on the ongoing notification. */
    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, WatchService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Two channels: one that is always there, one that asks for something. */
    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.watch_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.watch_channel_service_note)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ATTENTION,
                getString(R.string.watch_channel_attention),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.watch_channel_attention_note)
            },
        )
    }

    companion object {
        /** Start or keep the stream open. */
        const val ACTION_START = "dev.dsh.mobile.watch.START"

        /** Stop the stream and the service. */
        const val ACTION_STOP = "dev.dsh.mobile.watch.STOP"

        private const val FOREGROUND_ID = 4301
        private const val CHANNEL_SERVICE = "watch-service"
        private const val CHANNEL_ATTENTION = "watch-attention"
        private const val MAX_ANNOUNCED = 256
        private const val MAX_BACKOFF_MS = 60_000L
        private const val TURN_END_COOLDOWN_MS = 20_000L

        /**
         * Start the watch service, from a context that is allowed to.
         *
         * @param context any context; the intent is explicit.
         */
        fun start(context: Context) {
            val intent = Intent(context, WatchService::class.java).setAction(ACTION_START)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * Stop the watch service, if it is running.
         *
         * @param context any context.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WatchService::class.java)) }
        }
    }
}
