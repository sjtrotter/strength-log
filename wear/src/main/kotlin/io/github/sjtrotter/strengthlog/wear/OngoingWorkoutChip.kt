package io.github.sjtrotter.strengthlog.wear

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * Posts (and clears) the Wear [OngoingActivity] chip that keeps an in-progress
 * workout one tap away from the watch face after an accidental stem press or an
 * ambient timeout (redesign §1.4 / R6).
 *
 * Deliberately **no foreground service**: a posted ongoing notification already
 * survives process death, and an FGS would drag in `FOREGROUND_SERVICE` +
 * API-34 service-type policy for no added user value. Whether the chip actually
 * renders/survives is best-effort by design (R8 risk #1) — logging never
 * depends on it, so a missing [Manifest.permission.POST_NOTIFICATIONS] grant is
 * a silent no-op here, not an error.
 */
class OngoingWorkoutChip(context: Context) {

    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    /** Post the chip if allowed; a no-op when the notifications permission is absent. */
    fun show() {
        // Inline permission gate (the pattern lint's NotificationPermission check
        // recognizes): on API < 33 the permission is install-time granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()
        val touchIntent = reentryIntent(appContext)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(TITLE)
            .setContentText(TEXT)
            .setSmallIcon(R.drawable.ic_ongoing_workout)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(touchIntent)
            .setSilent(true)

        OngoingActivity.Builder(appContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_ongoing_workout)
            .setTouchIntent(touchIntent)
            .setStatus(Status.Builder().addTemplate(TITLE).build())
            .build()
            .apply(appContext)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    /** Remove the chip. Safe to call when none is posted — used for reconcile-on-launch. */
    fun clear() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(CHANNEL_NAME)
                .setDescription(CHANNEL_DESC)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "workout_in_progress"
        private const val CHANNEL_NAME = "Workout in progress"
        private const val CHANNEL_DESC =
            "Keeps an in-progress workout one tap away from the watch face."
        private const val NOTIFICATION_ID = 1001
        private const val TITLE = "Workout in progress"
        private const val TEXT = "Tap to keep logging"

        /** POST_NOTIFICATIONS only became a runtime permission in API 33 (Tiramisu). */
        fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun hasPostNotificationsPermission(context: Context): Boolean =
            !needsRuntimePermission() ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Deep-link back into the running task: `SINGLE_TOP` + `CLEAR_TOP` re-enters
         * the existing [MainActivity] instead of stacking a new one, so one tap on
         * the watch-face chip lands exactly where the lifter left off.
         */
        private fun reentryIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
