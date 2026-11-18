package jp.waketerminal;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncAdapterType;
import android.os.Bundle;
import android.provider.CalendarContract;

/** Best-effort requests to Android's existing calendar sync adapter; never claims cloud freshness. */
final class CalendarSync {
    static final long INTERVAL_MS = 60_000;
    interface Backend {
        boolean visible(Account account);
        boolean adapterAvailable();
        boolean automatic(Account account);
        boolean active(Account account);
        boolean pending(Account account);
        void request(Account account, boolean manual);
    }
    static final class Status {
        final long lastRequest;
        final boolean needsAccountAccess;
        final String text, warning;
        Status(long lastRequest, boolean needsAccountAccess, String text, String warning) {
            this.lastRequest = lastRequest; this.needsAccountAccess = needsAccountAccess;
            this.text = text; this.warning = warning;
        }
    }
    private final SharedPreferences prefs;
    private final Backend backend;
    private String lastAccount = "";
    private long lastAttemptElapsed = -1;
    private Status status;

    CalendarSync(Context context, SharedPreferences prefs) { this(prefs, new AndroidBackend(context)); }
    CalendarSync(SharedPreferences prefs, Backend backend) { this.prefs = prefs; this.backend = backend; }
    Status status() { return status; }

    void refresh(CalendarRepository.Primary primary, boolean manual, long now, long elapsed) {
        Account account = new Account(primary.label, "com.google");
        long requested = prefs.getString("sync_account", "").equals(primary.label) ? prefs.getLong("sync_requested_at", 0) : 0;
        try {
            if (!backend.visible(account)) {
                status = new Status(requested, true, "アカウントへのアクセス未許可",
                    "同期促進には「Google同期へのアクセスを許可」を押してください。端末内の予定監視は継続します。"); return;
            }
            if (!backend.adapterAvailable()) {
                status = new Status(requested, false, "同期アダプターが見つかりません",
                    "Googleカレンダーのインストール・アカウント同期設定を確認してください。"); return;
            }
            boolean auto = backend.automatic(account), active = backend.active(account), pending = backend.pending(account);
            String warning = auto ? "" : "端末または対象アカウントの自動同期がオフです。設定で有効にしてください。";
            boolean same = lastAccount.equals(primary.label);
            boolean due = !same || lastAttemptElapsed < 0 || elapsed - lastAttemptElapsed >= INTERVAL_MS;
            // A service restart should not produce another automatic request immediately.
            if (lastAttemptElapsed < 0 && requested > 0 && now >= requested && now - requested < INTERVAL_MS) due = false;
            boolean debounced = same && lastAttemptElapsed >= 0 && elapsed - lastAttemptElapsed < 5_000;
            if (!debounced && ((manual) || (auto && !active && !pending && due))) {
                lastAccount = primary.label; lastAttemptElapsed = elapsed;
                backend.request(account, manual);
                requested = now;
                prefs.edit().putString("sync_account", primary.label).putLong("sync_requested_at", requested).apply();
            }
            status = new Status(requested, false, active ? "同期処理中" : pending ? "同期の開始待ち" : "同期要求を送信可能（完了は未確認）", warning);
        } catch (RuntimeException e) {
            status = new Status(requested, false, "同期状態を確認できません",
                "同期要求または状態取得に失敗: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static final class AndroidBackend implements Backend {
        private final Context context;
        AndroidBackend(Context context) { this.context = context; }
        @Override public boolean visible(Account target) {
            for (Account a : AccountManager.get(context).getAccountsByType("com.google")) if (a.equals(target)) return true;
            return false;
        }
        @Override public boolean adapterAvailable() {
            for (SyncAdapterType adapter : ContentResolver.getSyncAdapterTypes())
                if (CalendarContract.AUTHORITY.equals(adapter.authority) && "com.google".equals(adapter.accountType)) return true;
            return false;
        }
        @Override public boolean automatic(Account a) {
            return ContentResolver.getMasterSyncAutomatically() && ContentResolver.getSyncAutomatically(a, CalendarContract.AUTHORITY);
        }
        @Override public boolean active(Account a) { return ContentResolver.isSyncActive(a, CalendarContract.AUTHORITY); }
        @Override public boolean pending(Account a) { return ContentResolver.isSyncPending(a, CalendarContract.AUTHORITY); }
        @Override public void request(Account a, boolean manual) {
            Bundle extras = new Bundle();
            if (manual) {
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            }
            ContentResolver.requestSync(a, CalendarContract.AUTHORITY, extras);
        }
    }
}
