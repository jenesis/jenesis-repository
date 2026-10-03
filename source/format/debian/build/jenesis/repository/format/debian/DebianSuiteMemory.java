package build.jenesis.repository.format.debian;

import module java.base;
import build.jenesis.repository.blobs.FamilyMemory;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A proxied suite's release documents - {@code InRelease}, {@code Release} and {@code Release.gpg} - remembered
 * together as a {@link FamilyMemory.Family} for {@code jenrepo.cache.upstream-ttl}, and every other index of the suite
 * fetched by the digest the remembered {@code Release} names.
 *
 * <p>A suite is a family: its {@code Release} names each {@code Packages}, {@code Sources} and {@code Contents} file by
 * digest, and apt refuses a file whose digest is not the one named. So the release documents are remembered only
 * together, and only from an upstream that declares {@code Acquire-By-Hash: yes}: an index asked for by name is then
 * fetched from the upstream's {@code by-hash/SHA256/<digest>} path the remembered {@code Release} names, which is the
 * file that {@code Release} vouches for whatever the upstream has moved to since. A suite whose upstream offers no
 * by-hash path cannot be pinned that way, so it is relayed fresh, every document of it at once.
 */
final class DebianSuiteMemory {

    private static final Pattern RELEASE = Pattern.compile("dists/([^/]+)/(InRelease|Release|Release\\.gpg)");
    private static final Pattern SUITE_INDEX = Pattern.compile("dists/([^/]+)/(.+)");

    private DebianSuiteMemory() {
    }

    /** The suite a release document's path names, or empty for any other path. */
    static Optional<String> releaseOf(String rest) {
        Matcher matcher = RELEASE.matcher(rest);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Answer a suite's release document from the suite's remembered family. */
    static boolean relayRelease(ProxyFormat.Fetcher fetcher, String root, String suite, String rest,
                                FormatExchange exchange, ArtifactStore store, ProxyRelay.Document document,
                                ProxyRelay.Tap tap) throws IOException {
        String member = rest.substring(rest.lastIndexOf('/') + 1);
        return FamilyMemory.relay(fetcher, family(root, suite), member, exchange, store, document, tap,
                (name, body) -> {
                    if (name.equals("InRelease")) {
                        DebianFormat.keepInRelease(new ByteArrayInputStream(body), store,
                                "dists/" + suite + "/InRelease");
                    }
                });
    }

    /**
     * Where to fetch an index of a suite the node remembers: the upstream's by-hash path for the digest the remembered
     * {@code Release} names for it, or empty for a path no remembered {@code Release} names - a suite not remembered,
     * a by-hash fetch itself, a file the release does not list.
     */
    static Optional<URI> pinned(String root, String rest, ArtifactStore store) {
        Matcher matcher = SUITE_INDEX.matcher(rest);
        if (!matcher.matches() || matcher.group(2).contains("/by-hash/") || releaseOf(rest).isPresent()) {
            return Optional.empty();
        }
        String suite = matcher.group(1);
        String index = matcher.group(2);
        FamilyMemory.Family family = family(root, suite);
        Optional<byte[]> release = FamilyMemory.remembered(family, "Release", store)
                .or(() -> FamilyMemory.remembered(family, "InRelease", store));
        if (release.isEmpty()) {
            return Optional.empty();
        }
        String digest = sha256(new String(release.get(), StandardCharsets.UTF_8)).get(index);
        if (digest == null) {
            return Optional.empty();
        }
        int slash = index.lastIndexOf('/');
        String directory = slash < 0 ? "" : index.substring(0, slash + 1);
        return Optional.of(URI.create(root + "dists/" + suite + "/" + directory + "by-hash/SHA256/" + digest));
    }

    private static FamilyMemory.Family family(String root, String suite) {
        return new FamilyMemory.Family(URI.create(root + "dists/" + suite + "/"),
                List.of("InRelease", "Release", "Release.gpg"), List.of("InRelease", "Release"),
                body -> byHash(new String(body, StandardCharsets.UTF_8)));
    }

    /** Whether a release declares that its indexes are served by hash. */
    static boolean byHash(String release) {
        return release.lines().map(String::strip).anyMatch(line -> line.equalsIgnoreCase("Acquire-By-Hash: yes"));
    }

    /** The {@code SHA256} section of a release: each listed file, relative to the suite, and its digest. */
    static Map<String, String> sha256(String release) {
        Map<String, String> files = new HashMap<>();
        boolean listing = false;
        for (String line : release.lines().toList()) {
            if (line.equals("SHA256:")) {
                listing = true;
            } else if (listing && line.startsWith(" ")) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length == 3) {
                    files.put(fields[2], fields[0]);
                }
            } else {
                listing = false;
            }
        }
        return files;
    }
}
