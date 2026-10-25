package com.androad.arearec.record

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 录制状态总线。
 *
 * 悬浮窗、主页、通知都要反映同一个状态，但它们分属不同的组件（Service / Activity），
 * 用进程内单例 + 监听器最省事，不用 LocalBroadcast 那一套。
 */
object Session {

    enum class State { IDLE, RECORDING, PAUSED, STOPPING }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 净录制时长（不含暂停），由服务每秒刷新 */
    @Volatile
    var elapsedMs: Long = 0L

    @Volatile
    var lastUri: Uri? = null

    @Volatile
    var lastError: String? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun offChange(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun refresh() {
        listeners.forEach { runCatching { it() } }
    }

    fun set(newState: State) {
        state = newState
        if (newState == State.IDLE) elapsedMs = 0L
        refresh()
    }

    val isActive: Boolean
        get() = state == State.RECORDING || state == State.PAUSED
}
