package jp.waketerminal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Exact-alarm delivery is an allowed background foreground-service start.
        MonitorService.start(context, MonitorService.ACTION_TIMER);
    }
}
