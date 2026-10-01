package build.jenesis.repository.cleanup;

import module java.base;

/**
 * A published version of a coordinate as the cleanup sees it: the ecosystem and its canonical coordinate, the version,
 * when it was published and last downloaded (the publish time when no download was tracked), whether it is a prerelease
 * (decided by the format, so no version convention leaks in here), and whether it is pinned (immune to every retention
 * rule).
 */
public record Release(String ecosystem, String coordinate, String version, Instant published, Instant lastDownloaded,
                      boolean prerelease, boolean pinned, Long downloads, Instant downloadedAt) {

    /** A release whose download facts are unknown: {@code downloads} and {@code downloadedAt} are {@code null}, while
     *  {@code lastDownloaded} stays the retention view. */
    public Release(String ecosystem, String coordinate, String version, Instant published, Instant lastDownloaded,
                   boolean prerelease, boolean pinned) {
        this(ecosystem, coordinate, version, published, lastDownloaded, prerelease, pinned, null, null);
    }


    /** A release nothing has downloaded since publication: last-downloaded is the publish time, unpinned, ecosystem
     *  unset. */
    public Release(String coordinate, String version, boolean prerelease, Instant published) {
        this(null, coordinate, version, published, published, prerelease, false);
    }

    /** An unpinned release with an explicit last-downloaded time. */
    public Release(String coordinate, String version, boolean prerelease, Instant published, Instant lastDownloaded) {
        this(null, coordinate, version, published, lastDownloaded, prerelease, false);
    }

    /** A release with an explicit last-downloaded time and pin state. */
    public Release(String coordinate, String version, boolean prerelease, Instant published, Instant lastDownloaded,
                   boolean pinned) {
        this(null, coordinate, version, published, lastDownloaded, prerelease, pinned);
    }
}
