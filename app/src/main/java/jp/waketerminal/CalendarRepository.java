package jp.waketerminal;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import java.util.ArrayList;
import java.util.List;

final class CalendarRepository {
    static final class Primary {
        final long id; final String label;
        Primary(long id, String label) { this.id = id; this.label = label; }
    }
    private final ContentResolver resolver;
    CalendarRepository(ContentResolver resolver) { this.resolver = resolver; }

    List<Primary> primaries() {
        List<Primary> result = new ArrayList<>();
        String selection = Calendars.ACCOUNT_TYPE + "=? AND " + Calendars.IS_PRIMARY + "=1 AND "
            + Calendars.SYNC_EVENTS + "=1 AND " + Calendars.CALENDAR_ACCESS_LEVEL + ">=?";
        try (Cursor c = resolver.query(Calendars.CONTENT_URI,
            new String[]{Calendars._ID, Calendars.ACCOUNT_NAME}, selection,
            new String[]{"com.google", Integer.toString(Calendars.CAL_ACCESS_CONTRIBUTOR)}, Calendars._ID + " ASC")) {
            if (c == null) throw new IllegalStateException("カレンダープロバイダーに接続できません");
            while (c.moveToNext()) result.add(new Primary(c.getLong(0), c.getString(1)));
        }
        return result;
    }

    Primary select(long selected) {
        List<Primary> list = primaries();
        for (Primary p : list) if (p.id == selected) return p;
        if (selected >= 0) throw new IllegalStateException("選択した primary がありません。Googleアカウントと同期設定を確認してください");
        if (list.size() == 1) return list.get(0);
        if (list.isEmpty()) throw new IllegalStateException("書込可能な Google primary がありません。Googleカレンダーの同期を有効にしてください");
        throw new IllegalStateException("Googleアカウントが複数あります。監視する primary を選んでください");
    }

    List<WakeEvent> scan(Primary calendar, long from, long to) {
        Uri.Builder uri = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, from); ContentUris.appendId(uri, to);
        String[] projection = {Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.RRULE, Instances.RDATE};
        List<WakeEvent> result = new ArrayList<>();
        String where = Instances.CALENDAR_ID + "=? AND " + Instances.ALL_DAY + "=0 AND "
            + Events.DELETED + "=0 AND (" + Instances.STATUS + " IS NULL OR " + Instances.STATUS + "!=?) AND " + Instances.TITLE + " LIKE ?";
        try (Cursor c = resolver.query(uri.build(), projection, where,
            new String[]{Long.toString(calendar.id), Integer.toString(Events.STATUS_CANCELED), WakeEvent.PREFIX + "%"},
            Instances.BEGIN + " ASC, " + Instances.EVENT_ID + " ASC")) {
            if (c == null) throw new IllegalStateException("カレンダーイベントの取得に失敗しました");
            while (c.moveToNext()) {
                String title = c.getString(3);
                if (WakeEvent.matches(title) && c.getLong(1) >= from)
                    result.add(new WakeEvent(c.getLong(0), calendar.id, c.getLong(1), c.getLong(2), title,
                        (!c.isNull(4) && !c.getString(4).isEmpty()) || (!c.isNull(5) && !c.getString(5).isEmpty())));
            }
        }
        return result;
    }

    void confirm(WakeEvent event) {
        ContentValues values = new ContentValues();
        values.put(Events.TITLE, "✅起きた");
        if (event.recurring) {
            // Update an existing exception on retry, or create one occurrence only.
            String where = Events.ORIGINAL_ID + "=? AND " + Events.ORIGINAL_INSTANCE_TIME + "=?";
            String[] args = {Long.toString(event.id), Long.toString(event.begin)};
            try (Cursor c = resolver.query(Events.CONTENT_URI, new String[]{Events._ID}, where, args, null)) {
                if (c == null) throw new IllegalStateException("繰返し予定を確認できません");
                if (c.moveToFirst()) {
                    if (resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, c.getLong(0)), values, null, null) != 1)
                        throw new IllegalStateException("起床結果を書き込めません");
                    return;
                }
            }
            values.put(Events.ORIGINAL_INSTANCE_TIME, event.begin);
            if (resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, event.id), values) == null)
                throw new IllegalStateException("繰返し予定の起床結果を書き込めません");
        } else {
            if (resolver.update(Events.CONTENT_URI, values,
                Events._ID + "=? AND " + Events.CALENDAR_ID + "=? AND " + Events.DELETED + "=0",
                new String[]{Long.toString(event.id), Long.toString(event.calendarId)}) != 1)
                throw new IllegalStateException("起床済みイベントが見つかりません（削除・同期状態を確認）");
        }
        // CalendarContract marks the row dirty; the Google sync adapter uploads it.
        // No sync-adapter-only fields, OAuth tokens, or network control plane are used.
    }
}
