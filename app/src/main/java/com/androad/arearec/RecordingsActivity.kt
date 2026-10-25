package com.androad.arearec

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.androad.arearec.core.OutputTarget
import com.androad.arearec.record.RecordService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录制文件列表。
 *
 * 悬浮条上的「文件」按钮进的就是这里 —— 不走系统文件管理器，
 * 因为各家 ROM 对「打开某个文件夹」的 Intent 支持五花八门，自己列最稳。
 *
 * 两种模式：
 *  - 浏览：点击播放，长按弹菜单（播放 / 用其他应用打开 / 分享 / 重命名 / 删除）
 *  - 多选：右上角「选择」进入，勾选后底部批量删除
 */
class RecordingsActivity : Activity() {

    private val items = mutableListOf<Item>()
    private lateinit var adapter: ItemAdapter
    private lateinit var emptyView: View
    private lateinit var listView: ListView
    private lateinit var barTitle: TextView
    private lateinit var selectBtn: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var actionInfo: TextView

    private var selecting = false
    private val selected = LinkedHashSet<Uri>()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density + 0.5f).toInt()

    private val colorBg = Color.parseColor("#F2F2F7")
    private val colorText = Color.parseColor("#1C1C1E")
    private val colorSub = Color.parseColor("#6E6E73")
    private val colorPrimary = Color.parseColor("#185FA5")
    private val colorDanger = Color.parseColor("#E24B4A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::listView.isInitialized) load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selecting) {
            exitSelectMode()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(14f), dp(12f), dp(10f))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(colorPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
            setOnClickListener { finish() }
        })
        barTitle = TextView(this).apply {
            text = "录制文件"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(colorText)
            typeface = MEDIUM
        }
        bar.addView(barTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        selectBtn = TextView(this).apply {
            text = "选择"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorPrimary)
            setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
            isClickable = true
            setOnClickListener { toggleSelectMode() }
        }
        bar.addView(selectBtn)
        root.addView(bar)

        root.addView(TextView(this).apply {
            text = "保存位置：Movies/${OutputTarget.SUBDIR}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorSub)
            setPadding(dp(20f), 0, dp(20f), dp(10f))
        })

        listView = ListView(this).apply {
            divider = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            dividerHeight = dp(10f)
            setPadding(dp(16f), dp(4f), dp(16f), dp(24f))
            clipToPadding = false
        }
        adapter = ItemAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val item = items[pos]
            if (selecting) toggleSelected(item) else play(item)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            if (selecting) toggleSelected(items[pos]) else showActions(items[pos])
            true
        }
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32f), 0, dp(32f), dp(80f))
            addView(TextView(this@RecordingsActivity).apply {
                text = "还没有录制文件"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(colorSub)
                gravity = Gravity.CENTER
            })
        }
        root.addView(emptyView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(12f), dp(16f), dp(20f))
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        actionInfo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorSub)
        }
        actionBar.addView(
            actionInfo,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        actionBar.addView(TextView(this).apply {
            text = "全选"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorText)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            isClickable = true
            setOnClickListener { selectAll() }
        })
        actionBar.addView(TextView(this).apply {
            text = "删除"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            typeface = MEDIUM
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(colorDanger)
            }
            isClickable = true
            setOnClickListener { confirmDeleteSelected() }
        })
        root.addView(actionBar)

        setContentView(root)
    }

    // ------------------------------------------------------------------ 多选

    private fun toggleSelectMode() {
        if (selecting) exitSelectMode() else enterSelectMode()
    }

    private fun enterSelectMode() {
        if (items.isEmpty()) {
            Toast.makeText(this, "没有文件可选", Toast.LENGTH_SHORT).show()
            return
        }
        selecting = true
        selected.clear()
        updateModeUi()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectMode() {
        selecting = false
        selected.clear()
        updateModeUi()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelected(item: Item) {
        if (!selected.remove(item.uri)) selected.add(item.uri)
        updateModeUi()
        adapter.notifyDataSetChanged()
    }

    private fun selectAll() {
        if (selected.size == items.size) {
            selected.clear()
        } else {
            items.forEach { selected.add(it.uri) }
        }
        updateModeUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateModeUi() {
        if (selecting) {
            barTitle.text = if (selected.isEmpty()) "选择文件" else "已选 ${selected.size} 项"
            selectBtn.text = "完成"
            actionBar.visibility = View.VISIBLE
            actionInfo.text = "共 ${items.size} 项，已选 ${selected.size}"
        } else {
            barTitle.text = "录制文件"
            selectBtn.text = "选择"
            actionBar.visibility = View.GONE
        }
    }

    private fun confirmDeleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "还没选中文件", Toast.LENGTH_SHORT).show()
            return
        }
        val n = selected.size
        val totalBytes = items.filter { selected.contains(it.uri) }.sumOf { it.sizeBytes }
        AlertDialog.Builder(this)
            .setTitle("删除 $n 个录像？")
            .setMessage("共 ${formatSize(totalBytes)}，删除后无法恢复")
            .setPositiveButton("删除") { _, _ ->
                var ok = 0
                var fail = 0
                selected.toList().forEach { uri ->
                    val r = runCatching { contentResolver.delete(uri, null, null) }
                    if (r.isSuccess) ok++ else fail++
                }
                selected.clear()
                selecting = false
                load()
                Toast.makeText(
                    this,
                    if (fail == 0) "已删除 $ok 个" else "删除 $ok 个，$fail 个失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ 数据

    private fun load() {
        items.clear()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        val criteria = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_MOVIES}/${OutputTarget.SUBDIR}%")

        runCatching {
            contentResolver.query(
                collection, projection, criteria, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    items.add(
                        Item(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(nameCol) ?: "未命名",
                            durationMs = c.getLong(durCol),
                            sizeBytes = c.getLong(sizeCol),
                            dateAdded = c.getLong(dateCol) * 1000
                        )
                    )
                }
            }
        }.onFailure {
            Toast.makeText(this, "读取失败：${it.message}", Toast.LENGTH_SHORT).show()
        }

        // 列表刷新后把已经不存在的选中项丢掉
        val alive = items.map { it.uri }.toSet()
        selected.retainAll(alive)
        if (items.isEmpty()) selecting = false

        adapter.notifyDataSetChanged()
        val has = items.isNotEmpty()
        listView.visibility = if (has) View.VISIBLE else View.GONE
        emptyView.visibility = if (has) View.GONE else View.VISIBLE
        updateModeUi()
    }

    // ------------------------------------------------------------------ 操作

    private fun play(item: Item) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            Toast.makeText(this, "没有可用的播放器，试试「用其他应用打开」", Toast.LENGTH_SHORT).show()
        }
    }

    /** 强制弹应用选择器，不走默认播放器 */
    private fun openWith(item: Item) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "用其他应用打开"
                )
            )
        }.onFailure {
            Toast.makeText(this, "没有可用的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActions(item: Item) {
        val actions = arrayOf("播放", "用其他应用打开", "分享", "重命名", "删除")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> play(item)
                    1 -> openWith(item)
                    2 -> share(item)
                    3 -> rename(item)
                    4 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun share(item: Item) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, item.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "分享录像"
                )
            )
        }
    }

    private fun rename(item: Item) {
        val input = EditText(this).apply {
            setText(item.name)
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val finalName = if (newName.endsWith(".mp4", true)) newName else "$newName.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
                }
                runCatching {
                    contentResolver.update(item.uri, values, null, null)
                }.onFailure {
                    Toast.makeText(this, "重命名失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setTitle("删除录像？")
            .setMessage("${item.name}\n删除后无法恢复")
            .setPositiveButton("删除") { _, _ ->
                runCatching { contentResolver.delete(item.uri, null, null) }
                    .onFailure {
                        Toast.makeText(this, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ 列表项

    private data class Item(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val dateAdded: Long
    )

    private inner class ItemAdapter : BaseAdapter() {
        private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val isSel = selected.contains(item.uri)

            val row = LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(if (isSel) Color.parseColor("#E6F1FB") else Color.WHITE)
                    if (isSel) setStroke(dp(1.2f), colorPrimary)
                }
            }

            if (selecting) {
                val check = TextView(this@RecordingsActivity).apply {
                    text = if (isSel) "✓" else ""
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (isSel) {
                            setColor(colorPrimary)
                        } else {
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(1.6f), colorSub)
                        }
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
                    marginEnd = dp(12f)
                })
            }

            val col = LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(this@RecordingsActivity).apply {
                text = item.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(colorText)
                typeface = MEDIUM
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            val meta = buildString {
                append(RecordService.formatDuration(item.durationMs))
                append("  ·  ")
                append(formatSize(item.sizeBytes))
                append("  ·  ")
                append(dateFmt.format(Date(item.dateAdded)))
            }
            col.addView(TextView(this@RecordingsActivity).apply {
                text = meta
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(colorSub)
                setPadding(0, dp(5f), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            return row
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)

        bytes >= 1024L * 1024 ->
            String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)

        else -> "${bytes / 1024} KB"
    }

    private companion object {
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
