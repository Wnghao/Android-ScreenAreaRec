package com.androad.arearec.core

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * 录制区域模型 + 屏幕尺寸工具。
 *
 * 一切都用「物理像素」说话：MediaProjection 的 VirtualDisplay 以物理像素为准，
 * 用 dp 或者去掉系统栏的尺寸都会导致画面被缩放。
 */
enum class AudioMode {
    NONE, INTERNAL, MIC, MIX;

    val label: String
        get() = when (this) {
            NONE -> "不录声音"
            INTERNAL -> "内录（App 播放的声音）"
            MIC -> "麦克风"
            MIX -> "内录 + 麦克风混音"
        }

    val needsMicPermission: Boolean
        get() = this == MIC || this == MIX

    val needsProjectionAudio: Boolean
        get() = this == INTERNAL || this == MIX
}

object Screen {

    /** 真实屏幕像素（含状态栏/导航栏），VirtualDisplay 必须用这个尺寸 */
    fun realSize(ctx: Context): Point {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Point(dm.widthPixels, dm.heightPixels)
    }

    fun densityDpi(ctx: Context): Int {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.densityDpi
    }

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------------ 刷新率

    /**
     * 屏幕当前显示模式的**标称**刷新率。
     *
     * 刻意不用 Display.getRefreshRate() —— 那返回的是实时 vsync 频率，
     * LTPO 屏（比如这台 120Hz 的）在画面静止时会掉到 10Hz 甚至 1Hz，
     * 拿它做判断会得到荒谬的结果。display.mode.refreshRate 是模式标称值，稳定。
     */
    fun nominalRefreshRate(ctx: Context): Float {
        val display = runCatching {
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: return 60f
        return runCatching { display.mode.refreshRate }.getOrDefault(60f)
    }

    /** 把 119.99997 / 59.940056 这种值归到常见档位 */
    fun refreshRateBucket(r: Float): Int = when {
        r >= 138f -> 144
        r >= 110f -> 120
        r >= 85f -> 90
        r >= 58f -> 60
        r >= 48f -> 50
        else -> 30
    }

    /**
     * 可选帧率。
     *
     * 注意：这套录制实现是「定时器恒定节拍重绘」，不是 vsync 驱动，
     * 所以不存在「帧率必须整除刷新率否则抖动」的问题 —— 每拍取一次最新合成帧即可。
     * 真正要守的只有两条：别超过屏幕能力；高刷屏才值得上 60+。
     */
    fun fpsCandidates(ctx: Context): List<Int> {
        val r = refreshRateBucket(nominalRefreshRate(ctx))
        return when {
            r >= 120 -> listOf(30, 60, 120)
            r >= 60 -> listOf(30, 60)
            else -> listOf(30)
        }
    }

    /** 首次启动的默认帧率：高刷屏给 60，普通屏给 30 */
    fun recommendedFps(ctx: Context): Int {
        val r = refreshRateBucket(nominalRefreshRate(ctx))
        return if (r >= 90) 60 else 30
    }

    /** 读用户的选择，失效（超出当前屏幕能力）时回落到推荐值 */
    fun currentFps(ctx: Context, prefs: android.content.SharedPreferences): Int {
        val rec = recommendedFps(ctx)
        val v = prefs.getInt(Prefs.KEY_FPS, rec)
        return if (v in fpsCandidates(ctx)) v else rec
    }
}

object Areas {

    /** 默认区域：屏幕正中，占 60% 宽 / 40% 高 */
    fun default(screenW: Int, screenH: Int): Rect {
        val w = (screenW * 0.6f).toInt()
        val h = (screenH * 0.4f).toInt()
        val x = (screenW - w) / 2
        val y = (screenH - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    /**
     * 编码安全化：H.264 要求宽高为偶数。
     * 向下取偶（最多丢 1px），同时保证不小于 2。
     */
    fun evenSize(v: Int): Int = (v and 1.inv()).coerceAtLeast(2)

    /** 把区域夹回屏幕内，并保证最小尺寸 */
    fun clamp(area: Rect, screenW: Int, screenH: Int, minSize: Int = 64): Rect {
        val r = Rect(area)
        r.left = r.left.coerceIn(0, (screenW - minSize).coerceAtLeast(0))
        r.top = r.top.coerceIn(0, (screenH - minSize).coerceAtLeast(0))
        r.right = r.right.coerceIn(r.left + minSize, screenW)
        r.bottom = r.bottom.coerceIn(r.top + minSize, screenH)
        return r
    }

    fun load(ctx: Context, screenW: Int, screenH: Int): Rect {
        val p = Prefs.get(ctx)
        val w = p.getInt(Prefs.KEY_AREA_W, 0)
        val h = p.getInt(Prefs.KEY_AREA_H, 0)
        if (w <= 0 || h <= 0) return default(screenW, screenH)
        val r = Rect(
            p.getInt(Prefs.KEY_AREA_X, 0),
            p.getInt(Prefs.KEY_AREA_Y, 0),
            0, 0
        )
        r.right = r.left + w
        r.bottom = r.top + h
        // 屏幕分辨率可能变了（换过设备方向/外接屏），夹回可视范围
        return clamp(r, screenW, screenH)
    }

    fun save(ctx: Context, area: Rect) {
        Prefs.get(ctx).edit()
            .putInt(Prefs.KEY_AREA_X, area.left)
            .putInt(Prefs.KEY_AREA_Y, area.top)
            .putInt(Prefs.KEY_AREA_W, area.width())
            .putInt(Prefs.KEY_AREA_H, area.height())
            .apply()
    }

    fun describe(area: Rect): String = "${area.width()} × ${area.height()}"

    fun describeFull(ctx: Context, area: Rect): String =
        "${area.width()} × ${area.height()}  @(${area.left}, ${area.top})"
}
