package build.jenesis.repository.format.cocoapods;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CDN shard listings as stored listings: a per-pod version list from which the pod's line in its shard's
 * {@code all_pods_versions_<a>_<b>_<c>.txt} is derived on every write, so a publish rewrites two documents and scans no
 * other pod. A version is listed exactly when its archive pointer is not withheld and it is not yanked.
 */
final class CocoaPodsListings {

    /** Every write of a pod's shard line and the decision behind it, at DEBUG, so a shard line missing versions its pod
     *  document has can be traced to the writer and document sequence; a rebuild rides the listing's lane
     *  ({@code StoredListing.rebuild}) for that reason. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CocoaPodsListings.class);

    static final StoredListing.Codec LINES = StoredListing.Codec.delimited("\n", Function.identity());

    static final StoredListing.Codec SHARD = StoredListing.Codec.delimited("\n", line -> {
        int slash = line.indexOf('/');
        return slash < 0 ? line : line.substring(0, slash);
    });

    private final Blobs blobs;
    private final ArtifactStore store;

    CocoaPodsListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String pod(String repo, String name) {
        return "cocoapods/" + repo + "/pods/" + name;
    }

    static String shard(String repo, String[] shard) {
        return "cocoapods/" + repo + "/all_pods_versions_" + shard[0] + "_" + shard[1] + "_" + shard[2] + ".txt";
    }

    StoredListing.Spec podSpec(String repo, String name) {
        String[] shard = CocoaPodsFormat.shard(name);
        return StoredListing.Spec.materialising(pod(repo, name), LINES, () -> generatePod(repo, name)).deriving(document -> {
            SortedMap<String, byte[]> versions = LINES.split(document.body());
            // The line carries the pod document's sequence, so the rebuild pass's regeneration of the shard, which can
            // lag this write, keeps this line, and a removal decided here stands against it.
            if (versions.isEmpty()) {
                LOGGER.debug("shard line of {} removed: pod document seq {} lists nothing", name,
                        document.header().seq());
                StoredListing.remove(store, shardSpec(repo, shard), name, document.header().seq());
            } else {
                String line = name + "/" + String.join("/", versions.keySet());
                LOGGER.debug("shard line of {} rewritten from pod document seq {}: {}", name, document.header().seq(),
                        line);
                StoredListing.put(store, shardSpec(repo, shard), name, line.getBytes(StandardCharsets.UTF_8),
                        document.header().seq());
            }
        });
    }

    StoredListing.Spec shardSpec(String repo, String[] shard) {
        return StoredListing.Spec.of(shard(repo, shard), SHARD, sink -> generateShard(repo, shard, sink));
    }

    private SortedMap<String, byte[]> generatePod(String repo, String name) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, name);
        for (String version : blobs.list(CocoaPodsFormat.shardPrefix(repo, CocoaPodsFormat.shard(name)) + "/" + name)) {
            Lifecycle.Flag flag = marks.get(version);
            if ((flag == null || flag.state() != LifecycleMark.YANKED)
                    && !blobs.withheld(CocoaPodsFormat.blobKey(repo, name, version))) {
                entries.put(version, version.getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** The shard from every pod's document, each line at its document's sequence, so a regeneration merges into the
     *  stored shard and a line derived from a later write survives. */
    private void generateShard(String repo, String[] shard, StoredListing.Generator.Sink sink) throws IOException {
        for (String name : new TreeSet<>(blobs.list(CocoaPodsFormat.shardPrefix(repo, shard)))) {
            // Each pod's list, materialised without the derivation that would update this very document.
            Optional<StoredListing.Document> document = StoredListing.read(store,
                    StoredListing.Spec.materialising(pod(repo, name), LINES, () -> generatePod(repo, name)));
            if (document.isEmpty()) {
                continue;
            }
            SortedMap<String, byte[]> versions = LINES.split(document.get().body());
            if (versions.isEmpty()) {
                sink.absent(name, document.get().header().seq());
            } else {
                sink.accept(name, (name + "/" + String.join("/", versions.keySet())).getBytes(StandardCharsets.UTF_8),
                        document.get().header().seq());
            }
        }
    }

    /** Regenerate the listing at this key if it is a CocoaPods one: a pod's list or a shard listing. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (!segments[0].equals("cocoapods") || segments.length < 3) {
            return false;
        }
        String repo = segments[1];
        if (segments.length == 4 && segments[2].equals("pods")) {
            StoredListing.rebuild(store, podSpec(repo, segments[3]));
            return true;
        }
        if (segments.length == 3 && segments[2].startsWith("all_pods_versions_") && segments[2].endsWith(".txt")) {
            String[] shard = segments[2].substring("all_pods_versions_".length(), segments[2].length() - ".txt".length())
                    .split("_", -1);
            if (shard.length == 3) {
                StoredListing.rebuild(store, shardSpec(repo, shard));
                return true;
            }
        }
        return false;
    }

    /** Re-decide one version's membership from the store's current state. */
    void refresh(String repo, String name, String version) throws IOException {
        boolean spec = blobs.exists(CocoaPodsFormat.specKey(repo, CocoaPodsFormat.shard(name), name, version));
        boolean withheld = spec && blobs.withheld(CocoaPodsFormat.blobKey(repo, name, version));
        boolean yanked = spec && !withheld
                && Lifecycle.read(store, name, version).filter(flag -> flag.state() == LifecycleMark.YANKED).isPresent();
        boolean servable = spec && !withheld && !yanked;
        LOGGER.debug("{} {} of {}: spec {}, withheld {}, yanked {}", servable ? "put" : "remove", version, name, spec,
                withheld, yanked);
        if (servable) {
            StoredListing.put(store, podSpec(repo, name), version, version.getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, podSpec(repo, name), version);
        }
    }
}
