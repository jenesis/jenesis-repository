package build.jenesis.repository.events;

import module java.base;

/**
 * One repository event: the small metadata a notification carries - its {@link EventType type}, the coordinate
 * ({@code ecosystem}/{@code coordinate}/{@code version}), the request {@code path}, a few type-specific {@code detail}
 * strings, and when it happened ({@code at}). Never an artifact body. No tenant or repository: the event is emitted
 * into a scoped store, and the delivery drain stamps them from its pass context.
 */
public record RepositoryEvent(EventType type, String ecosystem, String coordinate, String version, String path,
                              Map<String, String> detail, Instant at) {

    public RepositoryEvent {
        // Every sink dispatches on the type and the emit diagnostic renders it, so a null one would fail inside the
        // containment; it fails here instead, where the producer is on the stack.
        Objects.requireNonNull(type, "type");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    /** An accepted, serving publish of a coordinate at a path. */
    public static RepositoryEvent publish(String ecosystem, String coordinate, String version, String path,
                                          Instant at) {
        return new RepositoryEvent(EventType.PUBLISH, ecosystem, coordinate, version, path, Map.of(), at);
    }

    /** A gate decision that withheld an artifact - quarantined or rejected - with the {@code verdict} and the joined
     *  {@code reasons}. */
    public static RepositoryEvent quarantine(String ecosystem, String coordinate, String path, String verdict,
                                             List<String> reasons, Instant at) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("verdict", verdict);
        if (reasons != null && !reasons.isEmpty()) {
            detail.put("reasons", String.join(" | ", reasons));
        }
        return new RepositoryEvent(EventType.QUARANTINE, ecosystem, coordinate, null, path, detail, at);
    }

    /** A finding newly recorded against a coordinate version, with its {@code source}, {@code id}, {@code severity} and
     *  {@code category}. */
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

    /** A staging id promoted in full, with how many artifacts it released. */
    public static RepositoryEvent promotion(String stagingId, int artifacts, Instant at) {
        return new RepositoryEvent(EventType.PROMOTION, null, null, null, null,
                Map.of("staging", stagingId == null ? "" : stagingId, "artifacts", Integer.toString(artifacts)), at);
    }

    /** A reviewer release: a withheld artifact at {@code path} serving again. The coordinate is carried when the
     *  producer has it, the hold being keyed on its {@code /quarantine} path; the path is the identifier the quarantine
     *  event carried. */
    public static RepositoryEvent release(String ecosystem, String coordinate, String version, String path,
                                          Instant at) {
        return new RepositoryEvent(EventType.RELEASE, ecosystem, coordinate, version, path, Map.of(), at);
    }

    /** A reviewer discard: a withheld artifact at {@code path} destroyed, the other resolution of a hold. */
    public static RepositoryEvent discard(String ecosystem, String coordinate, String version, String path,
                                          Instant at) {
        return new RepositoryEvent(EventType.DISCARD, ecosystem, coordinate, version, path, Map.of(), at);
    }

    /** A serving coordinate at {@code path} removed, the delete counterpart of {@link #publish}, which an edge cache
     *  purges on. Fired from the after-delete hook, so a discard, a retention sweep and a retro-screening withhold
     *  alike raise it. */
    public static RepositoryEvent unpublish(String ecosystem, String coordinate, String version, String path,
                                            Instant at) {
        return new RepositoryEvent(EventType.UNPUBLISH, ecosystem, coordinate, version, path, Map.of(), at);
    }
}
