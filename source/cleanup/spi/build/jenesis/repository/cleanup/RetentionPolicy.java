package build.jenesis.repository.cleanup;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * A retention rule over each coordinate's published versions. A version is kept only if it is among the
 * {@code keepLast} newest, within {@code maxAge} (and {@code prereleaseExpiry} for a prerelease), and downloaded within
 * {@code notDownloadedFor}; anything else is evicted - except a coordinate's single newest version, always kept so a
 * cleanup never empties a coordinate, and a {@link Release#pinned() pinned} version, never evicted. A {@code keepLast}
 * of 0 or a {@code null} duration disables that rule. The plan is computed, not applied, so it can be previewed.
 */
public final class RetentionPolicy {

    /** The four rules as repository setting keys. */
    public static final String KEEP_LAST = "keep-last";
    public static final String MAX_AGE = "max-age";
    public static final String PRERELEASE_EXPIRY = "prerelease-expiry";
    public static final String NOT_DOWNLOADED_FOR = "not-downloaded-for";

    /** The four keys, in the order the rules are listed everywhere. */
    public static final List<String> KEYS = List.of(KEEP_LAST, MAX_AGE, PRERELEASE_EXPIRY, NOT_DOWNLOADED_FOR);

    // Newest-first, one comparator for every coordinate of every plan.
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
        // A zero or negative duration would make every past publish "older" and mass-delete all but each coordinate's
        // newest on the next sweep, so a deletion policy fails at construction, never at sweep time.
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

    /** Build a policy from the retention settings read through {@code config}, which answers {@code null} when unset;
     *  an unset or blank key disables its rule. */
    public static RetentionPolicy fromConfig(UnaryOperator<String> config) {
        return parse(config.apply(KEEP_LAST), config.apply(MAX_AGE), config.apply(PRERELEASE_EXPIRY),
                config.apply(NOT_DOWNLOADED_FOR));
    }

    /** The one parse of the four dials - the deployment default, the scheduled sweep, the API, the console and a
     *  repository's settings all come through here. An unset or blank dial, or a duration set to {@link Durations#NONE}
     *  (how a repository switches off a rule its tenant or deployment sets), disables its rule; a duration is the
     *  deployment's grammar ({@code P30D}, {@code 30d}, {@code PT12H}); a zero or negative one is refused here rather
     *  than at sweep time. A malformed value is an {@link IllegalArgumentException} naming it. */
    public static RetentionPolicy parse(String keepLast, String maxAge, String prereleaseExpiry,
                                        String notDownloadedFor) {
        return parse(keepLast == null || keepLast.isBlank() ? 0 : Integer.parseInt(keepLast.trim()),
                maxAge, prereleaseExpiry, notDownloadedFor);
    }

    /** {@link #parse(String, String, String, String)} with the count already a number. */
    public static RetentionPolicy parse(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
        return new RetentionPolicy(keepLast, rule(maxAge), rule(prereleaseExpiry), rule(notDownloadedFor));
    }

    /** One duration dial: absent when unset, blank or {@link Durations#NONE}, else its duration. */
    private static Duration rule(String value) {
        return value == null || value.isBlank() ? null : Durations.parseOrNone(value).orElse(null);
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
        // Grouped by ecosystem and coordinate: two ecosystems may share a coordinate string, and each version list is
        // judged on its own - the same groups the store inventory's stream delivers.
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

    /** Judge the versions of one coordinate: newest-first, the newest always kept, a pinned version never evicted,
     *  everything else condemned by the first rule naming it. Judging a subset is conservative - a release's rank in a
     *  subset never exceeds its full rank, the age rules are per release, and the subset's newest is protected too - so
     *  a group split by a crash-resume under-evicts for one pass and converges on the next. Sorts {@code versions} in
     *  place. */
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

    /** A streaming evaluation of this policy over releases delivered grouped by coordinate (the
     *  {@link RepositoryInventory#releases(RepositoryInventory.ReleaseVisitor)} contract): it buffers only the current
     *  coordinate's versions and hands each group's evictions to {@code sink} as the group ends, so a sweep evicts
     *  while the enumeration flows. Call {@link Planner#finish()} after the last release. */
    public Planner planner(Instant now, EvictionSink sink) {
        return new Planner(now, sink);
    }

    /** Receives each eviction a {@link Planner} condemns - a plan collects it, a sweep applies it. */
    @FunctionalInterface
    public interface EvictionSink {
        void accept(CleanupPlan.Eviction eviction) throws IOException;
    }

    /** The one-coordinate-at-a-time state of a streaming plan: {@link #offer} detects the group boundary and judges the
     *  completed group through the same rules {@link #plan} applies. */
    public final class Planner {

        private final Instant now;
        private final EvictionSink sink;
        private final List<Release> group = new ArrayList<>();

        private Planner(Instant now, EvictionSink sink) {
            this.now = now;
            this.sink = sink;
        }

        /** Add the next release, judging the previous coordinate's group when this one opens a new group. */
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
