package com.androad.arearec.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 区域选择视图。
 *
 * 两种形态：
 *  - 编辑态：全屏遮罩 + 亮色边框 + 8 个拖拉手柄 + 内部可整体拖动 + 外部拖拽重新框选
 *  - 录制态：只画一圈贴在区域「外侧」的细边框 —— 画在外侧是为了不被录进画面
 *
 * 坐标即屏幕物理像素（窗口带 FLAG_LAYOUT_IN_SCREEN），所以这里的 Rect 可以直接
 * 喂给 Recorder，不需要任何换算。
 */
class RegionView(context: Context) : View(context) {

    var area: Rect = Rect()
        set(value) {
            field = Rect(value)
            invalidate()
        }

    var editing: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        android.util.Log.i("RegionView", "size=$w x $h  area=$area")
    }

    var showBorderWhileRecording: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** 每次区域变化回调（拖动过程中也会调） */
    var onAreaChanged: ((Rect) -> Unit)? = null

    /** 拖动结束回调（已做 2px 对齐） */
    var onAreaCommitted: ((Rect) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val minSize = dp(64f).toInt()
    private val handleRadius = dp(9f)
    private val touchRadius = dp(30f)

    private val maskPaint = Paint().apply { color = Color.parseColor("#99000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#4DA3FF")
    }
    private val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#4DA3FF")
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#4DA3FF")
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#664DA3FF")
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC101418")
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    // 拖动状态
    private var mode = MODE_NONE
    private var activeHandle = -1
    private var downX = 0f
    private var downY = 0f
    private var startArea = Rect()
    private var newArea = Rect()

    override fun onDraw(canvas: Canvas) {
        if (area.isEmpty) return

        if (!editing) {
            if (showBorderWhileRecording) {
                // 向外让开一整个线宽：strokeWidth 是居中绘制的，只外扩一半的话
                // 会有 2px 压在录制区域里，被 GL 裁进画面
                val pad = dp(3.5f)
                val f = RectF(
                    area.left - pad, area.top - pad,
                    area.right + pad, area.bottom + pad
                )
                canvas.drawRect(f, outerBorderPaint)
            }
            return
        }

        canvas.save()
        canvas.clipOutRect(RectF(area))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restore()

        val f = RectF(area)
        canvas.drawRect(f, borderPaint)

        // 三分参考线
        val oneThirdX = area.width() / 3f
        val oneThirdY = area.height() / 3f
        canvas.drawLine(area.left + oneThirdX, area.top.toFloat(), area.left + oneThirdX, area.bottom.toFloat(), guidePaint)
        canvas.drawLine(area.left + 2 * oneThirdX, area.top.toFloat(), area.left + 2 * oneThirdX, area.bottom.toFloat(), guidePaint)
        canvas.drawLine(area.left.toFloat(), area.top + oneThirdY, area.right.toFloat(), area.top + oneThirdY, guidePaint)
        canvas.drawLine(area.left.toFloat(), area.top + 2 * oneThirdY, area.right.toFloat(), area.top + 2 * oneThirdY, guidePaint)

        // 8 个手柄
        for (i in 0 until 8) {
            val (hx, hy) = handlePoint(i)
            canvas.drawCircle(hx, hy, handleRadius, handleFill)
            canvas.drawCircle(hx, hy, handleRadius, handleStroke)
        }

        drawLabel(canvas)
    }

    private fun drawLabel(canvas: Canvas) {
        val text = "${area.width()} × ${area.height()}"
        val padH = dp(8f)
        val padV = dp(5f)
        val tw = labelText.measureText(text)
        val boxW = tw + padH * 2
        val boxH = labelText.textSize + padV * 2

        val cx = area.centerX().toFloat()
        var top = area.top - boxH - dp(10f)
        if (top < dp(8f)) top = area.top + dp(10f)
        val left = (cx - boxW / 2).coerceIn(dp(4f), width - boxW - dp(4f))

        val rf = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rf, dp(6f), dp(6f), labelBg)
        canvas.drawText(text, left + padH, top + padV + labelText.textSize * 0.82f, labelText)
    }

    private fun handlePoint(index: Int): Pair<Float, Float> {
        val cx = area.centerX().toFloat()
        val cy = area.centerY().toFloat()
        return when (index) {
            0 -> area.left.toFloat() to area.top.toFloat()
            1 -> cx to area.top.toFloat()
            2 -> area.right.toFloat() to area.top.toFloat()
            3 -> area.right.toFloat() to cy
            4 -> area.right.toFloat() to area.bottom.toFloat()
            5 -> cx to area.bottom.toFloat()
            6 -> area.left.toFloat() to area.bottom.toFloat()
            else -> area.left.toFloat() to cy
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return false

        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                startArea = Rect(area)
                val h = hitHandle(x, y)
                mode = when {
                    h >= 0 -> {
                        activeHandle = h
                        MODE_HANDLE
                    }
                    area.contains(x.toInt(), y.toInt()) -> MODE_MOVE
                    else -> {
                        // 区域外按下：拖出一个新框
                        newArea = Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
                        MODE_NEW
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_HANDLE -> resizeTo(activeHandle, x, y)
                    MODE_MOVE -> moveTo(x - downX, y - downY)
                    MODE_NEW -> {
                        newArea = Rect(
                            min(downX, x).toInt(), min(downY, y).toInt(),
                            max(downX, x).toInt(), max(downY, y).toInt()
                        )
                        area = newArea
                        onAreaChanged?.invoke(Rect(area))
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_NEW && (area.width() < minSize || area.height() < minSize)) {
                    area = startArea // 太小，撤销
                }
                val snapped = snapEven(area)
                if (snapped != area) area = snapped
                mode = MODE_NONE
                activeHandle = -1
                onAreaChanged?.invoke(Rect(area))
                onAreaCommitted?.invoke(Rect(area))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitHandle(x: Float, y: Float): Int {
        for (i in 0 until 8) {
            val (hx, hy) = handlePoint(i)
            if (abs(x - hx) <= touchRadius && abs(y - hy) <= touchRadius) return i
        }
        return -1
    }

    private fun resizeTo(handle: Int, x: Float, y: Float) {
        val r = Rect(startArea)
        val xi = x.toInt().coerceIn(0, width)
        val yi = y.toInt().coerceIn(0, height)
        when (handle) {
            0 -> { r.left = min(xi, r.right - minSize); r.top = min(yi, r.bottom - minSize) }
            1 -> r.top = min(yi, r.bottom - minSize)
            2 -> { r.right = max(xi, r.left + minSize); r.top = min(yi, r.bottom - minSize) }
            3 -> r.right = max(xi, r.left + minSize)
            4 -> { r.right = max(xi, r.left + minSize); r.bottom = max(yi, r.top + minSize) }
            5 -> r.bottom = max(yi, r.top + minSize)
            6 -> { r.left = min(xi, r.right - minSize); r.bottom = max(yi, r.top + minSize) }
            7 -> r.left = min(xi, r.right - minSize)
        }
        r.left = r.left.coerceAtLeast(0)
        r.top = r.top.coerceAtLeast(0)
        r.right = r.right.coerceAtMost(width)
        r.bottom = r.bottom.coerceAtMost(height)
        area = r
        onAreaChanged?.invoke(Rect(area))
    }

    private fun moveTo(dx: Float, dy: Float) {
        val r = Rect(startArea)
        var offsetX = dx.toInt()
        var offsetY = dy.toInt()
        offsetX = offsetX.coerceIn(-r.left, width - r.right)
        offsetY = offsetY.coerceIn(-r.top, height - r.bottom)
        r.offset(offsetX, offsetY)
        area = r
        onAreaChanged?.invoke(Rect(area))
    }

    /** 编码要求宽高为偶数，落地前对齐，避免渲染时被隐含缩放 */
    private fun snapEven(r: Rect): Rect {
        val out = Rect(r)
        out.right = out.left + (out.width() and 1.inv()).coerceAtLeast(minSize)
        out.bottom = out.top + (out.height() and 1.inv()).coerceAtLeast(minSize)
        out.right = out.right.coerceAtMost(width)
        out.bottom = out.bottom.coerceAtMost(height)
        return out
    }

    fun setFullScreen() {
        area = Rect(0, 0, width, height)
        onAreaChanged?.invoke(Rect(area))
        onAreaCommitted?.invoke(Rect(area))
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_HANDLE = 1
        const val MODE_MOVE = 2
        const val MODE_NEW = 3
    }
}
