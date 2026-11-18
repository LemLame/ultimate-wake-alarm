package jp.waketerminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One synchronously committed snapshot: acknowledgement and pending write cannot diverge. */
final class StateStore {
    final SharedPreferences prefs;
    final Context context;
    AlarmSession session;
    final List<WakeEvent> events = new ArrayList<>();
    final List<WakeEvent> pending = new ArrayList<>();
    final Map<String, Long> handled = new LinkedHashMap<>();
    long trackingSince, lastScan;
    String calendarLabel = "未接続", error = "", writeError = "", audioError = "";

    StateStore(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("wake_terminal", Context.MODE_PRIVATE);
        trackingSince = System.currentTimeMillis() - 60_000;
        String raw = prefs.getString("snapshot", "{}");
        try {
            JSONObject root = new JSONObject(raw);
            trackingSince = root.optLong("trackingSince", trackingSince);
            lastScan = root.optLong("lastScan");
            calendarLabel = root.optString("calendarLabel", calendarLabel);
            readEvents(root.optJSONArray("events"), events);
            readEvents(root.optJSONArray("pending"), pending);
            JSONObject done = root.optJSONObject("handled");
            if (done != null) for (Iterator<String> it = done.keys(); it.hasNext();) {
                String key = it.next(); handled.put(key, done.getLong(key));
            }
            JSONObject s = root.optJSONObject("session");
            if (s != null) {
                long deadline = s.getLong("deadline");
                // Durations use elapsed time within a boot; persisted wall time bridges reboot.
                if (deadline > 0 && root.optInt("boot", -1) == bootCount())
                    deadline = System.currentTimeMillis() + root.getLong("deadlineElapsed") - SystemClock.elapsedRealtime();
                session = new AlarmSession(eventFrom(s.getJSONObject("event")),
                    AlarmSession.Phase.valueOf(s.getString("phase")), deadline, s.getLong("revision"));
                // v1 stored the end of a 3-minute recheck. Preserve its start, shorten to 60s.
                if (root.optInt("schema", 1) < 2 && session.phase == AlarmSession.Phase.RECHECK)
                    session = new AlarmSession(session.event, session.phase,
                        deadline - AlarmSession.WAIT_MS + AlarmSession.CUE_MS, session.revision + 1);
            }
        } catch (JSONException | IllegalArgumentException e) {
            // Do not silently discard a corrupt active alarm snapshot.
            throw new IllegalStateException("保存したアラーム状態を読み込めません", e);
        }
    }

    int bootCount() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }

    void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 2);
            root.put("trackingSince", trackingSince).put("lastScan", lastScan).put("calendarLabel", calendarLabel);
            root.put("events", eventsJson(events)).put("pending", eventsJson(pending));
            JSONObject done = new JSONObject();
            for (Map.Entry<String, Long> e : handled.entrySet()) done.put(e.getKey(), e.getValue());
            root.put("handled", done).put("boot", bootCount());
            if (session != null) {
                root.put("session", new JSONObject().put("event", eventJson(session.event))
                    .put("phase", session.phase.name()).put("deadline", session.deadline).put("revision", session.revision));
                root.put("deadlineElapsed", session.deadline == 0 ? 0 :
                    SystemClock.elapsedRealtime() + session.deadline - System.currentTimeMillis());
            }
            if (!prefs.edit().putString("snapshot", root.toString()).commit())
                throw new IllegalStateException("アラーム状態を保存できません（ストレージを確認してください）");
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    static JSONObject eventJson(WakeEvent e) throws JSONException {
        return new JSONObject().put("id", e.id).put("calendar", e.calendarId).put("begin", e.begin)
            .put("end", e.end).put("title", e.title).put("recurring", e.recurring);
    }
    static WakeEvent eventFrom(JSONObject j) throws JSONException {
        return new WakeEvent(j.getLong("id"), j.getLong("calendar"), j.getLong("begin"),
            j.getLong("end"), j.getString("title"), j.optBoolean("recurring"));
    }
    private static JSONArray eventsJson(List<WakeEvent> list) throws JSONException {
        JSONArray a = new JSONArray(); for (WakeEvent e : list) a.put(eventJson(e)); return a;
    }
    private static void readEvents(JSONArray a, List<WakeEvent> target) throws JSONException {
        if (a != null) for (int i = 0; i < a.length(); i++) target.add(eventFrom(a.getJSONObject(i)));
    }
}
