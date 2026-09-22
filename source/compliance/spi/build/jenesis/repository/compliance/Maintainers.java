package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The maintainers a coordinate's published metadata has named, by coordinate - the durable side of
 * {@link Maintainer}, written on an accepted publish and read when a discovered key's binding is judged: a key
 * found through a maintainer is trusted only for a coordinate whose record names that maintainer. One small
 * document per coordinate ({@code maintainers/<ecosystem>/<sha-256 of the coordinate>}, one identity per line -
 * a person's, or the {@linkplain #REPOSITORY repository} the metadata names, which provenance-bound trust reads),
 * the union of what every version named, rewritten only when a publish adds a name; a point read at trust time,
 * never a walk. Best-effort like every derived write of an accepted publish: a lost write delays a binding being
 * satisfied and can never admit a signer.
 */
public final class Maintainers {

    static final String ROOT = "maintainers/";

    /** The prefix of a recorded repository - {@code repository:github.com/<owner>/<name>} - the one identity in a
     *  record that names a place rather than a person: read by the provenance trust, ignored by key discovery. */
    public static final String REPOSITORY = "repository:";

    private Maintainers() {
    }

    /** Record that this coordinate's metadata names these {@linkplain Maintainer#ids identities}; a name already
     *  recorded is kept, and a record naming nothing new is not rewritten. */
    public static void record(ArtifactStore store, String ecosystem, String coordinate, Collection<String> ids)
            throws IOException {
        if (ecosystem == null || coordinate == null || ids.isEmpty()) {
            return;
        }
        String key = key(ecosystem, coordinate);
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        Set<String> named = new TreeSet<>(current.map(recorded -> parse(recorded.content())).orElse(Set.of()));
        if (named.containsAll(ids)) {
            return;
        }
        named.addAll(ids);
        // A lost compare-and-set is a peer recording the same publish, or another version's names landing first;
        // either way the next accepted publish re-unions, so a refused write is not retried here.
        store.writeVersioned(key, (String.join("\n", named) + "\n").getBytes(StandardCharsets.UTF_8),
                current.map(ArtifactStore.Versioned::token).orElse(null));
    }

    /** The identities this coordinate's metadata has named, or none when nothing was recorded. */
    public static Set<String> named(ArtifactStore store, String ecosystem, String coordinate) throws IOException {
        if (ecosystem == null || coordinate == null) {
            return Set.of();
        }
        return store.readVersioned(key(ecosystem, coordinate)).map(recorded -> parse(recorded.content()))
                .orElse(Set.of());
    }

    private static Set<String> parse(byte[] document) {
        Set<String> ids = new TreeSet<>();
        for (String line : new String(document, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                ids.add(line.trim());
            }
        }
        return ids;
    }

    /** The coordinate hashed into one segment: a coordinate carries its ecosystem's own separators. */
    static String key(String ecosystem, String coordinate) {
        try {
            return ROOT + ecosystem + "/" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(coordinate.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
