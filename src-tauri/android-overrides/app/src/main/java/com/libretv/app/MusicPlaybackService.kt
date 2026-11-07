package com.libretv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * 音乐后台播放前台服务。
 *
 * 为什么不用 WebView 里的 <audio>：离开音乐页面 = 文档销毁 = 播放中断；
 * 熄屏时 WebView 更会被系统挂起。原生 MediaPlayer 挂在前台服务里，
 * 换页/熄屏/切 App 都不受影响（锁由服务持有）。
 *
 * JS 侧通过 PlayerBridge 的 musicPlay/musicPause/... 控制（startService 意图），
 * 进度由 JS 轮询 musicGetPosition 读取，播放完成/出错通过 MusicEventSink
 * 回推 WebView（window.__libretvMusicEvent('ended'/'error')）。
 */
class MusicPlaybackService : Service() {

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocus: AudioFocusRequest? = null
    private var currentTitle = ""
    private var currentArtist = ""
    private var pendingSeekMs = 0
    private var shouldPlayWhenReady = true
    // MediaSession：系统媒体控制卡片（锁屏/下拉快捷区）的接入点——
    // 没有它，Android 13+ 的媒体通知不会出现在「媒体播放器」区域
    private var mediaSession: MediaSessionCompat? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return stopSelfClean()
                currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "未知曲名"
                currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                pendingSeekMs = (intent.getFloatExtra(EXTRA_POSITION, 0f) * 1000).toInt()
                shouldPlayWhenReady = true
                startForegroundCompat()
                startPlaying(url)
            }
            ACTION_RESUME -> {
                startForegroundCompat()
                resumePlaying()
            }
            ACTION_PAUSE -> {
                pausePlaying()
                stopForegroundCompat() // 暂停即撤通知；恢复时 JS 会再次 startForeground
                stopSelfSafely()
            }
            ACTION_TOGGLE -> {
                if (isPlaying()) { onStartCommand(makeIntent(ACTION_PAUSE), flags, startId) }
                else { onStartCommand(makeIntent(ACTION_RESUME), flags, startId) }
            }
            ACTION_SEEK -> {
                val sec = intent.getFloatExtra(EXTRA_POSITION, 0f)
                player?.let { p ->
                    if (p.isPlaying) p.seekTo((sec * 1000).toInt())
                    else pendingSeekMs = (sec * 1000).toInt()
                }
            }
            ACTION_STOP -> return stopSelfClean()
            else -> { /* 未知动作忽略 */ }
        }
        return START_NOT_STICKY
    }

    private fun makeIntent(action: String): Intent =
        Intent(this, MusicPlaybackService::class.java).setAction(action)

    private fun startPlaying(url: String) {
        releasePlayer()
        gainAudioFocus()
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener { mp ->
                if (pendingSeekMs > 0) mp.seekTo(pendingSeekMs)
                pendingSeekMs = 0
                if (shouldPlayWhenReady) mp.start()
                updatePlaybackState(true)
                updateNotification()
                emit("playing")
            }
            p.setOnCompletionListener {
                emit("ended")
                stopSelfClean()
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                emit("error")
                stopSelfClean()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "startPlaying failed", e)
            emit("error")
            stopSelfClean()
            return
        }
        acquireWakeLock()
    }

    private fun resumePlaying() {
        shouldPlayWhenReady = true
        gainAudioFocus()
        val p = player ?: return
        try {
            if (!p.isPlaying) p.start()
            updatePlaybackState(true)
            updateNotification()
            emit("playing")
        } catch (e: Exception) { /* 已释放等场景忽略 */ }
    }

    private fun pausePlaying() {
        shouldPlayWhenReady = false
        try { player?.takeIf { it.isPlaying }?.pause() } catch (e: Exception) { /* 忽略 */ }
        abandonAudioFocus()
        releaseWakeLock()
        updatePlaybackState(false)
        emit("paused")
    }

    private fun isPlaying(): Boolean =
        try { player?.isPlaying == true } catch (e: Exception) { false }

    private fun stopSelfClean(): Int {
        shouldPlayWhenReady = false
        emit("stopped")
        releasePlayer()
        abandonAudioFocus()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        if (!isPlaying()) {
            releasePlayer()
            stopSelf()
        }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (e: Exception) { /* 已释放 */ }
        player = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreTV:MusicPlayback").apply {
                setReferenceCounted(false)
                acquire(4L * 60 * 60 * 1000) // 4 小时兜底
            }
        } catch (e: Exception) { /* 权限缺失静默 */ }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { /* 忽略 */ }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun gainAudioFocus() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                audioFocus = req
                am.requestAudioFocus(req)
            } else {
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) { /* 忽略 */ }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocus?.let { am.abandonAudioFocusRequest(it) }
            } else {
                am.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) { /* 忽略 */ }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { onStartCommand(makeIntent(ACTION_PAUSE), 0, 0) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlaying()
            AudioManager.AUDIOFOCUS_GAIN -> if (shouldPlayWhenReady) resumePlaying()
        }
    }

    // ---- 通知 ----

    private fun startForegroundCompat() {
        val notification = buildNotification(isPlaying())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, buildNotification(isPlaying())) } catch (e: Exception) { /* 忽略 */ }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 1, makeIntent(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 2, makeIntent(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (playing) "暂停" else "继续", toggleIntent)
            .addAction(0, "停止", stopIntent)
        // MediaStyle：系统媒体控制卡片（锁屏/下拉），需要 mediaSession token
        mediaSession?.let { session ->
            builder.setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    }
                )
            }
        }
    }

    private fun updatePlaybackState(playing: Boolean) {
        val session = mediaSession ?: return
        try {
            val pos = try { player?.currentPosition?.toLong() ?: 0L } catch (e: Exception) { 0L }
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_STOP
                        or PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(
                        if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                        pos, if (playing) 1f else 0f
                    )
                    .build()
            )
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                    .build()
            )
        } catch (e: Exception) { /* 忽略 */ }
    }

    // ---- JS 事件回推 ----

    private fun emit(event: String) {
        MusicEventSink.emit?.invoke(
            "(window.__libretvMusicEvent && window.__libretvMusicEvent('$event'))"
        )
    }

    companion object {
        private const val TAG = "MusicPlayback"
        const val CHANNEL_ID = "libretv_music_playback"
        const val NOTIF_ID = 1002
        const val ACTION_PLAY = "com.libretv.app.music.PLAY"
        const val ACTION_RESUME = "com.libretv.app.music.RESUME"
        const val ACTION_PAUSE = "com.libretv.app.music.PAUSE"
        const val ACTION_TOGGLE = "com.libretv.app.music.TOGGLE"
        const val ACTION_SEEK = "com.libretv.app.music.SEEK"
        const val ACTION_STOP = "com.libretv.app.music.STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_POSITION = "position"

        // 进度/状态查询：同进程直读服务实例（PlayerBridge 调用）
        @Volatile var instance: MusicPlaybackService? = null

        fun getPositionSeconds(): Double =
            instance?.player?.let { p ->
                try { p.currentPosition.toDouble() / 1000.0 } catch (e: Exception) { 0.0 }
            } ?: 0.0

        fun getDurationSeconds(): Double =
            instance?.player?.let { p ->
                try { p.duration.toDouble() / 1000.0 } catch (e: Exception) { 0.0 }
            } ?: 0.0

        fun isPlayingNow(): Boolean = instance?.isPlaying() == true
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "LibreTVMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { onStartCommand(makeIntent(ACTION_RESUME), 0, 0) }
                override fun onPause() { onStartCommand(makeIntent(ACTION_PAUSE), 0, 0) }
                override fun onStop() { onStartCommand(makeIntent(ACTION_STOP), 0, 0) }
                override fun onSeekTo(pos: Long) {
                    onStartCommand(makeIntent(ACTION_SEEK).putExtra(EXTRA_POSITION, pos / 1000f), 0, 0)
                }
            })
            isActive = true
        }
    }

    override fun onDestroy() {
        releasePlayer()
        abandonAudioFocus()
        releaseWakeLock()
        try { mediaSession?.release() } catch (e: Exception) { /* 已释放 */ }
        mediaSession = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
