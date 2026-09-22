package build.jenesis.repository.events;

import module java.base;

/**
 * One repository event: the small, self-describing metadata a notification carries - what happened ({@link
 * EventType type}), which coordinate it happened to ({@code ecosystem}/{@code coordinate}/{@code version}), the
 * request {@code path} it serves at, a handful of type-specific {@code detail} strings (a gate verdict and its
 * reasons, a finding's source and severity, a promotion's staged count), and when it happened ({@code at}). It
 * never carries an artifact body - a producer emits the descriptor, not the bytes - so an event is a metadata hop
 * a sink can queue and deliver without ever touching the content-addressed store. The tenant and repository are
 * <em>not</em> on the event: an event is emitted into a tenant-and-repository-scoped store, and the delivery drain
 * stamps those from the authoritative pass context rather than trusting a producer to thread them through.
 */
public record RepositoryEvent(EventType type, String ecosystem, String coordinate, String version, String path,
                              Map<String, String> detail, Instant at) {

    public RepositoryEvent {
        // The type is what every sink dispatches on and what emit's own diagnostic renders, so a null one would
        // surface as a NullPointerException from inside the handler that exists to report failures - the containment
        // defeated from within, which is the shape closed for a throwing name(). It is a producer bug either
        // way, so it fails here, at construction, where the producer can still be named by the stack.
        Objects.requireNonNull(type, "type");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    /** An accepted, serving publish of a coordinate at a path. */
    public static RepositoryEvent publish(String ecosystem, String coordinate, String version, String path,
                                          Instant at) {
        return new RepositoryEvent(EventType.PUBLISH, ecosystem, coordinate, version, path, Map.of(), at);
    }

    /** A gate decision that withheld an artifact - a quarantine (held for review) or a reject (refused) - carrying
     *  the {@code verdict} name and the joined {@code reasons} that drove it. */
    public static RepositoryEvent quarantine(String ecosystem, String coordinate, String path, String verdict,
                                             List<String> reasons, Instant at) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("verdict", verdict);
        if (reasons != null && !reasons.isEmpty()) {
            detail.put("reasons", String.join(" | ", reasons));
        }
        return new RepositoryEvent(EventType.QUARANTINE, ecosystem, coordinate, null, path, detail, at);
    }

    /** A finding newly recorded against a coordinate version - its {@code source}, {@code id}, {@code severity} and
     *  {@code category}, so a subscriber can triage without a second lookup. */
    public static RepositoryEvent finding(String ecosystem, String coordinate, String version, String source,
                                          String id, String severity, String category, Instant at) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("source", source);
        detail.put("id", id);
        if (severity != null) {
            detail.put("severity", severity);
        }
        if (category != null) {
            detail.put("category", category);
        }
        return new RepositoryEvent(EventType.FINDING, ecosystem, coordinate, version, null, detail, at);
    }

    /** A staging id promoted in full, carrying the staging id and how many artifacts it released. */
    public static RepositoryEvent promotion(String stagingId, int artifacts, Instant at) {
        return new RepositoryEvent(EventType.PROMOTION, null, null, null, null,
                Map.of("staging", stagingId == null ? "" : stagingId, "artifacts", Integer.toString(artifacts)), at);
    }

    /** A reviewer release: a withheld artifact at {@code path} promoted back into the layout and now serving, so an
     *  integrator that reacted to the {@link #quarantine} hold is told it resolved. The coordinate is not always
     *  known at the release surface (a hold is keyed on the {@code /quarantine} path), so it is carried when the
     *  producer has it and left {@code null} otherwise - the path is the stable identifier the quarantine event
     *  carried too. */
    public static RepositoryEvent release(String ecosystem, String coordinate, String version, String path,
                                          Instant at) {
        return new RepositoryEvent(EventType.RELEASE, ecosystem, coordinate, version, path, Map.of(), at);
    }

    /** A reviewer discard: a withheld artifact at {@code path} destroyed rather than released, the other terminal
     *  resolution of a hold, so a {@link #quarantine} subscriber is told the hold resolved either way. */
    public static RepositoryEvent discard(String ecosystem, String coordinate, String version, String path,
                                          Instant at) {
        return new RepositoryEvent(EventType.DISCARD, ecosystem, coordinate, version, path, Map.of(), at);
    }

    /** A previously-serving coordinate at {@code path} removed - the delete counterpart of {@link #publish}. A
     *  CDN-origin edge cache (EPIC 29 RD-6) subscribes to this to purge the withdrawn or retro-screened artifact
     *  from the edge, since a redirect target that a serve plane just stopped honouring must not keep serving from a
     *  cache. Fired from the store's after-delete hook, so it covers a reviewer discard's pointer removal, a
     *  retention sweep and a retro-screening withhold alike. */
    public static RepositoryEvent unpublish(String ecosystem, String coordinate, String version, String path,
                                            Instant at) {
        return new RepositoryEvent(EventType.UNPUBLISH, ecosystem, coordinate, version, path, Map.of(), at);
    }
}
