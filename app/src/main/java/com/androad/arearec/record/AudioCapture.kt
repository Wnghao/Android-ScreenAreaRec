package com.androad.arearec.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import com.androad.arearec.core.AudioMode
import java.util.concurrent.ArrayBlockingQueue

/**
 * 音频采集。三路：
 *  - INTERNAL：AudioPlaybackCapture（API 29+），录 App 播放的声音
 *  - MIC：AudioRecord 麦克风
 *  - MIX：两路同时开，按 10ms 块对齐后相加
 *
 * 统一 48kHz / 单声道 / PCM16。单声道是有意为之：录屏场景够用，
 * 而且内录的 AudioRecord 允许 MONO，混音时两路格式一致才不用重采样。
 */
class AudioCapture(
    private val ctx: Context,
    private val mode: AudioMode,
    private val projection: MediaProjection?,
    private val sampleRate: Int = 48000,
    private val onPcm: (ShortArray, Int) -> Unit
) {

    private var internalRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private val threads = mutableListOf<Thread>()

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    /** 实际生效的模式（降级后可能与请求的不同） */
    var effectiveMode: AudioMode = mode
        private set

    private val blockSize = sampleRate / 100 // 10ms

    fun setPaused(p: Boolean) {
        paused = p
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (mode == AudioMode.NONE) return true

        var wantInternal = mode == AudioMode.INTERNAL || mode == AudioMode.MIX
        var wantMic = mode == AudioMode.MIC || mode == AudioMode.MIX

        if (wantInternal && projection == null) wantInternal = false
        if (wantMic && !hasMicPermission()) wantMic = false

        if (wantInternal) {
            internalRec = runCatching { buildInternal(projection!!) }
                .onFailure { Log.e(TAG, "internal capture unavailable", it) }
                .getOrNull()
        }
        if (wantMic) {
            micRec = runCatching { buildMic() }
                .onFailure { Log.e(TAG, "mic capture unavailable", it) }
                .getOrNull()
        }

        // 混音时两路都得能跑，缺一路就降级
        if (internalRec == null) wantInternal = false
        if (micRec == null) wantMic = false

        if (!wantInternal && !wantMic) {
            effectiveMode = AudioMode.NONE
            Log.w(TAG, "requested $mode but no source available")
            return false
        }

        effectiveMode = when {
            wantInternal && wantMic -> AudioMode.MIX
            wantInternal -> AudioMode.INTERNAL
            else -> AudioMode.MIC
        }

        val started = mutableListOf<AudioRecord>()
        if (wantInternal) {
            val ok = runCatching {
                internalRec!!.startRecording()
                internalRec!!.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
            if (ok) started.add(internalRec!!) else {
                Log.e(TAG, "internal startRecording failed")
                runCatching { internalRec!!.release() }
                internalRec = null
            }
        }
        if (wantMic) {
            val ok = runCatching {
                micRec!!.startRecording()
                micRec!!.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
            if (ok) started.add(micRec!!) else {
                Log.e(TAG, "mic startRecording failed")
                runCatching { micRec!!.release() }
                micRec = null
            }
        }

        if (started.isEmpty()) {
            effectiveMode = AudioMode.NONE
            return false
        }

        running = true
        when {
            internalRec != null && micRec != null -> startMixed()
            internalRec != null -> startSingle(internalRec!!)
            micRec != null -> startSingle(micRec!!)
        }
        return true
    }

    fun stop() {
        running = false
        threads.forEach { runCatching { it.join(600) } }
        threads.clear()
        runCatching { internalRec?.stop() }
        runCatching { internalRec?.release() }
        runCatching { micRec?.stop() }
        runCatching { micRec?.release() }
        internalRec = null
        micRec = null
    }

    // ------------------------------------------------------------------ 内部

    private fun startSingle(rec: AudioRecord) {
        val t = Thread({ readLoop(rec, null) }, "audio-single")
        threads.add(t)
        t.start()
    }

    private fun startMixed() {
        val qa = ArrayBlockingQueue<ShortArray>(2)
        val qb = ArrayBlockingQueue<ShortArray>(2)
        val ta = Thread({ readLoop(internalRec!!, qa) }, "audio-internal")
        val tb = Thread({ readLoop(micRec!!, qb) }, "audio-mic")
        val tm = Thread({ mixLoop(qa, qb) }, "audio-mix")
        threads.addAll(listOf(ta, tb, tm))
        ta.start()
        tb.start()
        tm.start()
    }

    private fun readLoop(rec: AudioRecord, queue: ArrayBlockingQueue<ShortArray>?) {
        val buf = ShortArray(blockSize)
        while (running) {
            var read = 0
            while (read < blockSize && running) {
                val n = runCatching {
                    rec.read(buf, read, blockSize - read)
                }.getOrDefault(-1)
                if (n <= 0) break
                read += n
            }
            if (read < blockSize) java.util.Arrays.fill(buf, read, blockSize, 0)
            if (paused) continue // 暂停时照读照丢，保持底层缓冲不积压

            val out = buf.copyOf(blockSize)
            if (queue == null) {
                onPcm(out, blockSize)
            } else {
                offerLatest(queue, out)
            }
        }
    }

    private fun mixLoop(qa: ArrayBlockingQueue<ShortArray>, qb: ArrayBlockingQueue<ShortArray>) {
        val out = ShortArray(blockSize)
        while (running) {
            val t0 = System.nanoTime()
            val a = qa.poll()
            val b = qb.poll()
            if (a == null && b == null) {
                java.util.Arrays.fill(out, 0)
            } else {
                if (a != null) a.copyInto(out) else java.util.Arrays.fill(out, 0)
                if (b != null) {
                    for (i in out.indices) {
                        val v = out[i].toInt() + b[i].toInt()
                        out[i] = v.coerceIn(-32768, 32767).toShort()
                    }
                }
            }
            if (!paused) onPcm(out, blockSize)

            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val sleep = 10 - elapsedMs
            if (sleep > 0) {
                runCatching { Thread.sleep(sleep) }
            }
        }
    }

    private fun offerLatest(q: ArrayBlockingQueue<ShortArray>, item: ShortArray) {
        while (!q.offer(item)) {
            q.poll()
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildInternal(projection: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 4, blockSize * 2 * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, blockSize * 2 * 8)
        )
    }

    private fun hasMicPermission(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AudioCapture"
    }
}
