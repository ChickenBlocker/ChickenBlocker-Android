package io.github.chickenblocker.android;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PauseNotificationReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "pause_expiry";
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_MINUTES = "minutes";

    @Override
    public void onReceive(Context context, Intent intent) {
        String mode = intent.getStringExtra(EXTRA_MODE);
        int minutes = intent.getIntExtra(EXTRA_MINUTES, 0);
        if (mode == null || minutes == 0 || !NativeBlockPolicy.isRulePaused(context, mode)) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Pause ending reminders",
                NotificationManager.IMPORTANCE_DEFAULT
        ));
        PendingIntent openApp = PendingIntent.getActivity(
                context,
                0,
                new Intent(context, RuleListActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(NativeBlockPolicy.ruleTitle(context, mode))
                .setContentText("Pause ends in " + minutes + (minutes == 1 ? " minute" : " minutes"))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build();
        manager.notify(notificationId(mode, minutes), notification);
    }

    static void schedule(Context context, String mode, long pausedUntil) {
        schedule(context, mode, pausedUntil, 5);
        schedule(context, mode, pausedUntil, 1);
    }

    static void cancel(Context context, String mode) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        alarms.cancel(pendingIntent(context, mode, 5));
        alarms.cancel(pendingIntent(context, mode, 1));
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) {
            notifications.cancel(notificationId(mode, 5));
            notifications.cancel(notificationId(mode, 1));
        }
    }

    private static void schedule(Context context, String mode, long pausedUntil, int minutes) {
        long now = System.currentTimeMillis();
        long triggerAt = pausedUntil - minutes * 60_000L;
        if (pausedUntil < now + minutes * 60_000L) {
            return;
        }
        triggerAt = Math.max(now + 1_000, triggerAt);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) {
            alarms.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent(context, mode, minutes)
            );
        }
    }

    private static PendingIntent pendingIntent(Context context, String mode, int minutes) {
        Intent intent = new Intent(context, PauseNotificationReceiver.class)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_MINUTES, minutes);
        return PendingIntent.getBroadcast(
                context,
                notificationId(mode, minutes),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static int notificationId(String mode, int minutes) {
        return 31 * mode.hashCode() + minutes;
    }
}
