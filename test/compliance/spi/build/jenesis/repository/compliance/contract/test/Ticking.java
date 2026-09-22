package build.jenesis.repository.compliance.contract.test;

import module java.base;

/**
 * A clock the test moves by hand - the {@code SignalContext.clock()} seam every feed takes its windows from in
 * production, so a suite reaches "this answer is now older than the feed says it may be" in one statement instead of
 * in six hours. Shared by the suites in this package rather than re-declared in each: three private copies of a
 * mutable clock is three chances for one of them to be subtly different from the one the assertion assumes.
 */
final class Ticking extends Clock {

    private volatile Instant instant;

    Ticking() {
        this(Instant.parse("2026-08-15T09:00:00Z"));
    }

    Ticking(Instant from) {
        this.instant = Objects.requireNonNull(from, "from");
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    void advance(Duration by) {
        instant = instant.plus(by);
    }
}
