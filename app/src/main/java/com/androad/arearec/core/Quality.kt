package com.androad.arearec.core

/**
 * 画质档位。
 *
 * factor 是「每像素每帧多少 bit」的系数，码率 = 宽 × 高 × 帧率 × factor。
 *
 * 0.09 对屏幕 UI 完全够用（大面积纯色，压缩率极高），但录视频内容会明显糊 ——
 * H.264 在低码率下先丢高频细节，观感就是发虚。所以默认给到高清档。
 */
enum class Quality(val label: String, val factor: Double, val hint: String) {
    STANDARD("标准", 0.09, "屏幕操作为主，文件最小"),
    HD("高清", 0.18, "录视频也清楚，推荐"),
    ULTRA("极高", 0.30, "几乎无损，文件很大");

    /** 按当前分辨率/帧率估算的码率，用于 UI 展示 */
    fun estimateMbps(w: Int, h: Int, fps: Int): Double =
        (w.toDouble() * h * fps * factor) / 1_000_000.0
}
