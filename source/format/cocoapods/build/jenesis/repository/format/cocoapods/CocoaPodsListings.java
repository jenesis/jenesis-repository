package build.jenesis.repository.format.cocoapods;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CocoaPods CDN shard listings as stored listings: a per-pod version list (entries by version) from which the
 * pod's line in its shard's {@code all_pods_versions_<a>_<b>_<c>.txt} is derived on every write, so a publish costs
 * one rewrite of the pod's list and one of its shard, never a scan of the shard's other pods. A version is listed
 * exactly when its archive pointer is not withheld and it is not yanked - the screen the on-read generation applied.
 */
final class CocoaPodsListings {

    /**
     * Every write of a pod's shard line and every membership decision behind it, at DEBUG. The soak read shard
     * lines that lacked versions the pod document had - three consecutive publishes of one pod left its line at
     * the snapshot before them for over half a minute (2026-09-12) - and nothing in the run could say who wrote
     * that line or from which document sequence. This trace did: every stale line was written by the maintenance
     * worker, the listing-rebuild pass regenerating the pod document beside a publish, and the regeneration ran
     * outside the listing's lane, so its derivation and the publish's raced into the shard. A rebuild rides the
     * lane now ({@code StoredListing.rebuild}). Switched on for the soak's node; silent elsewhere.
     */
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
            // The line carries the pod document's sequence as its source, so the rebuild pass's regeneration of
            // the shard - a snapshot of every pod document, which can be a beat behind this write - keeps this
            // line rather than the snapshot's older one, and a removal decided here stands against it too.
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
            if ((flag == null || flag.state() != Lifecycle.State.YANKED)
                    && !blobs.withheld(CocoaPodsFormat.blobKey(repo, name, version))) {
                entries.put(version, version.getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** The shard from every pod's document, each line stated at the sequence of the document it was read from -
     *  so a regeneration merges into the stored shard rather than replacing it, and a line a publish derived from
     *  a later write of its pod document than this walk read survives (the class comment of StoredListing). */
    private void generateShard(String repo, String[] shard, StoredListing.Generator.Sink sink) throws IOException {
        for (String name : new TreeSet<>(blobs.list(CocoaPodsFormat.shardPrefix(repo, shard)))) {
            // Each pod's list, materialised if need be - without the derivation that would update the very document
            // this generation is producing.
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

    /** Re-decide one version's membership from the store's current state - after a publish, a hold, a release or a
     *  mark. */
    void refresh(String repo, String name, String version) throws IOException {
        boolean spec = blobs.exists(CocoaPodsFormat.specKey(repo, CocoaPodsFormat.shard(name), name, version));
        boolean withheld = spec && blobs.withheld(CocoaPodsFormat.blobKey(repo, name, version));
        boolean yanked = spec && !withheld
                && Lifecycle.read(store, name, version).filter(flag -> flag.state() == Lifecycle.State.YANKED).isPresent();
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
