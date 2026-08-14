package com.reweb.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.reweb.browser.R
import com.reweb.browser.browser.BrowserActivity
import com.reweb.browser.engine.MediaPlaybackState
import java.lang.ref.WeakReference

/**
 * Keeps web audio playable while ReWeb is in the background, and publishes
 * lock-screen / notification transport controls.
 *
 * The service holds no player of its own — the audio belongs to the WebView. Its
 * jobs are to make the process a foreground one (so Android does not reclaim it
 * mid-track) and to translate notification and headset button presses back into
 * play/pause calls on the page.
 *
 * It runs only while something is actually playing, and stops itself as soon as
 * playback ends. There is no persistent background service.
 */
class MediaPlaybackService : Service() {

    /** Implemented by whatever currently owns a playing page. */
    interface Commands {
        fun onMediaPlayRequested()
        fun onMediaPauseRequested()
        fun onMediaStopRequested()
    }

    private var session: MediaSessionCompat? = null
    private var lastState: MediaPlaybackState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    commands?.get()?.onMediaPlayRequested()
                }

                override fun onPause() {
                    commands?.get()?.onMediaPauseRequested()
                }

                override fun onStop() {
                    commands?.get()?.onMediaStopRequested()
                    stopPlayback()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)

        when (intent?.action) {
            ACTION_UPDATE -> {
                val state = MediaPlaybackState(
                    isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false),
                    title = intent.getStringExtra(EXTRA_TITLE),
                    artist = intent.getStringExtra(EXTRA_ARTIST),
                    pageUrl = null
                )
                if (state.isPlaying) showPlaying(state) else showPaused(state)
            }
            ACTION_PLAY -> commands?.get()?.onMediaPlayRequested()
            ACTION_PAUSE -> commands?.get()?.onMediaPauseRequested()
            ACTION_STOP -> {
                commands?.get()?.onMediaStopRequested()
                stopPlayback()
            }
        }
        // Not sticky: if the process dies the page is gone too, so there is nothing
        // meaningful to restart.
        return START_NOT_STICKY
    }

    private fun showPlaying(state: MediaPlaybackState) {
        lastState = state
        isActive = true
        publishSessionState(state, PlaybackStateCompat.STATE_PLAYING)
        val notification = buildNotification(state, isPlaying = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showPaused(state: MediaPlaybackState) {
        lastState = state
        isActive = false
        publishSessionState(state, PlaybackStateCompat.STATE_PAUSED)
        // Leave the notification up but drop the foreground guarantee, so the
        // process becomes reclaimable again while paused.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(state, isPlaying = false))
        stopForegroundCompat(removeNotification = false)
    }

    private fun stopPlayback() {
        isActive = false
        session?.isActive = false
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun publishSessionState(state: MediaPlaybackState, playbackState: Int) {
        val session = session ?: return
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist.orEmpty())
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                // The page owns the timeline; ReWeb cannot read a position from it,
                // so the notification shows no scrubber rather than a wrong one.
                .setState(playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    private fun buildNotification(state: MediaPlaybackState, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags()
        )

        val toggleAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                getString(R.string.media_pause),
                servicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                getString(R.string.media_play),
                servicePendingIntent(ACTION_PLAY)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_media_note)
            .setContentTitle(state.title?.takeIf { it.isNotBlank() } ?: getString(R.string.media_playing))
            .setContentText(state.artist.orEmpty())
            .setContentIntent(contentIntent)
            .setDeleteIntent(servicePendingIntent(ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(toggleAction)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_stop,
                    getString(R.string.media_stop),
                    servicePendingIntent(ACTION_STOP)
                )
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, MediaPlaybackService::class.java).setAction(action),
        pendingIntentFlags()
    )

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.media_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.media_channel_description)
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_UPDATE = "com.reweb.browser.media.UPDATE"
        const val ACTION_PLAY = "com.reweb.browser.media.PLAY"
        const val ACTION_PAUSE = "com.reweb.browser.media.PAUSE"
        const val ACTION_STOP = "com.reweb.browser.media.STOP"

        private const val EXTRA_PLAYING = "playing"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"

        private const val CHANNEL_ID = "reweb_media"
        private const val NOTIFICATION_ID = 1001
        private const val SESSION_TAG = "ReWebMedia"

        /**
         * Weak so that a destroyed activity is never held alive by the service.
         * A null referent simply means no page is available to command.
         */
        private var commands: WeakReference<Commands>? = null

        /**
         * True while a page is actually playing. Read by the browser activity to
         * decide whether it may pause its engines when the user leaves — pausing
         * a WebView stops its audio, so background playback depends on this.
         */
        @Volatile
        var isActive: Boolean = false
            private set

        fun setCommandHandler(handler: Commands?) {
            commands = handler?.let { WeakReference(it) }
        }

        /**
         * Pushes the current playback state to the service, starting it if audio
         * began and stopping it when playback ends.
         */
        fun update(context: Context, state: MediaPlaybackState) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_PLAYING, state.isPlaying)
                putExtra(EXTRA_TITLE, state.title)
                putExtra(EXTRA_ARTIST, state.artist)
            }
            runCatching {
                if (state.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MediaPlaybackService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
