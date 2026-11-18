package jp.waketerminal;

/** Pure, immutable state machine. Revision tokens prevent a queued double tap from confirming. */
public final class AlarmSession {
    public static final long WAIT_MS = 180_000L;
    public static final long CUE_MS = 60_000L;
    public enum Phase { RINGING, PROVISIONAL, RECHECK, CONFIRMED }
    public final WakeEvent event;
    public final Phase phase;
    public final long deadline, revision;

    public AlarmSession(WakeEvent event, Phase phase, long deadline, long revision) {
        this.event = event; this.phase = phase; this.deadline = deadline; this.revision = revision;
    }

    public static AlarmSession start(WakeEvent event) {
        return new AlarmSession(event, Phase.RINGING, 0, 1);
    }

    public AlarmSession tick(long now) {
        if (phase == Phase.PROVISIONAL && now >= deadline) {
            if (now >= deadline + CUE_MS) return new AlarmSession(event, Phase.RINGING, 0, revision + 2);
            return new AlarmSession(event, Phase.RECHECK, deadline + CUE_MS, revision + 1);
        }
        if (phase == Phase.RECHECK && now >= deadline)
            return new AlarmSession(event, Phase.RINGING, 0, revision + 1);
        return this;
    }

    public AlarmSession press(long now, String key, long expectedRevision) {
        AlarmSession current = tick(now);
        if (!event.key().equals(key) || current.revision != expectedRevision) return current;
        if (current.phase == Phase.RINGING)
            return new AlarmSession(event, Phase.PROVISIONAL, now + WAIT_MS, revision + 1);
        if (current.phase == Phase.RECHECK)
            return new AlarmSession(event, Phase.CONFIRMED, 0, revision + 1);
        return current;
    }

    public boolean sounds(long now) {
        return phase == Phase.RINGING || (phase == Phase.RECHECK && now < deadline);
    }

    public long nextBoundary(long now) {
        if (phase == Phase.PROVISIONAL) return deadline;
        if (phase == Phase.RECHECK) return deadline;
        return 0;
    }
}
