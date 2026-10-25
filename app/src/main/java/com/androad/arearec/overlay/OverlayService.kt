package com.androad.arearec.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.androad.arearec.MainActivity
import com.androad.arearec.ProjectionRequestActivity
import com.androad.arearec.R
import com.androad.arearec.RecordingsActivity
import com.androad.arearec.core.Areas
import com.androad.arearec.core.Prefs
import com.androad.arearec.core.Screen
import com.androad.arearec.record.RecordService
import com.androad.arearec.record.Session
import kotlin.math.abs

/**
 * 常驻悬浮控制条 + 区域框。
 *
 * 两个独立窗口：
 *  - control：小条，始终可点，可拖
 *  - region ：全屏，编辑时可触摸，录制态只画一圈外扩边框（画在外侧，不会被录进去）
 *
 * 位置策略（这是需求里明确要的）：控制条优先吸附到录制区域的上/下/左/右外侧，
 * 四边都放不下（区域接近全屏）时才退化成贴边迷你条。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var controlView: LinearLayout? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var regionView: RegionView? = null
    private var regionParams: WindowManager.LayoutParams? = null

    private var area = Rect()
    private var screenW = 0
    private var screenH = 0
    private var editingRegion = false
    private var timerLabel: TextView? = null

    /** 录制期间把两个窗口都摘掉了（为了避免被对方 App 检测到悬浮窗） */
    private var windowsRemoved = false

    /** 通知刷新限流：每秒最多一次，避免高频 notify 造成闪烁 */
    private var lastNotifMs = 0L

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    private val sessionListener: () -> Unit = { handler.post { refreshUi() } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        val size = Screen.realSize(this)
        screenW = size.x
        screenH = size.y
        area = Areas.load(this, screenW, screenH)
        Session.onChange(sessionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat(buildNotification())
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!canOverlay()) {
            Log.w(TAG, "no overlay permission, stop")
            stopSelf()
            return START_NOT_STICKY
        }

        val wantEdit = intent?.action == ACTION_EDIT_REGION ||
            intent?.getBooleanExtra(EXTRA_EDIT, false) == true

        if (controlView == null && !windowsRemoved) {
            showRegion(false)
            showControl()
        }
        refreshUi()

        if (wantEdit) {
            handler.postDelayed({ enterRegionEdit() }, 120)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        Session.offChange(sessionListener)
        runCatching { controlView?.let { wm.removeView(it) } }
        runCatching { regionView?.let { wm.removeView(it) } }
        controlView = null
        regionView = null
        super.onDestroy()
    }

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(this)

    // ------------------------------------------------------------------ 窗口

    private fun showControl() {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(Color.parseColor("#F2101418"))
                setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(6f).toFloat()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        try {
            wm.addView(v, p)
            controlView = v
            controlParams = p
        } catch (t: Throwable) {
            Log.e(TAG, "addView(control) failed", t)
        }
    }
    private fun showRegion(editable: Boolean) {
        if (regionView == null) {
            val rv = RegionView(this).apply {
                area = Rect(this@OverlayService.area)
                onAreaChanged = { a ->
                    this@OverlayService.area = Rect(a)
                }
                onAreaCommitted = { a ->
                    this@OverlayService.area = Rect(a)
                    Areas.save(this@OverlayService, a)
                    layoutControl()
                }
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            try {
                wm.addView(rv, p)
                regionView = rv
                regionParams = p
            } catch (t: Throwable) {
                Log.e(TAG, "addView(region) failed", t)
            }
        }
        setRegionEditable(editable)
    }

    private fun setRegionEditable(editable: Boolean) {
        val rv = regionView ?: return
        val p = regionParams ?: return
        rv.editing = editable
        p.flags = if (editable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        android.util.Log.i("OverlayService", "editable=$editable area=$area view=${rv.width}x${rv.height}")
        runCatching { wm.updateViewLayout(rv, p) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // ------------------------------------------------------------------ 录制时隐藏

    /**
     * 很多视频类 App 会检测 TYPE_APPLICATION_OVERLAY 悬浮窗，一旦发现就主动降清晰度。
     * 开关打开时，录制期间把控制条和区域框整个从 WindowManager 摘掉。
     *
     * 前提是通知权限已给 —— 否则摘了窗口用户就没法停止录制了。
     */
    private fun shouldHideForRecording(): Boolean {
        if (!Session.isActive) return false
        if (!Prefs.get(this).getBoolean(Prefs.KEY_HIDE_OVERLAY, false)) return false
        val nm = getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled()
    }

    private fun removeWindowsForRecording() {
        if (windowsRemoved) return
        windowsRemoved = true
        editingRegion = false
        runCatching { controlView?.let { wm.removeView(it) } }
        runCatching { regionView?.let { wm.removeView(it) } }
        controlView = null
        regionView = null
        controlParams = null
        regionParams = null
        timerLabel = null
    }

    // ------------------------------------------------------------------ 控制条内容

    private fun refreshUi() {
        if (shouldHideForRecording()) {
            removeWindowsForRecording()
            updateNotification()
            return
        }
        if (windowsRemoved) {
            windowsRemoved = false
            showRegion(false)
            showControl()
            refreshUi()
            return
        }

        val cv = controlView
        if (cv == null) {
            updateNotification()
            return
        }
        timerLabel = null
        cv.removeAllViews()

        cv.addView(buildDragHandle())

        if (editingRegion) {
            cv.addView(button("完成", primary = true) { exitRegionEdit(save = true) })
            cv.addView(button("全屏") {
                regionView?.setFullScreen()
            })
            cv.addView(button("取消") { exitRegionEdit(save = false) })
        } else {
            when (Session.state) {
                Session.State.IDLE -> {
                    cv.addView(button("开始", accent = true) { launchProjection() })
                    cv.addView(button("区域") { enterRegionEdit() })
                    cv.addView(button("文件") { openRecordings() })
                }

                Session.State.RECORDING -> {
                    cv.addView(timerView())
                    cv.addView(button("暂停") { RecordService.pause(this) })
                    cv.addView(button("停止", accent = true) { RecordService.stop(this) })
                    cv.addView(button("文件") { openRecordings() })
                }

                Session.State.PAUSED -> {
                    cv.addView(timerView())
                    cv.addView(button("继续", accent = true) { RecordService.resume(this) })
                    cv.addView(button("停止") { RecordService.stop(this) })
                    cv.addView(button("文件") { openRecordings() })
                }

                Session.State.STOPPING -> {
                    cv.addView(button("保存中…", enabled = false) {})
                }
            }
        }

        regionView?.area = Rect(area)
        regionView?.editing = editingRegion
        layoutControl()
        updateNotification()
    }

    private fun refreshTimer() {
        timerLabel?.text = RecordService.formatDuration(Session.elapsedMs)
    }

    private fun buildDragHandle(): View = TextView(this).apply {
        text = "⋮⋮"
        setTextColor(Color.parseColor("#88FFFFFF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        width = dp(20f)
        height = dp(36f)
        setOnTouchListener(DragListener())
    }

    private fun timerView(): TextView = TextView(this).apply {
        text = RecordService.formatDuration(Session.elapsedMs)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(8f), 0, dp(8f), 0)
        timerLabel = this
    }

    private fun button(
        label: String,
        primary: Boolean = false,
        accent: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (enabled) Color.WHITE else Color.parseColor("#66FFFFFF"))
        gravity = Gravity.CENTER
        setPadding(dp(13f), dp(8f), dp(13f), dp(8f))
        background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            when {
                accent -> setColor(Color.parseColor("#E24B4A"))
                primary -> setColor(Color.parseColor("#185FA5"))
                else -> setColor(Color.parseColor("#2AFFFFFF"))
            }
        }
        isEnabled = enabled
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------ 布局算法

    /**
     * 核心：把控制条摆到区域外面。
     * 依次试 上 → 下 → 左 → 右，第一个能完整放下的就用；都不行就贴边迷你化。
     */
    private fun layoutControl() {
        val cv = controlView ?: return
        val p = controlParams ?: return
        cv.scaleX = 1f
        cv.scaleY = 1f
        cv.alpha = 1f

        cv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cw = cv.measuredWidth
        val ch = cv.measuredHeight
        if (cw <= 0 || ch <= 0) return

        val gap = dp(8f)
        val pos = findOutsideSlot(cw, ch, gap)

        if (pos != null) {
            p.x = pos.x
            p.y = pos.y
        } else {
            // 区域几乎占满屏幕，四边都没有干净位置。
            // 必须避开两个坑：
            //   1. y=0 是状态栏 + 澎湃 OS 灵动岛，控制条会被整个盖住（就是「开了看不到」的原因）
            //   2. 底部是导航条和 Dock，压上去很难点
            // 所以放在状态栏正下方，不缩放（缩放后字太小点不准）
            cv.scaleX = 0.92f
            cv.scaleY = 0.92f
            cv.alpha = 0.94f
            p.x = ((screenW - cw) / 2).coerceAtLeast(0)
            p.y = sysDimen("status_bar_height", 30) + dp(10f)
        }
        runCatching { wm.updateViewLayout(cv, p) }
    }

    private fun sysDimen(name: String, defDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) {
            resources.getDimensionPixelSize(id)
        } else {
            dp(defDp.toFloat())
        }
    }

    private fun findOutsideSlot(cw: Int, ch: Int, gap: Int): Point? {
        val cx = area.centerX() - cw / 2
        val cy = area.centerY() - ch / 2
        val candidates = listOf(
            Point(cx, area.top - ch - gap),      // 上
            Point(cx, area.bottom + gap),        // 下
            Point(area.left - cw - gap, cy),     // 左
            Point(area.right + gap, cy)          // 右
        )
        for (c in candidates) {
            if (c.x >= 0 && c.y >= 0 && c.x + cw <= screenW && c.y + ch <= screenH) return c
        }
        return null
    }

    /** 拖动控制条本体（只认左侧的拖把，避免和按钮点击打架） */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var originX = 0
        private var originY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = controlParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    originX = p.x
                    originY = p.y
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > dp(6f) || abs(dy) > dp(6f))) dragging = true
                    if (dragging) {
                        p.x = (originX + dx).toInt().coerceIn(0, (screenW - v.width).coerceAtLeast(0))
                        p.y = (originY + dy).toInt().coerceIn(0, (screenH - v.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(controlView, p) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> return dragging
            }
            return false
        }
    }

    // ------------------------------------------------------------------ 动作

    private fun enterRegionEdit() {
        editingRegion = true
        regionView?.area = Rect(area)
        setRegionEditable(true)
        refreshUi()
    }

    private fun exitRegionEdit(save: Boolean) {
        editingRegion = false
        setRegionEditable(false)
        if (save) {
            area = Areas.clamp(area, screenW, screenH)
            Areas.save(this, area)
        } else {
            area = Areas.load(this, screenW, screenH)
        }
        regionView?.area = Rect(area)
        regionView?.editing = false
        refreshUi()
    }

    private fun launchProjection() {
        val i = Intent(this, ProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ProjectionRequestActivity.EXTRA_AREA_L, area.left)
            putExtra(ProjectionRequestActivity.EXTRA_AREA_T, area.top)
            putExtra(ProjectionRequestActivity.EXTRA_AREA_R, area.right)
            putExtra(ProjectionRequestActivity.EXTRA_AREA_B, area.bottom)
        }
        runCatching { startActivity(i) }
            .onFailure { toast("无法打开授权页：${it.message}") }
    }

    private fun openRecordings() {
        val i = Intent(this, RecordingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
    }

    private fun toast(msg: String) {
        handler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ 前台通知

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // 清掉历史渠道。importance 一旦创建就改不了，只能换新 id。
        runCatching { nm.deleteNotificationChannel("arearec_overlay") }
        runCatching { nm.deleteNotificationChannel("arearec_overlay_v2") }

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                // 必须 DEFAULT：LOW 会被澎湃 OS 折叠进「更多通知」，
                // 那里面按钮根本点不到，等于白做
                NotificationChannel(CHANNEL_ID, "录制控制", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        description = "常驻控制条：开始 / 暂停 / 停止 / 打开录制文件"
                    }
            )
        }
    }

    /**
     * 常驻通知就是主要控制面板。
     *
     * 为什么用 RemoteViews 而不是系统的 addAction：
     * 澎湃 OS 会把 action 收进展开态，折叠时只看得到两行字 —— 那等于没有按钮。
     * 自己画在 contentView 里，折叠态就能直接点。
     *
     * 三态各自给满三个按钮：
     *   空闲 [开始][区域][文件]
     *   录制 [暂停][停止][文件]
     *   暂停 [继续][停止][文件]
     *
     * 录制期间另有 RecordService 的「正在录制」通知，这里降级成纯状态，避免重复。
     */
    private fun buildNotification(): Notification {
        createChannel()
        val area = Areas.load(this, screenW, screenH)
        val state = Session.state
        val active = Session.isActive

        var title = ""
        var l1: String? = null
        var i1: PendingIntent? = null
        var l2: String? = null
        var i2: PendingIntent? = null
        var l3: String? = null
        var i3: PendingIntent? = null

        when (state) {
            Session.State.RECORDING -> {
                title = "正在录制 ${RecordService.formatDuration(Session.elapsedMs)}"
            }

            Session.State.PAUSED -> {
                title = "已暂停 ${RecordService.formatDuration(Session.elapsedMs)}"
            }

            Session.State.STOPPING -> {
                title = "正在保存…"
            }

            else -> {
                title = "区域录制 · ${area.width()}×${area.height()}"
                l1 = "开始"
                i1 = activityIntent(ProjectionRequestActivity::class.java, 11)
                l2 = "区域"
                i2 = serviceIntent(OverlayService::class.java, ACTION_EDIT_REGION, 12)
                l3 = "文件"
                i3 = activityIntent(RecordingsActivity::class.java, 13)
            }
        }

        val rv = RemoteViews(packageName, R.layout.notif_control)
        rv.setTextViewText(R.id.n_title, title)

        if (l1 != null && i1 != null && l2 != null && i2 != null && l3 != null && i3 != null) {
            rv.setTextViewText(R.id.n_btn1, l1)
            rv.setOnClickPendingIntent(R.id.n_btn1, i1)
            rv.setTextViewText(R.id.n_btn2, l2)
            rv.setOnClickPendingIntent(R.id.n_btn2, i2)
            rv.setTextViewText(R.id.n_btn3, l3)
            rv.setOnClickPendingIntent(R.id.n_btn3, i3)
            rv.setViewVisibility(R.id.n_actions, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.n_actions, View.GONE)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(activityIntent(MainActivity::class.java, 10))
            .build()
    }

    private fun activityIntent(cls: Class<*>, code: Int): PendingIntent =
        PendingIntent.getActivity(
            this, code,
            Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun serviceIntent(cls: Class<*>, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, cls).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(n: Notification) {
        // specialUse 这个类型值是 API 34 才有的，低版本传进去会被当成非法类型
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        // 换过 id：通知渠道的 importance 一旦创建就改不了，
        // 旧渠道是 MIN/LOW，会被系统折叠到最底部连 action 都不显示
        private const val CHANNEL_ID = "arearec_overlay_v3"
        private const val GROUP_KEY = "arearec"
        private const val NOTIF_ID = 1002
        private const val EXTRA_EDIT = "edit_region"
        const val ACTION_EDIT_REGION = "com.androad.arearec.EDIT_REGION"

        @Volatile
        private var instance: OverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context, editRegion: Boolean = false) {
            val i = Intent(ctx, OverlayService::class.java)
                .putExtra(EXTRA_EDIT, editRegion)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        fun notifyStateChanged() {
            instance?.let { s -> s.handler.post { s.refreshUi() } }
        }

        fun notifyTick() {
            instance?.let { s ->
                s.handler.post {
                    s.refreshTimer()
                    val now = SystemClock.elapsedRealtime()
                    if (now - s.lastNotifMs >= 1000) {
                        s.lastNotifMs = now
                        s.updateNotification()
                    }
                }
            }
        }

        /** 区域变了（比如从主页改的），同步过去 */
        fun reloadArea(ctx: Context) {
            val s = instance ?: return
            s.handler.post {
                val size = Screen.realSize(s)
                s.screenW = size.x
                s.screenH = size.y
                s.area = Areas.load(ctx, s.screenW, s.screenH)
                s.regionView?.area = Rect(s.area)
                s.layoutControl()
            }
        }
    }
}
