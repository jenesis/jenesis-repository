package build.jenesis.repository.hooks.testkit;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

/** Store idioms the shipped-hook fixtures share, so each fixture is only the statement of its own hook's surface. */
public final class Hooks {

    private Hooks() {
    }

    /** A request path reduced to one flat, store-safe key segment - what a fixture keyed by request path uses to name
     *  a projection row without carrying the path's slashes into the map. */
    public static String slug(String path) {
        return path.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** The body of a small object, or empty when nothing is stored there. */
    public static Optional<String> read(ArtifactStore store, String key) throws IOException {
        return store.readVersioned(key).map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8));
    }

    /** Upsert a small object over its current token, so a repeated write converges instead of appending. */
    public static void upsert(ArtifactStore store, String key, String body) throws IOException {
        Optional<ArtifactStore.Versioned> prior = store.readVersioned(key);
        store.writeVersioned(key, body.getBytes(StandardCharsets.UTF_8),
                prior.map(ArtifactStore.Versioned::token).orElse(null));
    }

    /** Every leaf key under {@code prefix}, relative to it, mapped to its body - the shape a fixture's projection
     *  takes. Walked rather than listed one level deep, so a surface that nests (an outbox shard, a per-kind
     *  subtree) is projected whole rather than as a set of directory names. */
    public static Map<String, String> rows(ArtifactStore store, String prefix) throws IOException {
        Map<String, String> rows = new TreeMap<>();
        collect(store, prefix, "", rows);
        return rows;
    }

    private static void collect(ArtifactStore store, String key, String relative, Map<String, String> rows)
            throws IOException {
        List<String> children = store.list(key);
        if (children.isEmpty()) {
            if (!relative.isEmpty()) {
                read(store, key).ifPresent(body -> rows.put(relative, body));
            }
            return;
        }
        for (String child : children) {
            collect(store, key + "/" + child, relative.isEmpty() ? child : relative + "/" + child, rows);
        }
    }

    /** Every leaf key under {@code prefix}, relative to it, without reading a body - for a surface whose rows are
     *  presence rather than content (a stamp, a coalescing marker). */
    public static Set<String> names(ArtifactStore store, String prefix) throws IOException {
        return rows(store, prefix).keySet();
    }

    /** Every currently published request path, read out of the {@code publish/} pointer namespace - durable truth,
     *  and what every repair leg here rebuilds from. The {@code /quarantine} review subtree is skipped: a held path
     *  is stored, not served, so no publish-derived surface may carry it. */
    public static List<String> published(ArtifactStore store) throws IOException {
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

    /** The blob hash the serving pointer at {@code path} names, as the publish wrote it. */
    public static Optional<String> pointer(ArtifactStore store, String path) throws IOException {
        return read(store, ServableNames.PUBLISHED + path).map(ServableNames::hash).filter(hash -> !hash.isEmpty());
    }
}
