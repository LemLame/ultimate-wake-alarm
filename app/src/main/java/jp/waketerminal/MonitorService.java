package jp.waketerminal;

import android.Manifest;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.CalendarContract;
import java.util.ArrayList;
import java.util.List;

/** All state transitions and provider operations are serialized on one worker. */
public final class MonitorService extends Service {
    static final String ACTION_REFRESH = "jp.waketerminal.REFRESH";
    static final String ACTION_SYNC = "jp.waketerminal.SYNC";
    static final String ACTION_TIMER = "jp.waketerminal.TIMER";
    static final String ACTION_PRESS = "jp.waketerminal.PRESS";
    static final String ACTION_CHANGED = "jp.waketerminal.CHANGED";
    static volatile Status latest;
    static final class Status {
        final AlarmSession session;
        final long lastScan, heartbeat;
        final String calendar, error;
        final int pending;
        final List<WakeEvent> events;
        final CalendarSync.Status sync;
        Status(StateStore s, CalendarSync.Status sync) {
            this.sync = sync;
            session = s.session; lastScan = s.lastScan; calendar = s.calendarLabel;
            error = join(s.error, s.writeError, s.audioError); pending = s.pending.size();
            heartbeat = SystemClock.elapsedRealtime(); events = new ArrayList<>();
            for (WakeEvent e : s.events) if (!s.handled.containsKey(e.key())) events.add(e);
        }
        private static String join(String... parts) {
            StringBuilder s = new StringBuilder();
            for (String part : parts) if (!part.isEmpty()) { if (s.length() > 0) s.append('\n'); s.append(part); }
            return s.toString();
        }
    }
    private HandlerThread thread;
    private Handler worker;
    private StateStore store;
    private CalendarRepository calendar;
    private CalendarSync sync;
    private AlarmScheduler scheduler;
    private AlarmAudio sound;
    private PowerManager.WakeLock cpu;
    private ContentObserver observer;
    private long lastRevision = -1, monotonicDeadline;
    private String lastKey = "";
    private boolean observing;

    static boolean hasCalendar(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            && c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }
    static void start(Context c, String action) {
        c.startForegroundService(new Intent(c, MonitorService.class).setAction(action));
    }

    @Override public void onCreate() {
        super.onCreate();
        Notifications.create(this);
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(Notifications.MONITOR_ID, Notifications.monitor(this, "監視を開始しています"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Notifications.MONITOR_ID, Notifications.monitor(this, "監視を開始しています"));
        cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeTerminal:CalendarMonitor");
        // Renew while healthy; a stuck provider cannot retain a CPU lock indefinitely.
        cpu.setReferenceCounted(false); cpu.acquire(120_000);
        thread = new HandlerThread("wake-state"); thread.start(); worker = new Handler(thread.getLooper());
        worker.post(() -> {
            store = new StateStore(this); calendar = new CalendarRepository(getContentResolver());
            sync = new CalendarSync(this, store.prefs);
            scheduler = new AlarmScheduler(this); sound = new AlarmAudio(this);
            observer = new ContentObserver(worker) {
                @Override public void onChange(boolean selfChange) {
                    worker.removeCallbacks(changed); worker.postDelayed(changed, 300);
                }
            };
            runCycle(true); worker.postDelayed(poll, 60_000); worker.postDelayed(tick, 1_000);
        });
    }

    private final Runnable changed = () -> runCycle(true);
    private final Runnable poll = new Runnable() {
        @Override public void run() { cpu.acquire(120_000); runCycle(true); worker.postDelayed(this, 60_000); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (store.session != null) { runCycle(false); sound.pulse(); }
            worker.postDelayed(this, 1_000);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        worker.post(() -> {
            if (intent != null && ACTION_PRESS.equals(intent.getAction()) && store.session != null) {
                alignDeadline();
                AlarmSession before = store.session;
                store.session = before.press(System.currentTimeMillis(), intent.getStringExtra("key"), intent.getLongExtra("revision", -1));
                if (store.session != before) {
                    monotonicDeadline = 0;
                    // Persist before silence; a crash cannot erase the user's confirmation.
                    store.save();
                }
                if (store.session.phase == AlarmSession.Phase.CONFIRMED) complete();
                runCycle(false);
            } else runCycle(true, intent != null && ACTION_SYNC.equals(intent.getAction()));
        });
        return START_STICKY;
    }

    private void complete() {
        WakeEvent event = store.session.event;
        store.handled.put(event.key(), event.begin);
        boolean queued = false;
        for (WakeEvent e : store.pending) if (e.key().equals(event.key())) queued = true;
        if (!queued) store.pending.add(event);
        store.session = null; monotonicDeadline = 0;
        store.save(); sound.stop();
        retryWrites();
    }

    private void retryWrites() {
        store.writeError = "";
        for (WakeEvent e : new ArrayList<>(store.pending)) {
            try { calendar.confirm(e); store.pending.remove(e); store.save(); }
            catch (RuntimeException ex) { store.writeError = "起床結果の書込待ち（60秒ごとに再試行）: " + message(ex); }
        }
    }

    private void runCycle(boolean scan) {
        runCycle(scan, false);
    }
    private void runCycle(boolean scan, boolean manualSync) {
        if (store == null) return;
        alignDeadline();
        long now = System.currentTimeMillis();
        if (scan) {
            try {
                if (!hasCalendar(this)) throw new SecurityException("カレンダーの読取・書込を許可してください");
                if (!observing) {
                    getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, observer);
                    observing = true;
                }
                retryWrites();
                CalendarRepository.Primary primary = calendar.select(store.prefs.getLong("calendar_id", -1));
                sync.refresh(primary, manualSync, now, SystemClock.elapsedRealtime());
                List<WakeEvent> events = calendar.scan(primary, Math.max(store.trackingSince, now - 86_400_000L), now + 30L * 86_400_000L);
                store.events.clear(); store.events.addAll(events); store.calendarLabel = primary.label;
                if (store.prefs.getLong("calendar_id", -1) < 0) store.prefs.edit().putLong("calendar_id", primary.id).apply();
                store.lastScan = System.currentTimeMillis(); store.error = "";
                store.handled.entrySet().removeIf(e -> e.getValue() < now - 7L * 86_400_000L);
                store.save();
            } catch (RuntimeException e) { store.error = message(e); }
        }
        if (store.session != null && store.session.phase == AlarmSession.Phase.CONFIRMED) complete();
        if (store.session != null) {
            alignDeadline();
            AlarmSession after = store.session.tick(now);
            if (after != store.session) { store.session = after; monotonicDeadline = 0; store.save(); }
        }
        if (store.session == null) {
            for (WakeEvent e : store.events) {
                if (e.begin <= now && e.begin >= now - 86_400_000L && !store.handled.containsKey(e.key())) {
                    store.session = AlarmSession.start(e); monotonicDeadline = 0; store.save(); break;
                }
            }
        }
        render(now);
        schedule(now);
        latest = new Status(store, sync.status());
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }

    private void alignDeadline() {
        AlarmSession s = store.session;
        if (s == null || s.deadline == 0) { monotonicDeadline = 0; return; }
        if (monotonicDeadline == 0) monotonicDeadline = SystemClock.elapsedRealtime() + s.deadline - System.currentTimeMillis();
        long adjusted = System.currentTimeMillis() + monotonicDeadline - SystemClock.elapsedRealtime();
        if (Math.abs(adjusted - s.deadline) > 1_000)
            store.session = new AlarmSession(s.event, s.phase, adjusted, s.revision);
    }

    private void render(long now) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        AlarmSession s = store.session;
        if (s != null && s.sounds(now)) sound.start(); else sound.stop();
        store.audioError = sound.error();
        if (s != null && s.phase != AlarmSession.Phase.CONFIRMED && s.phase != AlarmSession.Phase.PROVISIONAL) {
            boolean transition = !lastKey.equals(s.event.key()) || lastRevision != s.revision;
            if (transition) {
                nm.cancel(Notifications.ALARM_ID);
                nm.notify(Notifications.ALARM_ID, Notifications.alarm(this, s, true));
            }
        } else nm.cancel(Notifications.ALARM_ID);
        lastKey = s == null ? "" : s.event.key(); lastRevision = s == null ? -1 : s.revision;
        String text = s == null ? (store.error.isEmpty() ? "primary を監視 · 60秒間隔" : store.error) : phaseLabel(s.phase);
        nm.notify(Notifications.MONITOR_ID, Notifications.monitor(this, text));
    }

    private void schedule(long now) {
        try {
            // Separate event and phase timers: overlapping events cannot cancel the recheck.
            long next = 0;
            for (WakeEvent e : store.events) if (e.begin > now && !store.handled.containsKey(e.key())) { next = e.begin; break; }
            scheduler.set(1, next);
            scheduler.set(2, store.session == null ? 0 : store.session.nextBoundary(now));
        } catch (RuntimeException e) { store.error = message(e); }
    }

    static String phaseLabel(AlarmSession.Phase p) {
        switch (p) {
            case RINGING: return "本鳴動";
            case PROVISIONAL: return "仮起床 · 再確認待ち";
            case RECHECK: return "再確認 · もう一度押してください";
            default: return "起床確定";
        }
    }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            if (observing) getContentResolver().unregisterContentObserver(observer);
            if (sound != null) sound.stop();
            if (cpu.isHeld()) cpu.release();
            latest = null; thread.quitSafely();
        });
        super.onDestroy();
    }
}
