package com.androad.arearec

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.androad.arearec.core.AudioMode
import com.androad.arearec.core.Areas
import com.androad.arearec.core.Prefs
import com.androad.arearec.core.Quality
import com.androad.arearec.core.Screen
import com.androad.arearec.record.RecordService

/**
 * 透明中转页。
 *
 * 存在的唯一原因：MediaProjection 的授权 Intent 必须由 Activity 用 startActivityForResult 发起，
 * 而「开始」按钮在悬浮窗（Service）上。所以这里做一个不可见的 Activity，
 * 一进来就弹系统授权框，拿到结果立刻把录制甩给 RecordService 然后 finish。
 *
 * 用户看到的就是：点悬浮窗的「开始」→ 系统录屏确认框直接出现。
 */
class ProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MediaProjectionManager::class.java)
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
        }.onFailure {
            Toast.makeText(this, "无法请求录屏权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) {
            finish()
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "已取消录屏授权", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val size = Screen.realSize(this)
        val area = intentArea(size.x, size.y)
        val prefs = Prefs.get(this)
        val audio = runCatching {
            AudioMode.valueOf(prefs.getString(Prefs.KEY_AUDIO_MODE, AudioMode.NONE.name)!!)
        }.getOrDefault(AudioMode.NONE)
        val fps = Screen.currentFps(this, prefs)
        val quality = runCatching {
            Quality.valueOf(prefs.getString(Prefs.KEY_QUALITY, Quality.HD.name)!!)
        }.getOrDefault(Quality.HD)

        RecordService.start(this, resultCode, data, area, audio, fps, quality)
        finish()
    }

    private fun intentArea(screenW: Int, screenH: Int): Rect {
        val l = intent.getIntExtra(EXTRA_AREA_L, Int.MIN_VALUE)
        if (l == Int.MIN_VALUE) return Areas.load(this, screenW, screenH)
        val r = Rect(
            l,
            intent.getIntExtra(EXTRA_AREA_T, 0),
            intent.getIntExtra(EXTRA_AREA_R, screenW),
            intent.getIntExtra(EXTRA_AREA_B, screenH)
        )
        return Areas.clamp(r, screenW, screenH)
    }

    companion object {
        private const val REQ_CAPTURE = 2001
        const val EXTRA_AREA_L = "area_l"
        const val EXTRA_AREA_T = "area_t"
        const val EXTRA_AREA_R = "area_r"
        const val EXTRA_AREA_B = "area_b"
    }
}
