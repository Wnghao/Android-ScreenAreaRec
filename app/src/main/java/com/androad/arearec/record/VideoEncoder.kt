package com.androad.arearec.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * H.264 硬编。
 *
 * 输入不是 ByteBuffer 而是 Surface —— GL 渲染器直接渲染到 createInputSurface() 给的那个面，
 * 帧数据一步都不经过 CPU 拷贝。
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val muxer: MuxerHolder
) {

    private val codec: MediaCodec
    val inputSurface: Surface

    private var trackIndex = -1
    private var drainThread: Thread? = null
    private var eosRequested = false

    @Volatile
    private var drainError: Throwable? = null

    init {
        val probe = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        // 先问系统哪个编码器能编这个尺寸，顺便查它支不支持 CBR。
        // CBR 很关键：VBR 遇到复杂画面会自己往下压码率，录视频内容就糊了。
        val codecName = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(probe)
        }.getOrNull()
        val bitrateMode = codecName?.let { queryBitrateMode(it) } ?: -1

        val format = probe.apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (bitrateMode > 0) setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
        }

        codec = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        Log.i(TAG, "encoder=$codecName bitrate=$bitRate mode=$bitrateMode ${width}x$height@$fps")
    }

    /** 优先 CBR，退 VBR，都不支持就返回 -1（用编码器默认） */
    private fun queryBitrateMode(codecName: String): Int {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.name.equals(codecName, ignoreCase = true)) continue
            val caps = runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }.getOrNull() ?: continue
            val enc = caps.encoderCapabilities ?: continue
            return when {
                enc.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR

                enc.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR

                else -> -1
            }
        }
        return -1
    }

    fun startDrain() {
        drainThread = Thread({ drainLoop() }, "video-drain").also { it.start() }
    }

    /** 恢复录制时插一个关键帧，方便播放器 seek */
    fun requestSyncFrame() {
        runCatching {
            codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun requestEos() {
        if (eosRequested) return
        eosRequested = true
        // Surface 输入模式下结束信号是这个，不是 signalEndOfStream
        runCatching { codec.signalEndOfInputStream() }
    }

    /** 等排水线程收完 EOS */
    fun awaitDrain(timeoutMs: Long = 3000) {
        drainThread?.let { runCatching { it.join(timeoutMs) } }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    fun error(): Throwable? = drainError

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (eosRequested) {
                            // EOS 可能已经被消费但没带 flag，保险起见再转一圈
                            continue
                        }
                    }

                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addVideoTrack(codec.outputFormat)
                    }

                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && info.size > 0 && !isConfig) {
                            muxer.writeVideo(trackIndex, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            drainError = t
            Log.e(TAG, "video drain failed", t)
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"

        /**
         * 码率估算：像素数 × 帧率 × 画质系数，夹在 1.5Mbps ~ 40Mbps 之间。
         * factor 由 Quality 枚举给，标准 0.09 / 高清 0.18 / 极高 0.30。
         */
        fun estimateBitRate(w: Int, h: Int, fps: Int, factor: Double): Int {
            val raw = (w.toDouble() * h * fps * factor).toLong()
            return raw.coerceIn(1_500_000L, 40_000_000L).toInt()
        }
    }
}
