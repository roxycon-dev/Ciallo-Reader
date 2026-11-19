package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.PowerManager
import com.example.i18n.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TtsPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: TtsManager? = null
    private var session = -1L
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var updates: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("session", -1L) ?: -1L
        if (intent?.action != null) {
            val active = owner?.takeIf { id == session && it.ownsService(id) }
            when (intent.action) {
                "pause" -> active?.pause()
                "previous" -> active?.moveParagraph(-1)
                "next" -> active?.moveParagraph(1)
            }
            if (active == null && owner?.ownsService(session) != true) stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val requestedOwner = manager?.takeIf { it.ownsService(id) }
        // Fulfil a queued foreground start even if playback was cancelled before delivery.
        if (requestedOwner == null && owner?.ownsService(session) == true) return START_NOT_STICKY
        ensureChannel()
        if (requestedOwner != null) startMediaSession()
        startForeground(NOTIFICATION_ID, notification(id, requestedOwner?.state?.value?.chapter))
        if (requestedOwner == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        owner = requestedOwner
        session = id
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + ":reading").apply { acquire() }
        }
        if (requestedOwner.serviceStarted(id)) {
            updates?.cancel()
            updates = scope.launch {
                requestedOwner.state.map { Triple(it.chapter, it.playing, it.preparing) }.distinctUntilChanged().collect { (chapter, playing, preparing) ->
                    if (session == id && requestedOwner.ownsService(id)) {
                        publishMediaState(chapter, playing, preparing)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(id, chapter))
                    }
                }
            }
        } else stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun startMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSession(this, "CialloListening").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() { owner?.pause() }
                override fun onStop() { owner?.stop() }
                override fun onSkipToNext() { owner?.moveParagraph(1) }
                override fun onSkipToPrevious() { owner?.moveParagraph(-1) }
                override fun onFastForward() { owner?.skipSeconds(15) }
                override fun onRewind() { owner?.skipSeconds(-15) }
            })
            isActive = true
        }
    }

    private fun publishMediaState(chapter: ListeningChapter?, playing: Boolean, preparing: Boolean) {
        mediaSession?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, chapter?.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter?.bookTitle).build())
        val state = if (preparing) PlaybackState.STATE_BUFFERING else
            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        mediaSession?.setPlaybackState(PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        ).setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, AppLocale.t("听书"), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(id: Long, chapter: ListeningChapter?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        mediaSession?.sessionToken?.let(style::setMediaSession)
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(chapter?.bookTitle ?: AppLocale.t("听书"))
            .setContentText(chapter?.title)
            .setContentIntent(open)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, AppLocale.t("上一段"), action(id, "previous")).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, AppLocale.t("暂停"), action(id, "pause")).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, AppLocale.t("下一段"), action(id, "next")).build())
            .build()
    }

    private fun action(id: Long, action: String): PendingIntent =
        PendingIntent.getService(
            this, 0, Intent(this, TtsPlaybackService::class.java).setAction(action)
                .setData(Uri.parse("ciallo-tts://playback/$id/$action")).putExtra("session", id),
            PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        owner?.serviceEnded(session)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "reading_tts"
        private const val NOTIFICATION_ID = 302
        internal var manager: TtsManager? = null
    }
}
