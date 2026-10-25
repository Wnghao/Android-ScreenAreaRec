package com.androad.arearec.record

/**
 * 录制共享时间基。
 *
 * MediaMuxer 没有「暂停」语义，所以暂停只能靠自己维护时间轴：
 * 暂停时把已流逝的时间累加进 accumulatedUs 并冻结，恢复后从冻结值接着走。
 * 这样暂停期间那一整段在 MP4 里直接被剪掉，播放器看到的是无缝的。
 *
 * 视频帧和音频帧都用这里的 nowUs() 作为基准，保证音画同步。
 */
class RecordingClock {

    private var baseNs = System.nanoTime()
    private var accumulatedUs = 0L
    private var paused = false

    /** 所有资源就绪后调一次，把时间轴拉回 0 起点，保证音视频同起点 */
    @Synchronized
    fun start() {
        baseNs = System.nanoTime()
        accumulatedUs = 0L
        paused = false
    }

    @Synchronized
    fun pause() {
        if (paused) return
        accumulatedUs += (System.nanoTime() - baseNs) / 1_000L
        paused = true
    }

    @Synchronized
    fun resume() {
        if (!paused) return
        baseNs = System.nanoTime()
        paused = false
    }

    @Synchronized
    fun isPaused(): Boolean = paused

    /** 已录制的净时长（不含暂停段），微秒 */
    @Synchronized
    fun nowUs(): Long {
        if (paused) return accumulatedUs
        return accumulatedUs + (System.nanoTime() - baseNs) / 1_000L
    }
}
