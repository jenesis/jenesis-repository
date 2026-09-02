package build.jenesis.repository.walk.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

/**
 * The corpus every archetype fixture seeds: {@code n} retained serving pointers under one pointer root, each naming a
 * real content-addressed blob, laid out exactly as {@code Publication} lays a pointer out (a small object whose whole
 * content is a lower-case SHA-256).
 *
 * <p>The root is deliberately <em>not</em> {@code publish}. Under {@code publish/} the rebuild pass additionally runs
 * the withheld screen per pointer, which reads the store several more times per delivery - useful behaviour, already
 * pinned by {@code RebuildPassTest}, and noise here: the crash points in this kit are positioned by the consumer's own
 * delivery count and must not be perturbed by a screen's reads. A blobs-namespace root is the other half of the
 * documented delivery contract anyway ("the path is the raw store key"), so the corpus exercises a real shape rather
 * than a contrived one.
 */
final class KitCorpus {

    /** The pointer root the archetypes walk - a format's own blobs-namespace root, not the free {@code publish/} one. */
    static final String ROOT = "walkkit";

    private KitCorpus() {
    }

    /** Publish {@code artifacts} pointers and answer the path-to-hash map a converged consumer must hold. */
    static WalkConsumerFixture.Corpus seed(ArtifactStore store, int artifacts) throws IOException {
        Map<String, String> converged = new HashMap<>();
        for (int index = 0; index < artifacts; index++) {
            String key = ROOT + "/" + String.format("%03d", index) + "/artifact";
            String hash = store.writeBlob(new ByteArrayInputStream(
                    ("payload " + index).getBytes(StandardCharsets.UTF_8)));
            store.writeVersioned(key, hash.getBytes(StandardCharsets.UTF_8), null);
            converged.put(key, hash);
        }
        // A leaf that names no hash is metadata, never a serving pointer: it must not be delivered, so it must not be
        // in the declared projection either - which makes the "exactly one delivery per retained pointer" count real
        // rather than "one per stored key".
        store.writeVersioned(ROOT + "/notes", "2026-08-09T00:00:00Z rebuild".getBytes(StandardCharsets.UTF_8), null);
        return new WalkConsumerFixture.Corpus(artifacts, converged);
    }

    /** A path rendered as one traversal-free key segment, so a consumer can key a row by the path it was handed. */
    static String encode(String path) {
        return HexFormat.of().formatHex(path.getBytes(StandardCharsets.UTF_8));
    }

    /** The inverse of {@link #encode}. */
    static String decode(String segment) {
        return new String(HexFormat.of().parseHex(segment), StandardCharsets.UTF_8);
    }

    /** Read a small store object as text, or {@code null} when it is absent. */
    static String text(ArtifactStore store, String key) throws IOException {
        return store.readVersioned(key)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8))
                .orElse(null);
    }

    /** Write a small store object, overwriting whatever was there - the upsert every converge pass makes. */
    static void write(ArtifactStore store, String key, String content) throws IOException {
        store.write(key, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
