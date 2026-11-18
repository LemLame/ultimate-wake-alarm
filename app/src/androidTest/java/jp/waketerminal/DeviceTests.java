package jp.waketerminal;

import android.app.Activity;
import android.app.Instrumentation;
import android.accounts.Account;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Runs only on an isolated emulator. Uses a temporary local provider calendar, never Google servers. */
public final class DeviceTests extends Instrumentation {
    private Context ctx;
    private long calendarId = -1;
    private int count;
    private boolean realTime;
    private boolean keepState;
    private int originalRinger = -1;
    private AlarmActivity alarmScreen;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        realTime = arguments != null && "true".equals(arguments.getString("realtime"));
        keepState = arguments != null && "true".equals(arguments.getString("keepState"));
        start();
    }
    private void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        count++;
        Bundle b = new Bundle(); b.putString("stream", "PASS " + label + "\n"); sendStatus(0, b);
    }
    private void await(BooleanSupplier condition, long timeout, String label) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        check(condition.getAsBoolean(), label);
    }
    @Override public void onStart() {
        ctx = getTargetContext();
        Bundle result = new Bundle();
        try {
            ctx.stopService(new Intent(ctx, MonitorService.class));
            long stoppedBy = SystemClock.elapsedRealtime() + 5_000;
            while (MonitorService.latest != null && SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(100);
            ctx.getSharedPreferences("wake_terminal", 0).edit().clear().commit();
            new AlarmScheduler(ctx).set(1, 0); new AlarmScheduler(ctx).set(2, 0);
            startActivitySync(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(MonitorService.hasCalendar(ctx), "calendar runtime permissions");
            createCalendar();
            CalendarRepository repository = new CalendarRepository(ctx.getContentResolver());
            CalendarRepository.Primary primary = repository.select(calendarId);
            check(primary.id == calendarId, "Google primary lookup");
            AudioManager audio = ctx.getSystemService(AudioManager.class);
            originalRinger = audio.getRingerMode();
            audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            check(audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT, "silent ringer mode enabled");
            testV11(primary);
            long now = System.currentTimeMillis();
            long id = insertEvent("⏰起床 device test", now + 4_000, false);
            long ignoredId = insertEvent("通常の予定", now + 4_000, false);
            List<WakeEvent> list = repository.scan(primary, now - 1_000, now + 86_400_000);
            check(list.size() == 1 && list.get(0).id == id, "prefix filtering and instance start");
            WakeEvent event = list.get(0);
            StateStore initial = new StateStore(ctx);
            initial.session = AlarmSession.start(event).press(now, event.key(), 1);
            initial.events.clear(); initial.events.add(event); initial.pending.clear(); initial.handled.clear();
            initial.trackingSince = now - 60_000; initial.save();
            StateStore restored = new StateStore(ctx);
            check(restored.session.phase == AlarmSession.Phase.PROVISIONAL, "durable provisional phase");
            check(Math.abs(restored.session.deadline - initial.session.deadline) < 1000, "durable three-minute deadline");
            initial.session = null; initial.save();
            ctx.getSharedPreferences("wake_terminal", 0).edit().putBoolean("enabled", true).putLong("calendar_id", calendarId).commit();
            ctx.startForegroundService(new Intent(ctx, MonitorService.class).setAction(MonitorService.ACTION_REFRESH));
            await(() -> current(AlarmSession.Phase.RINGING), 12_000, "event triggers live service");
            check(System.currentTimeMillis() >= event.begin && System.currentTimeMillis() - event.begin < 2_500,
                "alarm fires within 2.5 seconds of event start");
            check(audio.getStreamVolume(AudioManager.STREAM_ALARM) == audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), "alarm stream at maximum");
            alarmScreen = (AlarmActivity) startActivitySync(new Intent(ctx, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final int[] buttons = {0};
            runOnMainSync(() -> buttons[0] = countButtons(alarmScreen.getWindow().getDecorView()));
            check(buttons[0] == 1, "alarm screen has exactly one button");
            capture("ringing");
            press();
            await(() -> current(AlarmSession.Phase.PROVISIONAL), 3_000, "first press stops in provisional state");
            SystemClock.sleep(350); capture("provisional");
            if (realTime) {
                await(() -> current(AlarmSession.Phase.RECHECK), 185_000, "real three-minute recheck timer");
            } else {
                restartAt(AlarmSession.Phase.PROVISIONAL, System.currentTimeMillis() - 1);
                await(() -> current(AlarmSession.Phase.RECHECK), 5_000, "restore advances expired provisional timer");
            }
            SystemClock.sleep(350); capture("recheck");
            press();
            await(() -> MonitorService.latest != null && MonitorService.latest.session == null, 5_000, "second press confirms and clears active alarm");
            check(title(id).equals("✅起きた"), "CalendarContract writes exact completed title (actual=" + title(id)
                + ", status=" + MonitorService.latest.error + ")");
            check(title(ignoredId).equals("通常の予定"), "unrelated event is unchanged");
            check(new StateStore(ctx).handled.containsKey(event.key()), "confirmation is durably deduplicated");
            long seriesId = insertEvent("⏰起床 recurring test", now + 60_000, true);
            WakeEvent occurrence = null;
            for (WakeEvent e : repository.scan(primary, now, now + 2L * 86_400_000)) if (e.id == seriesId) { occurrence = e; break; }
            check(occurrence != null && occurrence.recurring, "recurring occurrence expansion");
            repository.confirm(occurrence); repository.confirm(occurrence);
            check(title(seriesId).equals("⏰起床 recurring test"), "series master remains unchanged");
            int exceptions;
            try (Cursor c = ctx.getContentResolver().query(Events.CONTENT_URI, new String[]{Events._ID, Events.TITLE},
                Events.ORIGINAL_ID + "=?", new String[]{Long.toString(seriesId)}, null)) {
                exceptions = c.getCount(); c.moveToFirst(); check(c.getString(1).equals("✅起きた"), "single-occurrence completion title");
            }
            check(exceptions == 1, "calendar retry does not duplicate exception");
            long fallbackId = insertEvent("⏰起床 fallback test", System.currentTimeMillis() + 2_000, false);
            MonitorService.start(ctx, MonitorService.ACTION_REFRESH);
            await(() -> current(AlarmSession.Phase.RINGING), 7_000, "second event triggers");
            press(); await(() -> current(AlarmSession.Phase.PROVISIONAL), 3_000, "fallback test provisional phase");
            if (realTime) {
                await(() -> current(AlarmSession.Phase.RECHECK), 185_000, "fallback test real recheck timer");
                long recheckStarted = SystemClock.elapsedRealtime();
                SystemClock.sleep(15_000);
                check(current(AlarmSession.Phase.RECHECK) && MonitorService.latest.session.sounds(System.currentTimeMillis()),
                    "recheck still sounds after old ten-second limit");
                await(() -> current(AlarmSession.Phase.RINGING), 50_000, "no second press returns to full alarm after sixty seconds");
                check(SystemClock.elapsedRealtime() - recheckStarted >= 58_000, "recheck was not shortened below sixty seconds");
            } else {
                restartAt(AlarmSession.Phase.RECHECK, System.currentTimeMillis() - 1);
                await(() -> current(AlarmSession.Phase.RINGING), 5_000, "restore after missed confirmation returns to full alarm");
            }
            check(title(fallbackId).startsWith(WakeEvent.PREFIX), "missed confirmation does not mark calendar completed");
            ctx.stopService(new Intent(ctx, MonitorService.class));
            await(() -> MonitorService.latest == null, 5_000, "service stops for restart test");
            runOnMainSync(() -> new BootReceiver().onReceive(ctx, new Intent(Intent.ACTION_BOOT_COMPLETED)));
            await(() -> current(AlarmSession.Phase.RINGING), 5_000, "boot receiver entry point restores active alarm");
            ctx.startActivity(new Intent(ctx, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1_000);
            result.putString("stream", "PASS: " + count + " device checks (realtime=" + realTime + ")\n");
            if (!keepState) cleanup();
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL after " + count + " checks: " + e + "\n" + android.util.Log.getStackTraceString(e));
            cleanup(); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private boolean current(AlarmSession.Phase phase) {
        return MonitorService.latest != null && MonitorService.latest.session != null && MonitorService.latest.session.phase == phase;
    }
    private void testV11(CalendarRepository.Primary primary) throws Exception {
        SoundSettings sounds = new SoundSettings(ctx);
        Intent picker = sounds.ringtonePicker();
        check(picker.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1) == RingtoneManager.TYPE_ALARM
            && !picker.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true), "picker lists alarm sounds without silent option");
        check(Intent.ACTION_OPEN_DOCUMENT.equals(SoundSettings.documentPicker().getAction())
            && "audio/*".equals(SoundSettings.documentPicker().getType()), "audio document picker uses SAF");
        RingtoneManager ringtones = new RingtoneManager(ctx); ringtones.setType(RingtoneManager.TYPE_ALARM);
        try (Cursor c = ringtones.getCursor()) {
            check(c.getCount() > 0, "device alarm sound list is available");
            sounds.selectRingtone(ringtones.getRingtoneUri(0));
            check(new File(new SoundSettings(ctx).selected().getPath()).isFile(), "device ringtone selection is saved privately");
        }
        File source = new File(ctx.getCacheDir(), "wake-v11-test.wav");
        ByteBuffer wav = ByteBuffer.allocate(44 + 16000).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + 16000).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(16000);
        for (int i = 0; i < 8000; i++) wav.putShort((short)(Math.sin(i * 2 * Math.PI * 880 / 8000) * 4000));
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(wav.array()); }
        sounds.importAudio(Uri.fromFile(source));
        Uri selected = new SoundSettings(ctx).selected();
        check(!selected.equals(Uri.fromFile(source)), "audio import does not depend on the original document URI");
        source.delete();
        MediaPlayer probe = new MediaPlayer();
        try { probe.setDataSource(ctx, selected); probe.prepare(); check(probe.getDuration() >= 900, "imported audio survives original file deletion"); }
        finally { probe.release(); }
        File invalid = new File(ctx.getCacheDir(), "not-audio.txt");
        try (FileOutputStream out = new FileOutputStream(invalid)) { out.write(new byte[]{1,2,3}); }
        boolean rejected = false;
        try { sounds.importAudio(Uri.fromFile(invalid)); } catch (java.io.IOException expected) { rejected = true; }
        finally { invalid.delete(); }
        check(rejected && selected.equals(new SoundSettings(ctx).selected()), "invalid audio preserves the previous selection");
        AlarmAudio playback = new AlarmAudio(ctx);
        try { playback.start(); check(playback.error().isEmpty(), "configured audio is playable by the alarm engine"); }
        finally { playback.stop(); }

        long now = System.currentTimeMillis();
        StateStore legacy = new StateStore(ctx);
        legacy.session = new AlarmSession(new WakeEvent(999, primary.id, now, now + 60_000, "⏰起床 legacy", false),
            AlarmSession.Phase.RECHECK, now + 180_000, 5);
        legacy.save();
        JSONObject root = new JSONObject(ctx.getSharedPreferences("wake_terminal", 0).getString("snapshot", "{}"));
        root.remove("schema");
        ctx.getSharedPreferences("wake_terminal", 0).edit().putString("snapshot", root.toString()).commit();
        StateStore migrated = new StateStore(ctx);
        check(Math.abs(migrated.session.deadline - (now + 60_000)) < 1000, "v1 recheck migrates to a sixty-second deadline");
        migrated.save();
        check(Math.abs(new StateStore(ctx).session.deadline - migrated.session.deadline) < 1000, "migration does not shorten the deadline twice");
        ctx.getSharedPreferences("wake_terminal", 0).edit().remove("snapshot").commit();

        android.content.SharedPreferences syncPrefs = ctx.getSharedPreferences("sync_test", 0);
        syncPrefs.edit().clear().commit();
        FakeSync backend = new FakeSync();
        CalendarSync sync = new CalendarSync(syncPrefs, backend);
        sync.refresh(primary, false, now, 100_000);
        check(sync.status().needsAccountAccess && backend.calls == 0, "invisible account warns without claiming a sync request");
        backend.visible = true;
        sync.refresh(primary, false, now, 100_000);
        check(backend.calls == 0 && sync.status().warning.contains("Google"), "missing sync adapter produces a warning");
        backend.adapter = true; backend.auto = true;
        sync.refresh(primary, false, now, 100_000);
        check(backend.calls == 1 && !backend.manual && primary.label.equals(backend.account.name), "automatic sync targets only selected account");
        sync.refresh(primary, false, now + 59_999, 159_999);
        check(backend.calls == 1, "observer activity cannot exceed sixty-second sync request rate");
        sync.refresh(primary, false, now + 60_000, 160_000);
        check(backend.calls == 2, "periodic sync request repeats after sixty seconds");
        backend.auto = false;
        sync.refresh(primary, false, now + 120_000, 220_000);
        check(backend.calls == 2 && !sync.status().warning.isEmpty(), "automatic requests respect disabled auto-sync");
        sync.refresh(primary, true, now + 120_001, 220_001);
        check(backend.calls == 3 && backend.manual, "explicit refresh may request manual sync");
        sync.refresh(primary, true, now + 120_002, 220_002);
        check(backend.calls == 3, "manual sync button is debounced");
        check(sync.status().lastRequest == now + 120_001, "status records request time rather than cloud success time");
        backend.auto = true; backend.active = true;
        sync.refresh(primary, false, now + 180_000, 280_000);
        check(backend.calls == 3 && sync.status().text.contains("処理中"), "active sync is observed without enqueueing duplicates");
        syncPrefs.edit().clear().commit();
    }
    private static final class FakeSync implements CalendarSync.Backend {
        boolean visible, adapter, auto, active, manual;
        int calls; Account account;
        @Override public boolean visible(Account account) { return visible; }
        @Override public boolean adapterAvailable() { return adapter; }
        @Override public boolean automatic(Account account) { return auto; }
        @Override public boolean active(Account account) { return active; }
        @Override public boolean pending(Account account) { return false; }
        @Override public void request(Account account, boolean manual) { calls++; this.account = account; this.manual = manual; }
    }
    private void press() {
        if (alarmScreen != null && !alarmScreen.isFinishing()) {
            SystemClock.sleep(350);
            runOnMainSync(() -> clickButton(alarmScreen.getWindow().getDecorView()));
            return;
        }
        AlarmSession s = MonitorService.latest.session;
        ctx.startForegroundService(new Intent(ctx, MonitorService.class).setAction(MonitorService.ACTION_PRESS)
            .putExtra("key", s.event.key()).putExtra("revision", s.revision));
    }
    private int countButtons(View v) {
        if (v instanceof Button) return 1;
        int n = 0;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) n += countButtons(((ViewGroup)v).getChildAt(i));
        return n;
    }
    private void clickButton(View v) {
        if (v instanceof Button) { v.performClick(); return; }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) clickButton(((ViewGroup)v).getChildAt(i));
    }
    private void capture(String name) {
        Bitmap shot = getUiAutomation().takeScreenshot();
        if (shot == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getExternalFilesDir(null), name + ".png"))) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
        finally { shot.recycle(); }
    }
    private void restartAt(AlarmSession.Phase phase, long deadline) {
        ctx.stopService(new Intent(ctx, MonitorService.class));
        await(() -> MonitorService.latest == null, 5_000, "stop before deadline restoration");
        StateStore s = new StateStore(ctx);
        s.session = new AlarmSession(s.session.event, phase, deadline, s.session.revision + 1); s.save();
        MonitorService.start(ctx, MonitorService.ACTION_REFRESH);
    }
    private void createCalendar() {
        ContentValues c = new ContentValues();
        c.put(Calendars.ACCOUNT_NAME, "wake-terminal-device-test@invalid.example"); c.put(Calendars.ACCOUNT_TYPE, "com.google");
        c.put(Calendars.NAME, "WakeTerminal test"); c.put(Calendars.CALENDAR_DISPLAY_NAME, "WakeTerminal test");
        c.put(Calendars.OWNER_ACCOUNT, "wake-terminal-device-test@invalid.example");
        c.put(Calendars.CALENDAR_COLOR, 0xff00ff00); c.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
        c.put(Calendars.VISIBLE, 1); c.put(Calendars.SYNC_EVENTS, 1); c.put(Calendars.CALENDAR_TIME_ZONE, "Asia/Tokyo");
        c.put(Calendars.IS_PRIMARY, 1);
        Uri uri = Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, c.getAsString(Calendars.ACCOUNT_NAME))
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, "com.google").build();
        calendarId = ContentUris.parseId(ctx.getContentResolver().insert(uri, c));
        check(calendarId > 0, "temporary provider calendar created");
    }
    private long insertEvent(String title, long begin, boolean recurring) {
        ContentValues e = new ContentValues(); e.put(Events.CALENDAR_ID, calendarId); e.put(Events.TITLE, title);
        e.put(Events.DTSTART, begin); e.put(Events.EVENT_TIMEZONE, "Asia/Tokyo");
        e.put(Events.STATUS, Events.STATUS_CONFIRMED);
        if (recurring) { e.put(Events.RRULE, "FREQ=DAILY;COUNT=2"); e.put(Events.DURATION, "PT60S"); }
        else e.put(Events.DTEND, begin + 60_000);
        return ContentUris.parseId(ctx.getContentResolver().insert(Events.CONTENT_URI, e));
    }
    private String title(long id) {
        try (Cursor c = ctx.getContentResolver().query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
            new String[]{Events.TITLE}, null, null, null)) { if (!c.moveToFirst()) throw new AssertionError("missing event"); return c.getString(0); }
    }
    private void cleanup() {
        try {
            ctx.stopService(new Intent(ctx, MonitorService.class));
            SystemClock.sleep(300);
            new AlarmScheduler(ctx).set(1, 0); new AlarmScheduler(ctx).set(2, 0);
            if (calendarId > 0) ctx.getContentResolver().delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), null, null);
            ctx.getSharedPreferences("wake_terminal", 0).edit().clear().commit();
            if (originalRinger >= 0) ctx.getSystemService(AudioManager.class).setRingerMode(originalRinger);
        } catch (RuntimeException ignored) { }
    }
}
