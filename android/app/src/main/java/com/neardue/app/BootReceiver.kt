package com.neardue.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机、应用更新、系统时间/时区变化后重排所有提醒。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                AlarmScheduler.scheduleAll(context)
                // 上课提醒靠「第几周 + 周几 + 作息表」算出来，改时区/改时间后必须重算。
                CourseAlarmScheduler.rescheduleAll(context)
            }
        }
    }
}
