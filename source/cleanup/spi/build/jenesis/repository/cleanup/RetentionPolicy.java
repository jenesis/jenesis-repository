package build.jenesis.repository.cleanup;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * A retention rule over the published versions of each coordinate. A version is retained only if it is among the
 * {@code keepLast} newest AND within {@code maxAge} (and within {@code prereleaseExpiry} when it is a prerelease) AND
 * downloaded within {@code notDownloadedFor}; anything else is evicted - except the single newest version of a
 * coordinate, which is always kept so a cleanup never empties a coordinate. A {@code keepLast} of 0 disables the
 * count cap and a {@code null} duration disables that rule, so the dials compose: keep-last, max-age, prerelease
 * expiry, and not-downloaded-for (which evicts cold versions, the criterion that keeps a proxy cache lean). A
 * {@link Release#pinned() pinned} version is never evicted, whatever the rules say. The plan is computed, not
 * applied, so it can be previewed first.
 */
public final class RetentionPolicy {

    // Newest-first, reused across every coordinate of every plan rather than reallocated inside the grouping loop
    // (a large repository sorts one version list per coordinate, so the comparator would otherwise be minted per group).
    private static final Comparator<Release> BY_PUBLISHED_DESCENDING = Comparator.comparing(Release::published).reversed();

    private final int keepLast;
    private final Duration maxAge;
    private final Duration prereleaseExpiry;
    private final Duration notDownloadedFor;

    public RetentionPolicy(int keepLast) {
        this(keepLast, null, null, null);
    }

    private RetentionPolicy(int keepLast, Duration maxAge, Duration prereleaseExpiry, Duration notDownloadedFor) {
        if (keepLast < 0) {
            throw new IllegalArgumentException("keepLast must not be negative: " + keepLast);
        }
        // A zero or negative duration would invert the rule: every past publish is "older" than PT-1H or PT0S, so
        // one mistyped dial would mass-delete everything but each coordinate's newest version on the next sweep.
        // A deletion policy fails loudly at construction, never at sweep time.
        this.keepLast = keepLast;
        this.maxAge = positive("maxAge", maxAge);
        this.prereleaseExpiry = positive("prereleaseExpiry", prereleaseExpiry);
        this.notDownloadedFor = positive("notDownloadedFor", notDownloadedFor);
    }

    private static Duration positive(String name, Duration duration) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(name + " must be a positive duration, not " + duration
                    + " (leave it unset to disable the rule)");
        }
        return duration;
    }

    /** Build a policy from the retention settings ({@code keep-last}, {@code max-age}, {@code prerelease-expiry},
     *  {@code not-downloaded-for}) read through {@code config} - a property/setting accessor returning {@code null}
     *  when unset; an unset or blank key disables its rule. */
    public static RetentionPolicy fromConfig(UnaryOperator<String> config) {
        return parse(config.apply("keep-last"), config.apply("max-age"), config.apply("prerelease-expiry"),
                config.apply("not-downloaded-for"));
    }

    /**
     * The one parse of the four dials as an operator writes them - the deployment default, the scheduled sweep, the
     * API, the console and the stored per-repository policy all come through here, so they cannot disagree about a
     * value. An unset or blank dial disables its rule; a duration is the deployment's one grammar ({@code P30D},
     * {@code 30d}, {@code PT12H}); a zero or negative one is refused here, at the operator's desk, and never at sweep
     * time (see the constructor). A malformed value is an {@link IllegalArgumentException} naming it.
     */
    public static RetentionPolicy parse(String keepLast, String maxAge, String prereleaseExpiry,
                                        String notDownloadedFor) {
        return parse(keepLast == null || keepLast.isBlank() ? 0 : Integer.parseInt(keepLast.trim()),
                maxAge, prereleaseExpiry, notDownloadedFor);
    }

    /** {@link #parse(String, String, String, String)} with the count already a number. */
    public static RetentionPolicy parse(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
        RetentionPolicy policy = new RetentionPolicy(keepLast);
        if (maxAge != null && !maxAge.isBlank()) {
            policy = policy.maxAge(Durations.parse(maxAge));
        }
        if (prereleaseExpiry != null && !prereleaseExpiry.isBlank()) {
            policy = policy.prereleaseExpiry(Durations.parse(prereleaseExpiry));
        }
        if (notDownloadedFor != null && !notDownloadedFor.isBlank()) {
            policy = policy.notDownloadedFor(Durations.parse(notDownloadedFor));
        }
        return policy;
    }

    public RetentionPolicy maxAge(Duration maxAge) {
        return new RetentionPolicy(keepLast, maxAge, prereleaseExpiry, notDownloadedFor);
    }

    public RetentionPolicy prereleaseExpiry(Duration prereleaseExpiry) {
        return new RetentionPolicy(keepLast, maxAge, prereleaseExpiry, notDownloadedFor);
    }

    public RetentionPolicy notDownloadedFor(Duration notDownloadedFor) {
        return new RetentionPolicy(keepLast, maxAge, prereleaseExpiry, notDownloadedFor);
    }

    public int keepLast() {
        return keepLast;
    }

    /** The age cap, or {@code null} when none. */
    public Duration maxAge() {
        return maxAge;
    }

    /** The prerelease age cap, or {@code null} when none. */
    public Duration prereleaseExpiry() {
        return prereleaseExpiry;
    }

    /** The cold-version cap (evict if not downloaded within this), or {@code null} when none. */
    public Duration notDownloadedFor() {
        return notDownloadedFor;
    }

    public CleanupPlan plan(Collection<Release> releases, Instant now) {
        // Grouped by ecosystem AND coordinate: two ecosystems may publish the same coordinate string (an npm
        // package and a crate both named "foo"), and each ecosystem's version list is judged independently -
        // the same groups the store inventory's walk-ordered stream delivers, so the buffered and the streaming
        // plan cannot drift.
        Map<List<String>, List<Release>> byCoordinate = new LinkedHashMap<>();
        for (Release release : releases) {
            byCoordinate.computeIfAbsent(Arrays.asList(release.ecosystem(), release.coordinate()),
                    _ -> new ArrayList<>()).add(release);
        }
        List<CleanupPlan.Eviction> evictions = new ArrayList<>();
        for (List<Release> versions : byCoordinate.values()) {
            judge(versions, now, evictions);
        }
        return new CleanupPlan(List.copyOf(evictions));
    }

    /** Judge the versions of ONE coordinate - the group unit every rule is defined over: newest-first, the single
     *  newest always kept (a cleanup never empties a coordinate), a pinned version never evicted, everything else
     *  condemned by the first rule that names it. Judging any <em>subset</em> of a coordinate's versions is
     *  provably conservative: a release's rank in a subset never exceeds its rank in the full list (keep-last fires
     *  less), the age rules are per-release, and the subset's newest is protected on top of the full list's - so a
     *  streamed group split by a crash-resume under-evicts for one pass and converges on the next, never the
     *  reverse. Sorts {@code versions} in place. */
    private void judge(List<Release> versions, Instant now, List<CleanupPlan.Eviction> evictions) {
        versions.sort(BY_PUBLISHED_DESCENDING);
        for (int index = 1; index < versions.size(); index++) {
            Release release = versions.get(index);
            if (release.pinned()) {
                continue;
            }
            String reason = reason(release, index, now);
            if (reason != null) {
                evictions.add(new CleanupPlan.Eviction(release, reason));
            }
        }
    }

    /** A streaming evaluation of this policy over a release enumeration delivered grouped by coordinate (the
     *  {@link RepositoryInventory#releases(RepositoryInventory.ReleaseVisitor)} contract): buffers only the current
     *  coordinate's versions - never the repository's whole release list - and hands each completed group's
     *  evictions to {@code sink} as soon as the group's boundary passes, so an applying sweep evicts while the
     *  enumeration flows. Call {@link Planner#finish()} after the last release to judge the final group. */
    public Planner planner(Instant now, EvictionSink sink) {
        return new Planner(now, sink);
    }

    /** Receives each eviction a {@link Planner} condemns - a plan collects it, a sweep applies it, so the receiver
     *  is allowed the store I/O an eviction does. */
    @FunctionalInterface
    public interface EvictionSink {
        void accept(CleanupPlan.Eviction eviction) throws IOException;
    }

    /** The one-coordinate-at-a-time state of a streaming {@link #planner plan}: {@link #offer} detects the group
     *  boundary (the ecosystem or coordinate changes) and judges the completed group through the same rules the
     *  buffered {@link #plan} applies - one judgment path, two delivery shapes. */
    public final class Planner {

        private final Instant now;
        private final EvictionSink sink;
        private final List<Release> group = new ArrayList<>();

        private Planner(Instant now, EvictionSink sink) {
            this.now = now;
            this.sink = sink;
        }

        /** Add the next release of the grouped stream, judging the previous coordinate's group when this release
         *  opens a new one. */
        public void offer(Release release) throws IOException {
            if (!group.isEmpty() && !sameCoordinate(group.getFirst(), release)) {
                judgeGroup();
            }
            group.add(release);
        }

        /** Judge the final group; call once, after the enumeration is exhausted. */
        public void finish() throws IOException {
            judgeGroup();
        }

        private void judgeGroup() throws IOException {
            if (group.isEmpty()) {
                return;
            }
            List<CleanupPlan.Eviction> evictions = new ArrayList<>();
            judge(group, now, evictions);
            group.clear();
            for (CleanupPlan.Eviction eviction : evictions) {
                sink.accept(eviction);
            }
        }

        private boolean sameCoordinate(Release left, Release right) {
            return Objects.equals(left.ecosystem(), right.ecosystem())
                    && Objects.equals(left.coordinate(), right.coordinate());
        }
    }

    private String reason(Release release, int index, Instant now) {
        if (keepLast > 0 && index >= keepLast) {
            return "beyond keep-last=" + keepLast;
        }
        Duration age = Duration.between(release.published(), now);
        if (release.prerelease() && prereleaseExpiry != null && age.compareTo(prereleaseExpiry) > 0) {
            return "prerelease older than " + prereleaseExpiry.toDays() + " days";
        }
        if (maxAge != null && age.compareTo(maxAge) > 0) {
            return "older than " + maxAge.toDays() + " days";
        }
        if (notDownloadedFor != null
                && Duration.between(release.lastDownloaded(), now).compareTo(notDownloadedFor) > 0) {
            return "not downloaded for " + notDownloadedFor.toDays() + " days";
        }
        return null;
    }
}
