package jp.waketerminal;

/** One calendar occurrence, identified independently of its mutable title. */
public final class WakeEvent {
    public static final String PREFIX = "⏰起床";
    public final long id, calendarId, begin, end;
    public final String title;
    public final boolean recurring;

    public WakeEvent(long id, long calendarId, long begin, long end, String title, boolean recurring) {
        this.id = id; this.calendarId = calendarId; this.begin = begin; this.end = end;
        this.title = title; this.recurring = recurring;
    }

    public String key() { return id + ":" + begin; }
    public static boolean matches(String title) { return title != null && title.startsWith(PREFIX); }
}
