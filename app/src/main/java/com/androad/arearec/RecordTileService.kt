package com.androad.arearec

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.androad.arearec.record.RecordService
import com.androad.arearec.record.Session

/**
 * 控制中心磁贴。
 *
 * 存在理由：澎湃 OS 会把所有前台服务的常驻通知统一折叠进「更多通知」，
 * 通知里的按钮点不到。磁贴是控制中心一级可见的，和系统自带的「屏幕录制」并列，
 * 下拉就能点。
 *
 * 用法：控制中心 → 编辑 → 把「区域录制」拖进来。
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onTileAdded() {
        refresh()
    }

    override fun onClick() {
        if (Session.isActive) {
            RecordService.stop(this)
            refresh()
            return
        }
        val i = Intent(this, ProjectionRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 20, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val active = Session.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "区域录制"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                Session.state == Session.State.RECORDING -> "点按停止"
                Session.state == Session.State.PAUSED -> "已暂停"
                else -> "点按开始"
            }
        }
        tile.updateTile()
    }
}
