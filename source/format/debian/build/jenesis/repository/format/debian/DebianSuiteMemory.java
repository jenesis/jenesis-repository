package build.jenesis.repository.format.debian;

import module java.base;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.UpstreamMemory;

/**
 * A proxied suite's release documents - {@code InRelease}, {@code Release} and {@code Release.gpg} - remembered
 * together in the node's {@link UpstreamMemory} for {@code jenrepo.cache.upstream-ttl}, and every other index of the
 * suite fetched by the digest the remembered {@code Release} names.
 *
 * <p>A suite is a family: its {@code Release} names each {@code Packages}, {@code Sources} and {@code Contents} file by
 * digest, and apt refuses a file whose digest is not the one named. Remembered alone, a {@code Release} from one
 * moment would name files the upstream has since replaced. So the three release documents are fetched in one go and
 * remembered only together, and only from an upstream that declares {@code Acquire-By-Hash: yes}: an index asked for
 * by name is then fetched from the upstream's {@code by-hash/SHA256/<digest>} path the remembered {@code Release}
 * names, which is the file that {@code Release} vouches for whatever the upstream has moved to since. A suite whose
 * upstream offers no by-hash path cannot be pinned that way, so it is relayed fresh, every document of it at once.
 */
final class DebianSuiteMemory {

    private static final Pattern RELEASE = Pattern.compile("dists/([^/]+)/(InRelease|Release|Release\\.gpg)");
    private static final List<String> FAMILY = List.of("InRelease", "Release", "Release.gpg");
    private static final Pattern SUITE_INDEX = Pattern.compile("dists/([^/]+)/(.+)");

    private DebianSuiteMemory() {
    }

    /** The suite a release document's path names, or empty for any other path. */
    static Optional<String> releaseOf(String rest) {
        Matcher matcher = RELEASE.matcher(rest);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * Answer a suite's release document from what the node remembers of the suite. On a miss the document asked for
     * is fetched - or, for {@code Release.gpg}, the {@code Release} it signs - and only when it declares by-hash are its
     * siblings fetched too and the family remembered; otherwise the request is answered from what was fetched, at the
     * cost of the one fetch a fresh relay makes.
     */
    static boolean relayRelease(ProxyFormat.Fetcher fetcher, String root, String suite, String rest,
                                FormatExchange exchange, ArtifactStore store, ProxyRelay.Document document,
                                ProxyRelay.Tap tap) throws IOException {
        URI url = URI.create(root + rest);
        Optional<UpstreamMemory.Remembered> remembered = UpstreamMemory.node().get(store, url);
        if (remembered.isEmpty()) {
            String name = rest.substring(rest.lastIndexOf('/') + 1);
            Map<String, ProxyFormat.Fetched> family = fetch(fetcher, root, suite, name);
            if (remember(family, root, suite, store)) {
                remembered = UpstreamMemory.node().get(store, url);
            } else if (family.get(name) != null && family.get(name).status() == 200) {
                ProxyFormat.Fetched own = family.get(name);
                answer(own, exchange);
                if (tap != null) {
                    tap.read(new ByteArrayInputStream(own.body()));
                }
                return true;
            }
        }
        if (remembered.isPresent()) {
            ProxyRelay.answerRemembered(remembered.get(), null, exchange);
            return true;
        }
        return ProxyRelay.streamFresh(fetcher, url, null, exchange, document, tap);
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
        Optional<String> release = releaseText(root, suite, store);
        if (release.isEmpty()) {
            return Optional.empty();
        }
        String digest = sha256(release.get()).get(index);
        if (digest == null) {
            return Optional.empty();
        }
        int slash = index.lastIndexOf('/');
        String directory = slash < 0 ? "" : index.substring(0, slash + 1);
        return Optional.of(URI.create(root + "dists/" + suite + "/" + directory + "by-hash/SHA256/" + digest));
    }

    /**
     * The release documents a miss of {@code name} fetches, by name: the one asked for - or the {@code Release} a
     * {@code Release.gpg} signs - and, when it declares by-hash, the rest of the family with it. A document the
     * upstream did not answer is absent; one it answered otherwise carries its status.
     */
    private static Map<String, ProxyFormat.Fetched> fetch(ProxyFormat.Fetcher fetcher, String root, String suite,
                                                          String name) throws IOException {
        Map<String, ProxyFormat.Fetched> family = new LinkedHashMap<>();
        String probe = name.equals("Release.gpg") ? "Release" : name;
        Optional<ProxyFormat.Fetched> first = fetcher.fetch(URI.create(root + "dists/" + suite + "/" + probe), Map.of());
        if (first.isEmpty()) {
            return family;
        }
        family.put(probe, first.get());
        if (first.get().status() != 200 || !byHash(new String(first.get().body(), StandardCharsets.UTF_8))) {
            return family;
        }
        for (String sibling : FAMILY) {
            if (!family.containsKey(sibling)) {
                fetcher.fetch(URI.create(root + "dists/" + suite + "/" + sibling), Map.of())
                        .ifPresent(fetched -> family.put(sibling, fetched));
            }
        }
        return family;
    }

    /** Remember a family fetched in one go: every document the upstream answered, when the family is whole - each of
     *  the three answered, by-hash declared, and each small enough to keep. Answers whether it remembered it. */
    private static boolean remember(Map<String, ProxyFormat.Fetched> family, String root, String suite,
                                    ArtifactStore store) throws IOException {
        if (!family.keySet().containsAll(FAMILY)) {
            return false;
        }
        for (ProxyFormat.Fetched fetched : family.values()) {
            if (fetched.status() == 200 && fetched.body().length > UpstreamMemory.ENTRY_CAP) {
                return false;
            }
        }
        for (Map.Entry<String, ProxyFormat.Fetched> entry : family.entrySet()) {
            if (entry.getValue().status() != 200) {
                continue;
            }
            String path = "dists/" + suite + "/" + entry.getKey();
            UpstreamMemory.node().put(store, URI.create(root + path), entry.getValue().body(),
                    entry.getValue()::header);
            if (entry.getKey().equals("InRelease")) {
                DebianFormat.keepInRelease(new ByteArrayInputStream(entry.getValue().body()), store, path);
            }
        }
        return true;
    }

    /** Answer a document fetched whole: its body, type and validators. */
    private static void answer(ProxyFormat.Fetched fetched, FormatExchange exchange) throws IOException {
        String contentType = fetched.header("Content-Type");
        if (contentType != null) {
            exchange.setResponseHeader("Content-Type", contentType);
        }
        ProxyRelay.relayValidators(fetched, exchange);
        exchange.respond(200, fetched.body());
    }

    /** The remembered {@code Release} of a suite, else its remembered {@code InRelease}, as text. */
    private static Optional<String> releaseText(String root, String suite, ArtifactStore store) {
        for (String name : List.of("Release", "InRelease")) {
            Optional<UpstreamMemory.Remembered> remembered =
                    UpstreamMemory.node().get(store, URI.create(root + "dists/" + suite + "/" + name));
            if (remembered.isPresent()) {
                return Optional.of(new String(remembered.get().body(), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
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
