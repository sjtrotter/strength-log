package cloud.trotter.log.strength.wear.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/** Reads the active zone per render and finds the next real local-day boundary (DST included). */
internal object CivilDayFreshness {
    fun millisUntilNextDay(now: Instant, zone: ZoneId): Long {
        val tomorrow = now.atZone(zone).toLocalDate().plusDays(1)
        val boundary = tomorrow.atStartOfDay(zone).toInstant()
        return (boundary.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1L)
    }

    /**
     * Arms one inexact wall-clock alarm at the next civil-day boundary, as an
     * EXPLICIT broadcast — DATE_CHANGED never reaches manifest receivers on
     * API 26+, and a day label needs minute-scale, not exact, delivery. Every
     * glance render re-arms (same PendingIntent, so re-arming replaces), which
     * also restores the chain after a reboot's alarm wipe.
     */
    fun scheduleNextRollover(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val delay = millisUntilNextDay(Instant.now(), ZoneId.systemDefault())
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, CivilDayChangeReceiver::class.java)
                .setAction(CivilDayChangeReceiver.ACTION_CIVIL_DAY_ROLLOVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + delay, pending)
    }
}
