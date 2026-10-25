package com.androad.arearec.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘目标。
 *
 * 直接写 MediaStore 的 Movies/AreaRec，好处是录完立刻能在相册/文件管理器里看到，
 * 而且不需要任何存储权限（分区存储下写自己创建的 media 条目是允许的）。
 *
 * 录制期间标 IS_PENDING=1，录完置 0；中途失败就删掉条目，不留坏文件。
 */
class OutputTarget(
    val uri: Uri,
    val pfd: ParcelFileDescriptor,
    val displayName: String
) {
    val relativeDir: String = "${Environment.DIRECTORY_MOVIES}/$SUBDIR"

    fun close() {
        runCatching { pfd.close() }
    }

    companion object {
        const val SUBDIR = "AreaRec"
        private const val TAG = "OutputTarget"

        fun create(ctx: Context, tag: String = "AreaRec"): OutputTarget? {
            val name = "${tag}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBDIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = try {
                resolver.insert(collection, values)
            } catch (t: Throwable) {
                Log.e(TAG, "insert failed", t)
                null
            } ?: return null

            val pfd = try {
                resolver.openFileDescriptor(uri, "rw")
            } catch (t: Throwable) {
                Log.e(TAG, "openFileDescriptor failed", t)
                null
            }
            if (pfd == null) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            return OutputTarget(uri, pfd, name)
        }

        /** 录制成功收尾：解除 pending，让文件在相册里可见 */
        fun publish(ctx: Context, target: OutputTarget) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching { ctx.contentResolver.update(target.uri, values, null, null) }
        }

        /** 录制失败/取消：把半成品删干净 */
        fun discard(ctx: Context, target: OutputTarget) {
            runCatching { ctx.contentResolver.delete(target.uri, null, null) }
        }
    }
}
