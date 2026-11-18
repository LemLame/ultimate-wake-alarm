package jp.waketerminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class Notifications {
    static final int MONITOR_ID = 10, ALARM_ID = 11;
    static final String MONITOR = "monitor", ALARM = "wake_alarm_v1";
    static void create(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel monitor = new NotificationChannel(MONITOR, "カレンダー監視", NotificationManager.IMPORTANCE_LOW);
        monitor.setSound(null, null);
        NotificationChannel alarm = new NotificationChannel(ALARM, "起床アラーム", NotificationManager.IMPORTANCE_HIGH);
        // Playback is owned by AlarmAudio, avoiding duplicate notification sounds.
        alarm.setSound(null, null); alarm.enableVibration(false); alarm.setBypassDnd(true);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(monitor); nm.createNotificationChannel(alarm);
    }
    static PendingIntent screen(Context c, Class<?> activity) {
        return PendingIntent.getActivity(c, activity == AlarmActivity.class ? 1 : 0,
            new Intent(c, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static Notification monitor(Context c, String text) {
        return new Notification.Builder(c, MONITOR).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("起床端末 · 監視中").setContentText(text).setOngoing(true)
            .setContentIntent(screen(c, MainActivity.class)).build();
    }
    static Notification alarm(Context c, AlarmSession s, boolean fullScreen) {
        Notification.Builder b = new Notification.Builder(c, ALARM).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(s.phase == AlarmSession.Phase.RECHECK ? "もう一度、起きたことを確認" : "起きる時間です")
            .setContentText("画面の「起きた」を押してください").setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
            .setContentIntent(screen(c, AlarmActivity.class)).setOnlyAlertOnce(!fullScreen);
        if (fullScreen) b.setFullScreenIntent(screen(c, AlarmActivity.class), true);
        return b.build();
    }
}
