package com.androad.arearec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.androad.arearec.core.AudioMode
import com.androad.arearec.core.Areas
import com.androad.arearec.core.Prefs
import com.androad.arearec.core.Quality
import com.androad.arearec.core.Screen
import com.androad.arearec.overlay.OverlayService
import com.androad.arearec.record.RecordService
import com.androad.arearec.record.Session
import java.util.Locale

class MainActivity : Activity() {

    private var screenW = 0
    private var screenH = 0
    private var pendingStartAfterPermission = false

    private var rootScroll: ScrollView? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private val colorBg = Color.parseColor("#F2F2F7")
    private val colorCard = Color.WHITE
    private val colorPrimary = Color.parseColor("#185FA5")
    private val colorText = Color.parseColor("#1C1C1E")
    private val colorSub = Color.parseColor("#6E6E73")
    private val colorDanger = Color.parseColor("#E24B4A")
    private val colorOk = Color.parseColor("#0F6E56")
    private val colorWarn = Color.parseColor("#B35A00")

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = Screen.realSize(this)
        screenW = size.x
        screenH = size.y
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    // ------------------------------------------------------------------ UI

    private fun rebuild() {
        val savedY = rootScroll?.scrollY ?: 0
        val scroll = ScrollView(this).apply { setBackgroundColor(colorBg) }
        rootScroll = scroll
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(20f), dp(16f), dp(28f))
        }
        scroll.addView(root)

        root.addView(header())
        root.addView(statusCard())
        root.addView(areaCard())
        root.addView(audioCard())
        root.addView(qualityCard())
        root.addView(fpsCard())
        root.addView(overlayCard())
        root.addView(noteCard())

        setContentView(scroll)
        if (savedY > 0) {
            scroll.post { scroll.scrollTo(0, savedY) }
        }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4f), 0, dp(4f), dp(16f))
        addView(text("区域录制", 24f, colorText, bold = true))
        addView(text("只录屏幕上框出来的那一块，悬浮条控制开始/暂停/停止", 13f, colorSub).apply {
            setPadding(0, dp(6f), 0, 0)
        })
    }

    private fun statusCard(): View {
        val card = card()
        val state = Session.state
        val (label, color) = when (state) {
            Session.State.IDLE -> "未在录制" to colorSub
            Session.State.RECORDING -> "正在录制 · ${RecordService.formatDuration(Session.elapsedMs)}" to colorDanger
            Session.State.PAUSED -> "已暂停 · ${RecordService.formatDuration(Session.elapsedMs)}" to colorWarn
            Session.State.STOPPING -> "正在保存…" to colorSub
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(dot(color))
        row.addView(text(label, 15f, colorText, bold = true).apply {
            setPadding(dp(10f), 0, 0, 0)
        })
        card.addView(row)

        Session.lastError?.let {
            card.addView(text(it, 12.5f, colorWarn).apply { setPadding(0, dp(8f), 0, 0) })
        }

        Session.lastUri?.let { uri ->
            card.addView(text("最近保存：${lastFileName(uri)}", 12.5f, colorOk).apply {
                setPadding(0, dp(8f), 0, 0)
            })
        }

        if (state == Session.State.IDLE) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14f), 0, 0)
            }
            btnRow.addView(primaryButton("开始录制") { startRecording() }, weight())
            btnRow.addView(space(dp(10f)))
            btnRow.addView(ghostButton("录制文件") { openRecordings() }, weight())
            card.addView(btnRow)
        } else {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14f), 0, 0)
            }
            if (state == Session.State.RECORDING) {
                btnRow.addView(ghostButton("暂停") { RecordService.pause(this@MainActivity) }, weight())
            } else if (state == Session.State.PAUSED) {
                btnRow.addView(primaryButton("继续") { RecordService.resume(this@MainActivity) }, weight())
            }
            btnRow.addView(space(dp(10f)))
            btnRow.addView(dangerButton("停止") { RecordService.stop(this@MainActivity) }, weight())
            card.addView(btnRow)
        }
        return card
    }

    private fun areaCard(): View {
        val card = card()
        card.addView(cardTitle("录制区域"))
        val area = Areas.load(this, screenW, screenH)
        card.addView(
            text(
                "${area.width()} × ${area.height()}   左上角 (${area.left}, ${area.top})",
                13.5f, colorSub
            ).apply { setPadding(0, dp(4f), 0, 0) }
        )

        if (!Settings.canDrawOverlays(this)) {
            card.addView(text("需要「显示在其他应用上层」权限才能框选", 12.5f, colorWarn).apply {
                setPadding(0, dp(8f), 0, 0)
            })
            card.addView(primaryButton("去授权悬浮窗") { requestOverlay() }.apply {
                setPadding(0, dp(12f), 0, 0)
            })
            return card
        }

        if (area.width() >= screenW - 24 && area.height() >= screenH - 24) {
            card.addView(text(
                "已接近全屏：悬浮条会出现在录制画面里（四边没有地方可躲）",
                11.5f, colorWarn
            ).apply { setPadding(0, dp(6f), 0, 0) })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14f), 0, 0)
        }
        row.addView(primaryButton("框选区域") {
            OverlayService.start(this, editRegion = true)
            Toast.makeText(this, "在悬浮框上拖动调整，点「完成」保存", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }, weight())
        row.addView(space(dp(10f)))
        row.addView(ghostButton("全屏") {
            Areas.save(this, Rect(0, 0, screenW, screenH))
            OverlayService.reloadArea(this)
            rebuild()
        }, weight())
        row.addView(space(dp(10f)))
        row.addView(ghostButton("默认") {
            Areas.save(this, Areas.default(screenW, screenH))
            OverlayService.reloadArea(this)
            rebuild()
        }, weight())
        card.addView(row)
        return card
    }

    private fun audioCard(): View {
        val card = card()
        card.addView(cardTitle("声音"))
        val prefs = Prefs.get(this)
        val current = runCatching {
            AudioMode.valueOf(prefs.getString(Prefs.KEY_AUDIO_MODE, AudioMode.NONE.name)!!)
        }.getOrDefault(AudioMode.NONE)

        val hints = mapOf(
            AudioMode.NONE to "不录声音",
            AudioMode.INTERNAL to "录 App 播放的声音，系统提示音录不到",
            AudioMode.MIC to "录麦克风，适合边操作边讲解",
            AudioMode.MIX to "两路混在一起，注意可能音量偏大"
        )

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        AudioMode.entries.forEach { mode ->
            val selected = mode == current
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (selected) Color.parseColor("#E6F1FB") else Color.parseColor("#F7F7FA"))
                    if (selected) setStroke(dp(1.2f), colorPrimary)
                }
                isClickable = true
                setOnClickListener {
                    prefs.edit().putString(Prefs.KEY_AUDIO_MODE, mode.name).apply()
                    if (mode.needsMicPermission && !hasMic()) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    }
                    rebuild()
                }
            }
            item.addView(text(mode.label, 13.5f, if (selected) colorPrimary else colorText, bold = selected))
            item.addView(text(hints[mode] ?: "", 11.5f, colorSub).apply {
                setPadding(0, dp(3f), 0, 0)
            })
            wrap.addView(item)
            wrap.addView(space(dp(8f)))
        }
        card.addView(wrap)
        return card
    }

    private fun qualityCard(): View {
        val card = card()
        card.addView(cardTitle("画质"))
        val prefs = Prefs.get(this)
        val cur = runCatching {
            Quality.valueOf(prefs.getString(Prefs.KEY_QUALITY, Quality.HD.name)!!)
        }.getOrDefault(Quality.HD)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12f), 0, 0)
        }
        val all = Quality.entries
        all.forEachIndexed { i, q ->
            val sel = q == cur
            val tv = text(q.label, 13f, if (sel) Color.WHITE else colorText, bold = sel)
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, dp(10f), 0, dp(10f))
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(if (sel) colorPrimary else Color.parseColor("#F0F0F4"))
            }
            tv.isClickable = true
            tv.setOnClickListener {
                prefs.edit().putString(Prefs.KEY_QUALITY, q.name).apply()
                rebuild()
            }
            row.addView(tv, weight())
            if (i != all.lastIndex) row.addView(space(dp(8f)))
        }
        card.addView(row)

        val area = Areas.load(this, screenW, screenH)
        val fps = Screen.currentFps(this, prefs)
        val mbps = cur.estimateMbps(
            Areas.evenSize(area.width()), Areas.evenSize(area.height()), fps
        )
        card.addView(
            text(
                "${cur.hint}　·　当前约 ${String.format(Locale.US, "%.1f", mbps)} Mbps",
                11.5f, colorSub
            ).apply { setPadding(0, dp(8f), 0, 0) }
        )
        card.addView(text(
            "码率不够是「录出来糊」最常见的原因，录视频内容建议高清起步",
            11.5f, colorSub
        ).apply { setPadding(0, dp(4f), 0, 0) })
        return card
    }

    private fun fpsCard(): View {
        val card = card()
        card.addView(cardTitle("帧率"))

        val prefs = Prefs.get(this)
        val refresh = Screen.refreshRateBucket(Screen.nominalRefreshRate(this))
        val candidates = Screen.fpsCandidates(this)
        val rec = Screen.recommendedFps(this)
        val cur = Screen.currentFps(this, prefs)

        card.addView(text("屏幕 ${refresh}Hz", 12f, colorSub).apply {
            setPadding(0, dp(4f), 0, 0)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12f), 0, 0)
        }
        candidates.forEachIndexed { i, f ->
            val sel = f == cur
            val tv = text("$f", 15f, if (sel) Color.WHITE else colorText, bold = sel)
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, dp(10f), 0, dp(10f))
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                if (sel) {
                    setColor(colorPrimary)
                } else {
                    setColor(Color.parseColor("#F0F0F4"))
                    if (f == rec) setStroke(dp(1.2f), colorPrimary)
                }
            }
            tv.isClickable = true
            tv.setOnClickListener {
                prefs.edit().putInt(Prefs.KEY_FPS, f).apply()
                rebuild()
            }
            row.addView(tv, weight())
            if (i != candidates.lastIndex) row.addView(space(dp(8f)))
        }
        card.addView(row)

        val hint = when {
            cur <= 30 -> "够用且省电，绝大多数录屏场景的最佳选择"
            cur == 60 -> "动作更顺滑，文件体积约为 30fps 的两倍"
            else -> "只对 120fps 的游戏有意义，文件约 4 倍、发热明显"
        }
        card.addView(text("$cur fps · $hint", 11.5f, colorSub).apply {
            setPadding(0, dp(8f), 0, 0)
        })
        if (cur != rec) {
            card.addView(
                text(
                    "按屏幕 ${refresh}Hz 推荐 $rec fps（带蓝框那个）",
                    11.5f, colorPrimary
                ).apply { setPadding(0, dp(4f), 0, 0) }
            )
        }
        return card
    }

    private fun overlayCard(): View {
        val card = card()
        card.addView(cardTitle("悬浮控制条"))
        val running = OverlayService.isRunning()
        card.addView(
            text(
                if (running) "已开启，切到别的 App 也能点开始/暂停/停止" else "未开启",
                12.5f, if (running) colorOk else colorSub
            ).apply { setPadding(0, dp(4f), 0, 0) }
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12f), 0, 0)
        }
        if (running) {
            row.addView(ghostButton("收起悬浮条") {
                OverlayService.stop(this)
                ui.postDelayed({ rebuild() }, 400)
            }, weight())
        } else {
            row.addView(primaryButton("开启悬浮条") {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlay()
                } else {
                    OverlayService.start(this)
                    Toast.makeText(this, "已开启", Toast.LENGTH_SHORT).show()
                    // startForegroundService 是异步的，立刻 rebuild 时 Service 还没 onCreate，
                    // isRunning() 还是 false —— 界面会一直显示「未开启」，像是没启动成功
                    ui.postDelayed({ rebuild() }, 400)
                }
            }, weight())
        }
        if (!hasNotifications()) {
            row.addView(space(dp(10f)))
            row.addView(ghostButton("通知权限") {
                openNotificationSettings()
            }, weight())
        }
        card.addView(row)

        val prefs = Prefs.get(this)
        val hide = prefs.getBoolean(Prefs.KEY_HIDE_OVERLAY, false)
        val hideRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16f), 0, 0)
            isClickable = true
            setOnClickListener {
                prefs.edit().putBoolean(Prefs.KEY_HIDE_OVERLAY, !hide).apply()
                rebuild()
            }
        }
        hideRow.addView(text("录制时隐藏悬浮条", 13f, colorText), weight())
        hideRow.addView(
            text(if (hide) "已开启" else "已关闭", 12.5f, if (hide) colorOk else colorSub)
        )
        card.addView(hideRow)
        card.addView(text(
            "录视频类 App 时打开。悬浮窗会被对方检测到，进而主动降清晰度；" +
                "开启后靠控制中心磁贴或通知控制暂停/停止",
            11.5f, colorSub
        ).apply { setPadding(0, dp(6f), 0, 0); setLineSpacing(dp(3f).toFloat(), 1f) })

        card.addView(text(
            "控制中心磁贴：下拉控制中心 → 编辑 → 把「区域录制」拖进来。" +
                "系统会把常驻通知折叠进「更多通知」，磁贴才是真正一键可达的入口",
            11.5f, colorWarn
        ).apply { setPadding(0, dp(10f), 0, 0); setLineSpacing(dp(3f).toFloat(), 1f) })
        return card
    }

    private fun noteCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(Color.parseColor("#FFF7E6"))
        }
        addView(text("几个绕不过去的限制", 12.5f, colorWarn, bold = true))
        addView(text(
            "· 每次开始录制，系统都会弹一次授权框（Android 14 起无法记住）\n" +
            "· 授权时请选「整个屏幕」，选单个应用会导致只录到那个 App\n" +
            "· 内录只对媒体音频生效，通知音、来电铃声录不到\n" +
            "· 银行类 App 和 DRM 视频是黑屏，这是系统防录屏\n" +
            "· 录制中旋转屏幕会自动结束并保存，避免画面错位",
            12f, colorSub
        ).apply { setPadding(0, dp(8f), 0, 0); setLineSpacing(dp(4f).toFloat(), 1f) })
    }

    // ------------------------------------------------------------------ 动作

    private fun startRecording() {
        val prefs = Prefs.get(this)
        val mode = runCatching {
            AudioMode.valueOf(prefs.getString(Prefs.KEY_AUDIO_MODE, AudioMode.NONE.name)!!)
        }.getOrDefault(AudioMode.NONE)

        if (mode.needsMicPermission && !hasMic()) {
            pendingStartAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        if (!OverlayService.isRunning()) {
            runCatching { OverlayService.start(this) }
        }

        val area = Areas.load(this, screenW, screenH)
        startActivity(Intent(this, ProjectionRequestActivity::class.java).apply {
            putExtra(ProjectionRequestActivity.EXTRA_AREA_L, area.left)
            putExtra(ProjectionRequestActivity.EXTRA_AREA_T, area.top)
            putExtra(ProjectionRequestActivity.EXTRA_AREA_R, area.right)
            putExtra(ProjectionRequestActivity.EXTRA_AREA_B, area.bottom)
        })
    }

    private fun openRecordings() {
        startActivity(Intent(this, RecordingsActivity::class.java))
    }

    private fun requestOverlay() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        }.onFailure { requestNotificationPermission() }
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return getSystemService(android.app.NotificationManager::class.java)
                .areNotificationsEnabled()
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "没有麦克风权限，已改为不录声音", Toast.LENGTH_SHORT).show()
                Prefs.get(this).edit().putString(Prefs.KEY_AUDIO_MODE, AudioMode.NONE.name).apply()
                rebuild()
            }
        }
    }

    private fun lastFileName(uri: Uri): String {
        val name = uri.lastPathSegment ?: return "已保存"
        return name.substringAfterLast('/')
    }

    // ------------------------------------------------------------------ 小工具

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(colorCard)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12f) }
    }

    private fun cardTitle(t: String): TextView = text(t, 14f, colorText, bold = true)

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = MEDIUM
            includeFontPadding = false
        }

    private fun dot(color: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(9f), dp(9f))
    }

    private fun space(w: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun baseButton(label: String, bg: Int, fg: Int, stroke: Int? = null): TextView {
        val tv = text(label, 14f, fg, bold = true)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(12f), 0, dp(12f))
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(11f).toFloat()
            setColor(bg)
            stroke?.let { setStroke(dp(1.2f), it) }
        }
        tv.isClickable = true
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return tv
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        baseButton(label, colorPrimary, Color.WHITE).apply { setOnClickListener { onClick() } }

    private fun ghostButton(label: String, onClick: () -> Unit): TextView =
        baseButton(label, Color.parseColor("#F0F0F4"), colorText).apply {
            setOnClickListener { onClick() }
        }

    private fun dangerButton(label: String, onClick: () -> Unit): TextView =
        baseButton(label, Color.parseColor("#FCEBEB"), colorDanger, colorDanger).apply {
            setOnClickListener { onClick() }
        }

    private companion object {
        const val REQ_MIC = 3001
        const val REQ_NOTIF = 3002
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
