package com.androad.arearec.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder

/**
 * AAC 编码。
 *
 * PTS 用「已送入的采样数 / 采样率」推算 —— 比用系统时间戳稳定得多，
 * 而且暂停期间样本数不增长，自然就把暂停段剪掉了，和视频时间轴严丝合缝。
 */
class AudioEncoder(
    private val muxer: MuxerHolder,
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    bitRate: Int = 128_000
) {

    private val codec: MediaCodec
    private var trackIndex = -1
    private var totalSamples = 0L
    private var eosRequested = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** @param pcm 交错 PCM 16bit，len 是 short 的个数（含所有声道） */
    fun encode(pcm: ShortArray, len: Int) {
        if (len <= 0) return
        var offset = 0
        while (offset < len) {
            val idx = codec.dequeueInputBuffer(10_000L)
            if (idx < 0) {
                // 编码器堵了，丢掉剩下的，避免无限阻塞把音频线程拖死
                Log.w(TAG, "no input buffer, dropped ${len - offset} shorts")
                return
            }
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            buf.order(ByteOrder.nativeOrder())
            val sb = buf.asShortBuffer()
            val capacityShorts = sb.capacity()
            val chunk = minOf(len - offset, capacityShorts)
            sb.put(pcm, offset, chunk)

            val ptsUs = totalSamples * 1_000_000L / sampleRate
            codec.queueInputBuffer(idx, 0, chunk * 2, ptsUs, 0)

            totalSamples += chunk / channels
            offset += chunk
        }
    }

    /** 需要在音频线程里持续调用，把编码结果排空 */
    fun drain() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addAudioTrack(codec.outputFormat)
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !isConfig) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        muxer.write(trackIndex, bytes, info.presentationTimeUs, info.flags)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun requestEos() {
        if (eosRequested) return
        eosRequested = true
        runCatching {
            val idx = codec.dequeueInputBuffer(100_000L)
            if (idx >= 0) {
                val ptsUs = totalSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val TAG = "AudioEncoder"
    }
}
