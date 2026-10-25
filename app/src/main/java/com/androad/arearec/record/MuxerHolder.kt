package com.androad.arearec.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * MediaMuxer 的薄封装。
 *
 * 两个坑专门处理了：
 * 1. 必须先 addTrack 全部轨道才能 start，而视频轨的 format 往往比音频轨先到。
 *    start 之前写进来的样本会丢 —— 这里用 pending 队列缓存，start 后补写。
 * 2. 没有任何样本就 stop() 会抛 IllegalStateException，这里吞掉。
 */
class MuxerHolder(fd: FileDescriptor, private val audioEnabled: Boolean) {

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()

    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var released = false
    private val pending = ArrayList<Sample>()

    private class Sample(
        val track: Int,
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        if (videoTrack >= 0) return videoTrack
        videoTrack = muxer.addTrack(format)
        maybeStart()
        videoTrack
    }

    fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
        if (audioTrack >= 0) return audioTrack
        audioTrack = muxer.addTrack(format)
        maybeStart()
        audioTrack
    }

    private fun maybeStart() {
        if (started) return
        if (videoTrack < 0) return
        if (audioEnabled && audioTrack < 0) return
        muxer.start()
        started = true
        for (s in pending) writeInternal(s.track, s.data, s.ptsUs, s.flags)
        pending.clear()
    }

    fun writeVideo(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0 || info.size <= 0) return
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(bytes)
        write(track, bytes, info.presentationTimeUs, info.flags)
    }

    fun write(track: Int, data: ByteArray, ptsUs: Long, flags: Int) = synchronized(lock) {
        if (released) return
        if (!started) {
            // 还没凑齐轨道，先攒着
            if (pending.size < 600) pending.add(Sample(track, data, ptsUs, flags))
            return
        }
        writeInternal(track, data, ptsUs, flags)
    }

    private fun writeInternal(track: Int, data: ByteArray, ptsUs: Long, flags: Int) {
        try {
            val info = MediaCodec.BufferInfo()
            info.set(0, data.size, ptsUs, flags)
            muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
        } catch (t: Throwable) {
            Log.w(TAG, "writeSampleData failed track=$track pts=$ptsUs", t)
        }
    }

    /** 停止并释放。返回是否成功写入过内容（用于判断文件是否可用） */
    fun release(): Boolean = synchronized(lock) {
        if (released) return false
        released = true
        var hadData = false
        try {
            if (started) {
                muxer.stop()
                hadData = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "muxer.stop failed (likely no samples written)", t)
        }
        runCatching { muxer.release() }
        pending.clear()
        hadData
    }

    companion object {
        private const val TAG = "MuxerHolder"
    }
}
