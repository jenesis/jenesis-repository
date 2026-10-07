package build.jenesis.repository.store;

import module java.base;

/**
 * Which versions of one package a hold is placed on: the coordinate face of the held-subject records, read in one
 * listing of the package's level and a probe of each held version's review pointers. The layout is
 * {@code subjects/version/<ecosystem>/<coordinate>/<version>/<sha-256 of a held path>}, each coordinate segment
 * URL-encoded and a row's body the held path; the inventory's held-subject record writes and reclaims the rows with the
 * hold through {@link #versionRoot}, and this is the one place the spelling is composed.
 *
 * <p>What reads it is a surface that must leave a held version out of what it shows - an index relayed from an
 * upstream, a closure - without a read per version it shows: a package with no hold costs one listing. A row a crash
 * left behind outlives its hold, so a version counts as held only while the review pointer of a path recorded for it is
 * in place.
 *
 * <p>Bounded: a package's held versions, and an ecosystem's held packages, are as many as the review queue holds.
 * Past {@link #MAX_VERSIONS} names at one level the read raises rather than answer short, since a short answer
 * discloses a held version.
 */
public final class HeldVersions {

    /** The level the version face lies under. */
    public static final String ROOT = "subjects/version";

    /** The most held versions of one package the read answers for. */
    public static final int MAX_VERSIONS = 10_000;

    private static final int PAGE = 1_000;

    private HeldVersions() {
    }

    /** The version face's container for one coordinate version, whose rows name its held paths. */
    public static String versionRoot(String ecosystem, String coordinate, String version) {
        return coordinateRoot(ecosystem, coordinate) + "/" + encode(version);
    }

    /** The versions of {@code coordinate} held for review in {@code store}, empty for a package with no hold. */
    public static Set<String> of(ArtifactStore store, String ecosystem, String coordinate) throws IOException {
        Set<String> held = new TreeSet<>();
        for (String name : names(store, coordinateRoot(ecosystem, coordinate), ecosystem + " " + coordinate)) {
            String version = URLDecoder.decode(name, StandardCharsets.UTF_8);
            if (held(store, ecosystem, coordinate, version)) {
                held.add(version);
            }
        }
        return held;
    }

    /**
     * Every coordinate of {@code ecosystem} with a version held for review in {@code store}, each with its held
     * versions: what a relayed index covering a whole repository leaves out. One listing of the ecosystem's level and
     * the read of {@link #of} per coordinate a hold names, so a repository with no hold costs one listing.
     */
    public static Map<String, Set<String>> all(ArtifactStore store, String ecosystem) throws IOException {
        Map<String, Set<String>> held = new TreeMap<>();
        for (String name : names(store, ROOT + "/" + encode(ecosystem), ecosystem)) {
            String coordinate = URLDecoder.decode(name, StandardCharsets.UTF_8);
            Set<String> versions = of(store, ecosystem, coordinate);
            if (!versions.isEmpty()) {
                held.put(coordinate, versions);
            }
        }
        return held;
    }

    /** Whether the review pointer of a path recorded as held for {@code version} of {@code coordinate} is in place. */
    public static boolean held(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        String root = versionRoot(ecosystem, coordinate, version);
        List<String> rows = new ArrayList<>();
        store.page(root, "", PAGE, rows::add);
        for (String row : rows) {
            Optional<String> path = store.readVersioned(root + "/" + row)
                    .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim())
                    .filter(recorded -> !recorded.isEmpty());
            if (path.isPresent() && Publication.reviewPending(store, path.get())) {
                return true;
            }
        }
        return false;
    }

    /** Every child name under {@code root}, refusing past {@link #MAX_VERSIONS}, since a short answer discloses a held
     *  version. */
    private static List<String> names(ArtifactStore store, String root, String what) throws IOException {
        List<String> names = new ArrayList<>();
        Names level = Names.over(store, root, PAGE);
        for (String name = level.next(); name != null; name = level.next()) {
            names.add(name);
            if (names.size() > MAX_VERSIONS) {
                throw new IOException("more than " + MAX_VERSIONS + " names under " + what + " are recorded as held; "
                        + "refusing to answer, because a short answer discloses a held version");
            }
        }
        return names;
    }

    private static String coordinateRoot(String ecosystem, String coordinate) {
        return ROOT + "/" + encode(ecosystem) + "/" + encode(coordinate);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
