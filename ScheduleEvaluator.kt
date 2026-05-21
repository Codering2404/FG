package com.focusguard.app.utils

import com.focusguard.app.data.Schedule
import java.util.*

object ScheduleEvaluator {

    /**
     * Returns true if [schedule] is currently active based on:
     *  - day of week  (daysOfWeek field: "1,2,3,4,5" = Mon–Fri in Calendar constant)
     *  - time of day  (startHour:startMin → endHour:endMin, handles midnight crossing)
     */
    fun isActive(schedule: Schedule): Boolean {
        val now = Calendar.getInstance()
        val todayDow = now.get(Calendar.DAY_OF_WEEK)   // Calendar.MONDAY = 2 … SUNDAY = 1

        val activeDays = schedule.daysOfWeek
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }

        if (todayDow !in activeDays) return false

        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMin   = schedule.startHour * 60 + schedule.startMin
        val endMin     = schedule.endHour   * 60 + schedule.endMin

        return if (endMin > startMin) {
            // Normal range: e.g. 09:00–18:00
            nowMinutes in startMin until endMin
        } else {
            // Midnight-crossing range: e.g. 22:00–07:00
            nowMinutes >= startMin || nowMinutes < endMin
        }
    }
}
