package jp.waketerminal;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private static final int PICK_RINGTONE = 10, PICK_DOCUMENT = 11, PICK_SYNC_ACCOUNT = 12;
    private static final ExecutorService SOUND_IO = Executors.newSingleThreadExecutor();
    private TextView status, nextTime, nextTitle, details, checks, error, soundLabel, syncDetails, syncNotice;
    private Button start, soundChoice, syncAccess;
    private boolean soundBusy;
    private SharedPreferences prefs;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 2_000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("wake_terminal", MODE_PRIVATE);
        Notifications.create(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(layout); setContentView(scroll); Ui.insets(this, layout, 28);
        TextView brand = Ui.text(this, "WAKE TERMINAL  /  起床端末", 13, Ui.GOLD); Ui.bold(brand); layout.addView(brand);
        TextView title = Ui.text(this, "明日の朝を、\nカレンダーに預ける。", 30, Ui.INK); Ui.bold(title); layout.addView(title);
        status = Ui.text(this, "準備中", 16, Ui.GREEN); layout.addView(status);
        layout.addView(Ui.text(this, "次の起床", 13, Ui.MUTED));
        nextTime = Ui.text(this, "— — : — —", 48, Ui.INK); Ui.bold(nextTime); layout.addView(nextTime);
        nextTitle = Ui.text(this, "", 16, Ui.MUTED); layout.addView(nextTitle);
        details = Ui.text(this, "", 14, Ui.MUTED); layout.addView(details);
        syncDetails = Ui.text(this, "", 14, Ui.MUTED); layout.addView(syncDetails);
        syncNotice = Ui.text(this, "", 14, Ui.GOLD); layout.addView(syncNotice);
        error = Ui.text(this, "", 14, Ui.GOLD); layout.addView(error);
        start = Ui.button(this, "監視を開始 / 権限を許可", true); layout.addView(start);
        start.setOnClickListener(v -> requestPermissionsAndStart());
        syncAccess = Ui.button(this, "Google同期へのアクセスを許可", false); layout.addView(syncAccess);
        syncAccess.setOnClickListener(v -> grantSyncAccess());
        layout.addView(Ui.text(this, "アラーム音", 19, Ui.INK));
        soundLabel = Ui.text(this, "", 14, Ui.MUTED); layout.addView(soundLabel);
        soundChoice = Ui.button(this, "アラーム音を選ぶ", false); layout.addView(soundChoice);
        soundChoice.setOnClickListener(v -> chooseSound());
        layout.addView(Ui.text(this, "端末の準備", 19, Ui.INK));
        checks = Ui.text(this, "", 14, Ui.MUTED); layout.addView(checks);
        add(layout, "監視する Google primary を選択", this::selectCalendar);
        add(layout, "端末の同期設定を開く", () -> open(new Intent(Settings.ACTION_SYNC_SETTINGS)));
        add(layout, "通知・アラーム画面を許可", () -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            else if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager.class).canUseFullScreenIntent())
                open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:" + getPackageName())));
            else open(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.ALARM));
        });
        add(layout, "おやすみモードでアラームを許可", () -> {
            NotificationManager nm = getSystemService(NotificationManager.class);
            open(new Intent(nm.isNotificationPolicyAccessGranted() ? "android.settings.ZEN_MODE_SETTINGS" : Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        });
        add(layout, "電池最適化から除外", () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + getPackageName()))));
        if (Build.VERSION.SDK_INT >= 31 && Build.VERSION.SDK_INT <= 32)
            add(layout, "正確なアラームを許可", () -> open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:" + getPackageName()))));
        layout.addView(Ui.text(this, "「⏰起床」で始まる時刻付き予定が対象です。\n1回押す → 3分待つ → 再確認でもう1回。\n再確認は60秒間鳴り、無応答なら本鳴動へ戻ります。\n数分先の予定は同期が間に合わない場合があります。前夜に登録し、次の起床時刻を確認してください。", 14, Ui.MUTED));
    }
    private void add(LinearLayout layout, String label, Runnable action) {
        Button b = Ui.button(this, label, false); b.setOnClickListener(v -> action.run()); layout.addView(b);
    }
    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "この端末では設定アプリから変更してください", Toast.LENGTH_LONG).show(); }
    }
    private void requestPermissionsAndStart() {
        List<String> needed = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR})
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 1);
        else enable();
    }
    private void enable() {
        if (MonitorService.hasCalendar(this)) {
            prefs.edit().putBoolean("enabled", true).apply();
            MonitorService.start(this, MonitorService.ACTION_SYNC);
        }
        render();
    }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        super.onRequestPermissionsResult(code, names, results);
        if (code == 1) enable();
        render();
    }
    @Override public void onResume() {
        super.onResume();
        Notifications.create(this);
        if (prefs.getBoolean("enabled", false) && MonitorService.hasCalendar(this)) MonitorService.start(this, MonitorService.ACTION_REFRESH);
        handler.post(refresh);
    }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private void render() {
        MonitorService.Status s = MonitorService.latest;
        boolean live = s != null && SystemClock.elapsedRealtime() - s.heartbeat < 90_000;
        status.setText(live ? (s.session == null ? "● カレンダーを監視中" : "● " + MonitorService.phaseLabel(s.session.phase)) : "○ 監視を開始してください");
        status.setTextColor(live ? Ui.GREEN : Ui.GOLD);
        start.setText(live ? "同期を要求して再読み込み" : "監視を開始 / 権限を許可");
        WakeEvent next = s == null || s.events.isEmpty() ? null : s.events.get(0);
        if (s != null && s.session != null) next = s.session.event;
        nextTime.setText(next == null ? "— — : — —" : format(next.begin, "HH:mm"));
        nextTitle.setText(next == null ? "起床イベントを待っています" : format(next.begin, "M月d日（E）") + "  " + next.title);
        details.setText(s == null ? "Googleカレンダーの端末同期を有効にしてください" : "primary: " + s.calendar + "\n端末内の予定取得: "
            + (s.lastScan == 0 ? "未読込" : format(s.lastScan, "M/d HH:mm:ss")) + "  /  起床結果の書込待ち: " + s.pending);
        if (!soundBusy) soundLabel.setText(new SoundSettings(this).label() + "\n選んだ音は次の鳴動から使います。");
        CalendarSync.Status sync = s == null ? null : s.sync;
        syncDetails.setText(sync == null ? "Google同期: 状態未確認" : "Google同期: " + sync.text + "\n同期要求: "
            + (sync.lastRequest == 0 ? "未送信" : format(sync.lastRequest, "M/d HH:mm:ss")) + "\nGoogle側との同期完了時刻: 未確認");
        syncAccess.setVisibility(sync != null && sync.needsAccountAccess ? View.VISIBLE : View.GONE);
        boolean future = false;
        if (s != null) for (WakeEvent e : s.events) if (e.begin > System.currentTimeMillis()) { future = true; break; }
        String notice = sync == null ? "" : sync.warning;
        if (s != null && s.lastScan > 0 && System.currentTimeMillis() - s.lastScan > 120_000)
            notice += "\n端末内の予定取得が2分以上更新されていません。";
        if (live && !future && s.session == null)
            notice += "\n端末内に今後の起床予定がありません。Google側で作成済みなら、まだ届いていない可能性があります。";
        notice += "\n同期要求は即時反映の保証ではありません。数分先に追加した予定は、上の起床時刻への反映を確認してください。";
        syncNotice.setText(notice.trim());
        error.setText(s == null ? "" : s.error); error.setVisibility(s != null && !s.error.isEmpty() ? View.VISIBLE : View.GONE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = nm.getNotificationChannel(Notifications.ALARM);
        boolean notifications = nm.areNotificationsEnabled() && channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
        boolean full = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean dnd;
        try {
            int filter = nm.getCurrentInterruptionFilter();
            dnd = filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
                || (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && Build.VERSION.SDK_INT >= 28 &&
                ((Build.VERSION.SDK_INT >= 30 ? nm.getConsolidatedNotificationPolicy() : nm.getNotificationPolicy())
                    .priorityCategories & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0);
        } catch (SecurityException ex) { dnd = false; }
        checks.setText(check(MonitorService.hasCalendar(this), "カレンダー読取・書込") + "\n" + check(new AlarmScheduler(this).available(), "正確なアラーム")
            + "\n" + check(notifications, "通知（起床アラーム: 高）") + "\n" + check(full, "ロック画面の全画面表示")
            + "\n" + check(dnd, "現在のおやすみモードでアラーム許可") + "\n" + check(battery, "電池最適化の除外"));
        if (live && s.session != null && s.session.phase != AlarmSession.Phase.PROVISIONAL)
            startActivity(new Intent(this, AlarmActivity.class));
    }
    private static String check(boolean good, String label) { return (good ? "✓ " : "要設定 · ") + label; }
    private void chooseSound() {
        new AlertDialog.Builder(this).setTitle("アラーム音を選ぶ")
            .setItems(new String[]{"端末のアラーム音一覧", "音声ファイルから取り込む", "端末の標準音に戻す"}, (dialog, which) -> {
                try {
                    if (which == 0) startActivityForResult(new SoundSettings(this).ringtonePicker(), PICK_RINGTONE);
                    else if (which == 1) startActivityForResult(SoundSettings.documentPicker(), PICK_DOCUMENT);
                    else changeSound(SoundSettings::useDefault);
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(this, "この端末の音選択画面を開けません。音声ファイルから取り込んでください", Toast.LENGTH_LONG).show();
                }
            }).show();
    }
    private interface SoundWork { void run(SoundSettings settings) throws Exception; }
    private void changeSound(SoundWork work) {
        soundBusy = true;
        soundChoice.setEnabled(false); soundLabel.setText("音声を確認・保存しています…");
        SOUND_IO.execute(() -> {
            String failure = null;
            try { work.run(new SoundSettings(getApplicationContext())); }
            catch (Exception ex) { failure = ex.getMessage() == null ? "アラーム音を保存できません" : ex.getMessage(); }
            final String message = failure;
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                soundBusy = false; soundChoice.setEnabled(true); render();
                Toast.makeText(this, message == null ? "アラーム音を保存しました" : message, Toast.LENGTH_LONG).show();
            });
        });
    }
    private void grantSyncAccess() {
        try {
            CalendarRepository.Primary primary = new CalendarRepository(getContentResolver()).select(prefs.getLong("calendar_id", -1));
            Account target = new Account(primary.label, "com.google");
            ArrayList<Account> allowed = new ArrayList<>(); allowed.add(target);
            startActivityForResult(AccountManager.newChooseAccountIntent(target, allowed, new String[]{"com.google"},
                "選択したprimaryのカレンダー同期を促進するために使用します", null, null, null), PICK_SYNC_ACCOUNT);
        } catch (RuntimeException ex) { Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    @Override public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null) return;
        if (code == PICK_RINGTONE) {
            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            changeSound(settings -> settings.selectRingtone(uri));
        } else if (code == PICK_DOCUMENT && data.getData() != null) {
            Uri uri = data.getData(); changeSound(settings -> settings.importAudio(uri));
        } else if (code == PICK_SYNC_ACCOUNT) enable();
    }
    static String format(long timestamp, String pattern) {
        return new SimpleDateFormat(pattern, Locale.JAPAN).format(new Date(timestamp));
    }
    private void selectCalendar() {
        if (!MonitorService.hasCalendar(this)) { requestPermissionsAndStart(); return; }
        try {
            List<CalendarRepository.Primary> list = new CalendarRepository(getContentResolver()).primaries();
            if (list.isEmpty()) { Toast.makeText(this, "同期済みの Google primary がありません", Toast.LENGTH_LONG).show(); return; }
            String[] labels = new String[list.size()];
            for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).label;
            new AlertDialog.Builder(this).setTitle("監視する primary").setItems(labels, (dialog, which) -> {
                prefs.edit().putLong("calendar_id", list.get(which).id).apply(); enable();
            }).show();
        } catch (RuntimeException ex) { Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show(); }
    }
}
