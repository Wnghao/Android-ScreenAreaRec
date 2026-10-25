package com.androad.arearec.record

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.net.Uri
import android.util.Log
import com.androad.arearec.core.AudioMode
import com.androad.arearec.core.Areas
import com.androad.arearec.core.OutputTarget
import com.androad.arearec.core.Quality
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport

/**
 * 录制协调器：把 GL、编码器、音频、VirtualDisplay 串起来。
 *
 * 线程模型：
 *  - gl-render   ：持有 EGL context，Dc 驱动恒定帧率渲染（屏幕静止时也要重绘，否则编码器饿死）
 *  - video-drain ：MediaCodec 出队
 *  - audio-*     ：AudioRecord 读取 + AAC 编码，在采集线程里就地 drain
 *
 * 启动顺序很关键：所有资源就绪后统一 clock.start()，保证音视频时间轴同起点。
 */
class Recorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    areaIn: Rect,
    private val screenW: Int,
    private val screenH: Int,
    private val dpi: Int,
    private val requestedAudio: AudioMode,
    private val fps: Int,
    private val quality: Quality,
    private val onFatal: (Throwable) -> Unit
) {

    private val area: Rect = Areas.clamp(areaIn, screenW, screenH)

    /** 实际生效的尺寸（宽高取偶，H.264 要求） */
    val outWidth = Areas.evenSize(area.width())
    val outHeight = Areas.evenSize(area.height())

    private val clock = RecordingClock()

    private var target: OutputTarget? = null
    private var muxer: MuxerHolder? = null
    private var videoEncoder: VideoEncoder? = null

    // 音频线程会读这两个字段，主线程写，必须 volatile
    @Volatile
    private var audioEncoder: AudioEncoder? = null

    @Volatile
    private var audioCapture: AudioCapture? = null

    private var renderer: GlRenderer? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var renderThread: Thread? = null

    private val readyLatch = CountDownLatch(1)
    private val goLatch = CountDownLatch(1)
    private val loopExitLatch = CountDownLatch(1)
    private val cleanupLatch = CountDownLatch(1)

    @Volatile
    private var running = false

    @Volatile
    private var startupError: Throwable? = null

    @Volatile
    private var acceptingAudio = false

    var effectiveAudioMode: AudioMode = AudioMode.NONE
        private set

    var outputUri: Uri? = null
        private set

    // ------------------------------------------------------------------ 生命周期

    fun start(): Boolean {
        try {
            target = OutputTarget.create(ctx) ?: throw IllegalStateException("无法创建输出文件")
            val fd = target!!.pfd.fileDescriptor

            // 1) 音频先起，才知道最终是不是真的有音轨
            if (requestedAudio != AudioMode.NONE) {
                val cap = AudioCapture(ctx, requestedAudio, projection, SAMPLE_RATE) { pcm, len ->
                    if (acceptingAudio) {
                        audioEncoder?.encode(pcm, len)
                        audioEncoder?.drain()
                    }
                }
                if (cap.start()) {
                    audioCapture = cap
                    effectiveAudioMode = cap.effectiveMode
                } else {
                    cap.stop()
                    effectiveAudioMode = AudioMode.NONE
                }
            }

            // 2) Muxer：有没有音轨必须在这里定死，否则永远凑不齐轨道不会 start
            val withAudio = effectiveAudioMode != AudioMode.NONE
            muxer = MuxerHolder(fd, withAudio)
            if (withAudio) audioEncoder = AudioEncoder(muxer!!, SAMPLE_RATE, 1)

            // 3) 视频编码器
            val bitRate = VideoEncoder.estimateBitRate(outWidth, outHeight, fps, quality.factor)
            val enc = VideoEncoder(outWidth, outHeight, fps, bitRate, muxer!!)
            videoEncoder = enc
            enc.startDrain()

            // 4) GL 渲染线程（EGL context 必须和渲染在同一线程）
            running = true
            val t = Thread({ renderThreadBody(enc) }, "gl-render")
            renderThread = t
            t.start()

            if (!readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw IllegalStateException("GL 初始化超时")
            }
            startupError?.let { throw it }

            // 5) VirtualDisplay —— 必须全屏尺寸，裁剪交给 GL
            val r = renderer!!
            virtualDisplay = projection.createVirtualDisplay(
                "AreaRec",
                screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.displaySurface(),
                null, null
            ) ?: throw IllegalStateException("createVirtualDisplay 返回 null")

            // 6) 全部就绪，统一起跑
            clock.start()
            acceptingAudio = true
            goLatch.countDown()
            Log.i(TAG, "start ok area=$area out=${outWidth}x${outHeight} audio=$effectiveAudioMode")
            return true

        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            teardown(failed = true)
            onFatal(t)
            return false
        }
    }

    fun pause() {
        clock.pause()
        audioCapture?.setPaused(true)
        Log.i(TAG, "pause @ ${clock.nowUs() / 1000}ms")
    }

    fun resume() {
        clock.resume()
        audioCapture?.setPaused(false)
        videoEncoder?.requestSyncFrame()
        Log.i(TAG, "resume @ ${clock.nowUs() / 1000}ms")
    }

    fun isPaused(): Boolean = clock.isPaused()

    fun elapsedMs(): Long = clock.nowUs() / 1000L

    /** 停止并收尾。返回录制成功的文件 uri，失败返回 null */
    fun stop(): Uri? {
        Log.i(TAG, "stop request @ ${clock.nowUs() / 1000}ms")
        running = false
        runCatching { loopExitLatch.await(2, java.util.concurrent.TimeUnit.SECONDS) }

        audioCapture?.setPaused(true)
        acceptingAudio = false

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        videoEncoder?.let {
            it.requestEos()
            it.awaitDrain(3000)
        }
        videoEncoder?.error()?.let { Log.e(TAG, "video encoder error", it) }

        audioCapture?.stop()
        audioEncoder?.let {
            it.requestEos()
            it.drain()
        }

        cleanupLatch.countDown()
        runCatching { renderThread?.join(2000) }

        videoEncoder?.release()
        audioEncoder?.release()

        val hadData = muxer?.release() ?: false
        target?.close()

        val tgt = target
        val uri = if (hadData && tgt != null) {
            OutputTarget.publish(ctx, tgt)
            tgt.uri
        } else {
            if (tgt != null) OutputTarget.discard(ctx, tgt)
            null
        }
        outputUri = uri
        teardown(failed = uri == null)
        return uri
    }

    private fun teardown(failed: Boolean) {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { audioCapture?.stop() }
        audioCapture = null
        runCatching { audioEncoder?.release() }
        audioEncoder = null
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        cleanupLatch.countDown()
        runCatching { renderThread?.join(1000) }
        renderThread = null
        runCatching { muxer?.release() }
        muxer = null
        target?.let {
            if (failed) OutputTarget.discard(ctx, it)
            it.close()
        }
        target = null
    }

    // ------------------------------------------------------------------ 渲染线程

    private fun renderThreadBody(enc: VideoEncoder) {
        try {
            val r = GlRenderer(enc.inputSurface, outWidth, outHeight)
            r.setArea(area.left, area.top, area.width(), area.height(), screenW, screenH)
            renderer = r
            readyLatch.countDown()

            goLatch.await()
            renderLoop(r)
        } catch (t: Throwable) {
            startupError = t
            readyLatch.countDown()
            goLatch.countDown()
            Log.e(TAG, "render thread failed", t)
        } finally {
            loopExitLatch.countDown()
            runCatching { cleanupLatch.await(3, java.util.concurrent.TimeUnit.SECONDS) }
            runCatching { renderer?.release() }
            renderer = null
        }
    }

    /**
     * 恒定帧率重绘。
     * 屏幕内容不变时 VirtualDisplay 不出新帧，如果只在 onFrameAvailable 时渲染，
     * 编码器会长时间收不到画面，部分播放器会把这一帧拉得非常长。
     */
    private fun renderLoop(r: GlRenderer) {
        val frameIntervalNs = 1_000_000_000L / fps
        var next = System.nanoTime()
        while (running) {
            if (!clock.isPaused()) {
                val ptsNs = clock.nowUs() * 1000L
                runCatching { r.drawFrame(ptsNs) }
                    .onFailure {
                        Log.e(TAG, "drawFrame failed", it)
                        if (it is IllegalStateException) return
                    }
            }
            next += frameIntervalNs
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                LockSupport.parkNanos(sleep)
            } else {
                // 掉帧了，别追，重新对齐
                next = System.nanoTime()
            }
        }
    }

    companion object {
        private const val TAG = "Recorder"
        const val SAMPLE_RATE = 48000
    }
}
