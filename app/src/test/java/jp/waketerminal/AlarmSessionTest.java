package jp.waketerminal;

/** Dependency-free executable tests; run scripts/test.ps1. */
public final class AlarmSessionTest {
    private static int checks;
    private static final long T = 1_000_000;
    private static final WakeEvent EVENT = new WakeEvent(42, 7, T, T + 60_000, "⏰起床 テスト", false);
    private static void check(boolean result, String description) {
        checks++;
        if (!result) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        AlarmSession ring = AlarmSession.start(EVENT);
        check(ring.sounds(T), "initial alarm loops");
        AlarmSession provisional = ring.press(T, EVENT.key(), ring.revision);
        check(provisional.phase == AlarmSession.Phase.PROVISIONAL, "first press is provisional");
        check(!provisional.sounds(T), "first press silences audio");
        check(provisional.deadline == T + 180_000, "recheck is exactly three minutes later");
        check(provisional.press(T + 1, EVENT.key(), ring.revision) == provisional, "queued duplicate is ignored");
        check(provisional.press(T + 100, EVENT.key(), provisional.revision) == provisional, "early second press is ignored");
        check(provisional.tick(T + 179_999) == provisional, "no early recheck");
        AlarmSession recheck = provisional.tick(T + 180_000);
        check(recheck.phase == AlarmSession.Phase.RECHECK, "recheck starts on boundary");
        check(recheck.sounds(T + 180_000), "recheck has sound");
        check(recheck.sounds(T + 239_999), "cue sounds throughout all sixty seconds");
        check(recheck.sounds(T + 190_000), "no silent gap after the old ten-second boundary");
        check(recheck.nextBoundary(T + 180_000) == T + 240_000, "schedule fallback directly after sixty-second cue");
        check(recheck.nextBoundary(T + 230_000) == T + 240_000, "fallback deadline does not slide");
        check(recheck.tick(T + 239_999) == recheck, "second confirmation window remains open");
        AlarmSession confirmed = recheck.press(T + 200_000, EVENT.key(), recheck.revision);
        check(confirmed.phase == AlarmSession.Phase.CONFIRMED, "second press confirms");
        check(!confirmed.sounds(T + 200_000), "confirmation stops sound");
        check(confirmed.press(T + 200_001, EVENT.key(), confirmed.revision) == confirmed, "confirmation is idempotent");
        AlarmSession fallback = recheck.tick(T + 240_000);
        check(fallback.phase == AlarmSession.Phase.RINGING && fallback.sounds(T + 240_000), "four-minute total returns to full alarm without silence");
        check(recheck.press(T + 240_000, EVENT.key(), recheck.revision).phase == AlarmSession.Phase.RINGING,
            "late stale confirmation cannot silence full alarm");
        check(fallback.press(T + 240_001, EVENT.key(), fallback.revision).phase == AlarmSession.Phase.PROVISIONAL,
            "fallback requires two fresh stages");
        check(provisional.tick(T + 700_000).phase == AlarmSession.Phase.RINGING, "restart after both deadlines rings immediately");
        AlarmSession restored = new AlarmSession(EVENT, provisional.phase, provisional.deadline, provisional.revision);
        check(restored.tick(T + 181_000).phase == AlarmSession.Phase.RECHECK, "restart during recheck preserves phase");
        check(restored.tick(T + 239_999).sounds(T + 239_999), "late restoration preserves last second of recheck audio");
        check(restored.tick(T + 240_000).phase == AlarmSession.Phase.RINGING, "restoration at four-minute boundary rings fully");
        check(ring.press(T, "99:0", ring.revision) == ring, "other occurrence cannot acknowledge");
        check(ring.press(T, EVENT.key(), -1) == ring, "invalid token cannot acknowledge");
        check(WakeEvent.matches("⏰起床 テスト"), "wake prefix matches");
        check(WakeEvent.matches("⏰起床"), "bare prefix matches");
        check(!WakeEvent.matches("予定 ⏰起床"), "embedded prefix is ignored");
        check(!WakeEvent.matches(null) && !WakeEvent.matches("✅起きた"), "completed and missing titles are ignored");
        WakeEvent nextOccurrence = new WakeEvent(42, 7, T + 86_400_000, T + 86_460_000, EVENT.title, true);
        check(!EVENT.key().equals(nextOccurrence.key()), "recurring occurrences have independent acknowledgement keys");
        System.out.println("PASS: " + checks + " state-machine checks");
    }
}
