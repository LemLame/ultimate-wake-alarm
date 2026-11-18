package jp.waketerminal;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class AlarmScheduler {
    private final Context context;
    private final AlarmManager manager;
    private final long[] scheduled = {-1, -1, -1};
    AlarmScheduler(Context c) { context = c; manager = c.getSystemService(AlarmManager.class); }
    boolean available() { return Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms(); }
    private PendingIntent operation(int code) {
        return PendingIntent.getBroadcast(context, code,
            new Intent(context, AlarmReceiver.class).setAction("jp.waketerminal.TIMER." + code),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    void set(int code, long when) {
        if (scheduled[code] == when) return;
        if (when <= 0) { manager.cancel(operation(code)); scheduled[code] = when; return; }
        if (!available()) throw new IllegalStateException("「アラームとリマインダー」の許可が必要です");
        PendingIntent show = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), operation(code));
        scheduled[code] = when;
    }
}
