package jp.waketerminal;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** The alarm screen intentionally exposes exactly one action. */
public final class AlarmActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView clock, title, detail;
    private Button awake;
    private boolean submitted;
    private long renderedRevision = -1;
    private String renderedKey = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 250); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getSystemService(KeyguardManager.class).requestDismissKeyguard(this, null);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER); layout.setBackgroundColor(Ui.BG);
        setContentView(layout); Ui.insets(this, layout, 28);
        TextView brand = Ui.text(this, "WAKE TERMINAL", 14, Ui.GOLD); brand.setGravity(Gravity.CENTER); layout.addView(brand);
        clock = Ui.text(this, "", 60, Ui.INK); Ui.bold(clock); clock.setGravity(Gravity.CENTER); layout.addView(clock);
        title = Ui.text(this, "起きる時間です", 28, Ui.INK); Ui.bold(title); title.setGravity(Gravity.CENTER); layout.addView(title);
        detail = Ui.text(this, "", 16, Ui.MUTED); detail.setGravity(Gravity.CENTER); layout.addView(detail);
        awake = Ui.button(this, "起きた", true); awake.setTextSize(36); awake.setMinHeight(Ui.dp(this, 140)); layout.addView(awake);
        awake.setOnClickListener(v -> {
            MonitorService.Status current = MonitorService.latest;
            if (submitted || current == null || current.session == null || current.session.phase == AlarmSession.Phase.PROVISIONAL) return;
            submitted = true; awake.setEnabled(false);
            startForegroundService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_PRESS)
                .putExtra("key", renderedKey).putExtra("revision", renderedRevision));
        });
    }
    @Override public void onResume() {
        super.onResume(); MonitorService.start(this, MonitorService.ACTION_REFRESH); handler.post(refresh);
    }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void render() {
        MonitorService.Status status = MonitorService.latest;
        if (status == null) { detail.setText("アラームを復元しています…"); return; }
        AlarmSession s = status.session;
        if (s == null || s.phase == AlarmSession.Phase.CONFIRMED) { finish(); return; }
        if (renderedRevision != s.revision || !renderedKey.equals(s.event.key())) {
            submitted = false; renderedRevision = s.revision; renderedKey = s.event.key();
        }
        clock.setText(MainActivity.format(System.currentTimeMillis(), "HH:mm"));
        awake.setEnabled(!submitted && s.phase != AlarmSession.Phase.PROVISIONAL);
        awake.setAlpha(awake.isEnabled() ? 1f : .42f);
        if (s.phase == AlarmSession.Phase.PROVISIONAL) {
            title.setText("仮起床しました");
            detail.setText("3分後に、もう一度確認します\n再確認まで " + remaining(s.deadline));
        } else if (s.phase == AlarmSession.Phase.RECHECK) {
            title.setText("もう、起きていますか？");
            detail.setText("もう一度「起きた」で確定\n本鳴動に戻るまで " + remaining(s.deadline));
        } else {
            title.setText("起きる時間です"); detail.setText(s.event.title + "\n「起きた」で仮起床します");
        }
        if (!status.error.isEmpty()) detail.append("\n" + status.error);
    }
    private static String remaining(long deadline) {
        long seconds = Math.max(0, (deadline - System.currentTimeMillis() + 999) / 1000);
        return String.format(Locale.JAPAN, "%d:%02d", seconds / 60, seconds % 60);
    }
    @Override public void onBackPressed() { /* Only the two-stage action acknowledges an alarm. */ }
}
