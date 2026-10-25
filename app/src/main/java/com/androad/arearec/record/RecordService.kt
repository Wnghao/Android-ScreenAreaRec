package com.androad.arearec.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.androad.arearec.MainActivity
import com.androad.arearec.R
import com.androad.arearec.core.AudioMode
import com.androad.arearec.core.Prefs
import com.androad.arearec.core.Quality
import com.androad.arearec.core.Screen
import com.androad.arearec.overlay.OverlayService

/**
 * 录制前台服务。
 *
 * Android 14 的硬性时序：必须先 startForeground()（且类型为 mediaProjection），
 * 再调 getMediaProjection()，顺序反了直接 SecurityException。
 */
class RecordService : Service() {

    private var recorder: Recorder? = null
    private var projection: MediaProjection? = null
    private var screenW = 0
    private var screenH = 0
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> {
                recorder?.pause()
                Session.set(Session.State.PAUSED)
                updateNotification()
                OverlayService.notifyStateChanged()
            }

            ACTION_RESUME -> {
                recorder?.resume()
                Session.set(Session.State.RECORDING)
                updateNotification()
                OverlayService.notifyStateChanged()
            }

            ACTION_STOP -> {
                stopRecording(userInitiated = true)
            }

            ACTION_QUIT -> {
                stopRecording(userInitiated = true)
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 启动

    private fun handleStart(intent: Intent) {
        if (recorder != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            Log.e(TAG, "missing projection data")
            stopSelf()
            return
        }

        Session.lastError = null
        // 关键：先上前台
        startForegroundCompat(buildNotification())

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mgr.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            Session.lastError = "录屏授权已失效，请重新开始"
            sessionFail()
            return
        }
        if (proj == null) {
            Session.lastError = "录屏授权已失效，请重新开始"
            sessionFail()
            return
        }
        projection = proj
        proj.registerCallback(projectionCallback, handler)

        val size = Screen.realSize(this)
        screenW = size.x
        screenH = size.y
        val dpi = Screen.densityDpi(this)

        val prefs = Prefs.get(this)
        val area = Rect(
            intent.getIntExtra(EXTRA_AREA_L, 0),
            intent.getIntExtra(EXTRA_AREA_T, 0),
            intent.getIntExtra(EXTRA_AREA_R, screenW),
            intent.getIntExtra(EXTRA_AREA_B, screenH)
        )
        val audioMode = runCatching {
            AudioMode.valueOf(
                intent.getStringExtra(EXTRA_AUDIO)
                    ?: prefs.getString(Prefs.KEY_AUDIO_MODE, AudioMode.NONE.name)!!
            )
        }.getOrDefault(AudioMode.NONE)
        val fps = intent.getIntExtra(EXTRA_FPS, Screen.currentFps(this, prefs))
        val quality = runCatching {
            Quality.valueOf(
                intent.getStringExtra(EXTRA_QUALITY)
                    ?: prefs.getString(Prefs.KEY_QUALITY, Quality.HD.name)!!
            )
        }.getOrDefault(Quality.HD)

        val rec = Recorder(
            ctx = this,
            projection = proj,
            areaIn = area,
            screenW = screenW,
            screenH = screenH,
            dpi = dpi,
            requestedAudio = audioMode,
            fps = fps,
            quality = quality,
            onFatal = { t ->
                Session.lastError = t.message ?: "录制初始化失败"
                handler.post { stopRecording(userInitiated = false) }
            }
        )
        recorder = rec

        if (!rec.start()) {
            Session.lastError = Session.lastError ?: "录制启动失败"
            sessionFail()
            return
        }

        Session.set(Session.State.RECORDING)
        startTicker()
        registerDisplayWatcher()
        updateNotification()
        OverlayService.notifyStateChanged()

        if (rec.effectiveAudioMode != audioMode && audioMode != AudioMode.NONE) {
            Session.lastError = "音频源降级为：${rec.effectiveAudioMode.label}"
        }
    }

    private fun sessionFail() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        Session.set(Session.State.IDLE)
        OverlayService.notifyStateChanged()
        stopSelf()
    }

    // ------------------------------------------------------------------ 停止

    private fun stopRecording(userInitiated: Boolean) {
        if (stopping) return
        stopping = true

        stopTicker()
        unregisterDisplayWatcher()
        Session.set(Session.State.STOPPING)
        OverlayService.notifyStateChanged()

        val rec = recorder
        recorder = null

        // 停录制可能涉及 muxer 收尾，放到后台线程，别卡主线程
        Thread({
            val uri = runCatching { rec?.stop() }.getOrNull()
            handler.post {
                Session.lastUri = uri
                if (uri != null) {
                    android.widget.Toast.makeText(
                        this, "已保存到 Movies/AreaRec", android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else if (userInitiated) {
                    Session.lastError = "录制失败，文件未保存"
                }
                runCatching { projection?.unregisterCallback(projectionCallback) }
                runCatching { projection?.stop() }
                projection = null
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                Session.set(Session.State.IDLE)
                OverlayService.notifyStateChanged()
                stopping = false
                stopSelf()
            }
        }, "recorder-stop").start()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "projection stopped by system")
            handler.post {
                if (!stopping) {
                    Session.lastError = "系统停止了屏幕共享"
                    stopRecording(userInitiated = false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 旋转检测

    /**
     * 屏幕方向一变，VirtualDisplay 的尺寸就和真实屏幕对不上了，画面会错位。
     * 与其录出一段歪的，不如直接停掉并保存已录内容。
     */
    private fun registerDisplayWatcher() {
        val dm = getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val now = Screen.realSize(this@RecordService)
                if (now.x != screenW || now.y != screenH) {
                    Log.w(TAG, "screen size changed $screenW x $screenH -> ${now.x} x ${now.y}")
                    Session.lastError = "屏幕方向变了，已自动结束录制"
                    handler.post { stopRecording(userInitiated = false) }
                }
            }
        }
        dm.registerDisplayListener(listener, handler)
        displayListener = listener
    }

    private fun unregisterDisplayWatcher() {
        displayListener?.let {
            runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        }
        displayListener = null
    }

    private fun startTicker() {
        val r = object : Runnable {
            override fun run() {
                recorder?.let {
                    Session.elapsedMs = it.elapsedMs()
                    updateNotification()
                    OverlayService.notifyTick()
                }
                if (Session.isActive) handler.postDelayed(this, 500)
            }
        }
        ticker = r
        handler.postDelayed(r, 500)
    }

    private fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel("arearec_record") }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                // DEFAULT 才会直接出现在通知列表；LOW 会被折叠进「更多通知」
                NotificationChannel(CHANNEL_ID, "录制状态", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        }
    }

    private fun buildNotification(): Notification {
        val active = Session.isActive
        val title = when (Session.state) {
            Session.State.PAUSED -> "已暂停 · ${formatDuration(Session.elapsedMs)}"
            Session.State.RECORDING -> "正在录制 · ${formatDuration(Session.elapsedMs)}"
            else -> "准备录制"
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText("区域录制")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 和常驻控制条的通知归到一组，避免通知栏刷两条
            .setGroup("arearec")
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        if (active) {
            if (Session.state == Session.State.RECORDING) {
                builder.addAction(
                    Notification.Action.Builder(
                        null, "暂停",
                        servicePendingIntent(ACTION_PAUSE, 1)
                    ).build()
                )
            } else {
                builder.addAction(
                    Notification.Action.Builder(
                        null, "继续",
                        servicePendingIntent(ACTION_RESUME, 1)
                    ).build()
                )
            }
            builder.addAction(
                Notification.Action.Builder(
                    null, "停止",
                    servicePendingIntent(ACTION_STOP, 2)
                ).build()
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, RecordService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updateNotification() {
        if (!Session.isActive && Session.state != Session.State.STOPPING) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        instance = null
        stopTicker()
        runCatching { unregisterDisplayWatcher() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordService"
        private const val CHANNEL_ID = "arearec_record_v2"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.androad.arearec.START"
        const val ACTION_PAUSE = "com.androad.arearec.PAUSE"
        const val ACTION_RESUME = "com.androad.arearec.RESUME"
        const val ACTION_STOP = "com.androad.arearec.STOP"
        const val ACTION_QUIT = "com.androad.arearec.QUIT"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AREA_L = "area_l"
        const val EXTRA_AREA_T = "area_t"
        const val EXTRA_AREA_R = "area_r"
        const val EXTRA_AREA_B = "area_b"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"

        @Volatile
        private var instance: RecordService? = null

        fun isRunning(): Boolean = instance != null

        fun pause(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
        }

        /** 从 Activity 拿到授权后启动录制 */
        fun start(
            ctx: Context,
            resultCode: Int,
            data: Intent,
            area: Rect,
            audioMode: AudioMode,
            fps: Int,
            quality: Quality
        ) {
            val i = Intent(ctx, RecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_AREA_L, area.left)
                putExtra(EXTRA_AREA_T, area.top)
                putExtra(EXTRA_AREA_R, area.right)
                putExtra(EXTRA_AREA_B, area.bottom)
                putExtra(EXTRA_AUDIO, audioMode.name)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_QUALITY, quality.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun formatDuration(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%02d:%02d", m, s)
            }
        }
    }
}
