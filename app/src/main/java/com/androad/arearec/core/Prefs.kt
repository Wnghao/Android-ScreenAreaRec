package com.androad.arearec.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局偏好。区域、音频模式、帧率都存在这里，下次启动直接复用。
 */
object Prefs {

    private const val NAME = "arearec"

    const val KEY_AREA_X = "area_x"
    const val KEY_AREA_Y = "area_y"
    const val KEY_AREA_W = "area_w"
    const val KEY_AREA_H = "area_h"
    const val KEY_AUDIO_MODE = "audio_mode"
    const val KEY_FPS = "fps"
    const val KEY_QUALITY = "quality"
    const val KEY_HIDE_OVERLAY = "hide_overlay_while_recording"
    const val KEY_SHOW_BORDER = "show_border"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
