package build.jenesis.repository.cleanup;

import module java.base;

/**
 * A published version of a coordinate, as the cleanup sees it: the ecosystem and its ecosystem-canonical coordinate
 * (a Maven {@code group:artifact}, an npm package, ...), the version, when it was last published, when it was last
 * downloaded (the publish time when nothing has tracked a download), whether it is a prerelease (a Maven
 * {@code -SNAPSHOT}, an npm prerelease tag - decided by the format, not here), and whether it is pinned (force-kept,
 * immune to every retention rule). Prerelease is carried as a flag rather than derived from the version, so no version
 * convention leaks into the cleanup.
 */
public record Release(String ecosystem, String coordinate, String version, Instant published, Instant lastDownloaded,
                      boolean prerelease, boolean pinned, Long downloads, Instant downloadedAt) {

    /** A release whose download facts are unknown - the shape every retention rule and every test needed before the
     *  count existed. {@code downloads} and {@code downloadedAt} are the recorded facts as they are, {@code null} when
     *  nothing was recorded; {@code lastDownloaded} stays the retention view, the publish time when nothing was. */
    public Release(String ecosystem, String coordinate, String version, Instant published, Instant lastDownloaded,
                   boolean prerelease, boolean pinned) {
        this(ecosystem, coordinate, version, published, lastDownloaded, prerelease, pinned, null, null);
    }


    /** A release nothing has downloaded since publication - last-downloaded defaults to the publish time, unpinned;
     *  ecosystem unset (production reads it from the inventory, the retention rules do not need it). */
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
