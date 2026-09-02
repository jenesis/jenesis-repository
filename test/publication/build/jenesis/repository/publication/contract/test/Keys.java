package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

/** Key shapes and small store idioms the synthetic hooks share, so each archetype is only its own behaviour. */
final class Keys {

    private Keys() {
    }

    /** A request path reduced to one flat, store-safe key segment. Derived state is keyed by request path, and a
     *  request path carries slashes, so a hook that wants one row per artifact flattens it. */
    static String slug(String path) {
        return path.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** Upsert a small object: read the current token and compare-and-set over it, so a repeated delivery converges
     *  instead of appending. Answers whether the write landed. */
    static boolean upsert(ArtifactStore store, String key, String body) throws IOException {
        Optional<ArtifactStore.Versioned> prior = store.readVersioned(key);
        return store.writeVersioned(key, body.getBytes(StandardCharsets.UTF_8),
                prior.map(ArtifactStore.Versioned::token).orElse(null));
    }

    /** The body of a small object, or empty when nothing is stored there. */
    static Optional<String> read(ArtifactStore store, String key) throws IOException {
        return store.readVersioned(key)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8));
    }

    /** Every leaf key under {@code prefix}, mapped to its body - the shape a fixture's projection takes. */
    static Map<String, String> rows(ArtifactStore store, String prefix) throws IOException {
        Map<String, String> rows = new TreeMap<>();
        for (String child : store.list(prefix)) {
            read(store, prefix + "/" + child).ifPresent(body -> rows.put(child, body));
        }
        return rows;
    }

    /** Every currently published request path, read out of the {@code publish/} pointer namespace - durable truth,
     *  and what a repair sweep rebuilds from. The {@code /quarantine} review subtree is skipped: a held path is
     *  stored, not served, so no publish-derived surface may carry it.
     *
     *  <p>A deliberately small stand-in for the walk SPI's shared enumeration: the store testkit cannot require the
     *  walk module, and a fixture's repair leg only has to be executable and to read durable truth, not to be the
     *  production sweep. */
    static List<String> published(ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        descend(store, ServableNames.PUBLISHED, "", paths);
        paths.sort(Comparator.naturalOrder());
        return paths;
    }

    private static void descend(ArtifactStore store, String key, String path, List<String> paths) {
        List<String> children = store.list(key);
        if (children.isEmpty()) {
            if (!path.isEmpty() && !path.equals("/" + ServableNames.QUARANTINE)
                    && !path.startsWith("/" + ServableNames.QUARANTINE + "/")) {
                paths.add(path);
            }
            return;
        }
        for (String child : children) {
            descend(store, key + "/" + child, path + "/" + child, paths);
        }
    }
}
