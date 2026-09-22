package build.jenesis.repository.format.go;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.format.Semver;

/**
 * A Go module's {@code @v/list} as a stored listing - one line per version - with {@code @latest} (the highest
 * semantic version of the list) derived from it on every write. A version is listed exactly when it is servable: its
 * {@code .info} and {@code .zip} pointers not withheld and the version not yanked - the screen the on-read
 * enumeration applied per version, applied here to the one version a write touches.
 */
final class GoListings {

    static final StoredListing.Codec LINES = StoredListing.Codec.delimited("\n", Function.identity());

    private final Blobs blobs;
    private final ArtifactStore store;

    GoListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String list(String modulePath) {
        return "go/" + modulePath + "/@v/list";
    }

    static String latest(String modulePath) {
        return "go/" + modulePath + "/@latest";
    }

    StoredListing.Spec spec(String modulePath) {
        return StoredListing.Spec.materialising(list(modulePath), LINES, () -> generate(modulePath)).deriving(document -> {
            SortedMap<String, byte[]> versions = LINES.split(document.body());
            String latest = versions.isEmpty() ? "" : Collections.max(versions.keySet(), Semver::compare);
            StoredListing.derive(store, latest(modulePath), document.header().seq(),
                    latest.getBytes(StandardCharsets.UTF_8));
        });
    }

    private SortedMap<String, byte[]> generate(String modulePath) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String name : blobs.list("go/" + modulePath + "/@v")) {
            if (!name.endsWith(".info")) {
                continue;
            }
            String version = name.substring(0, name.length() - ".info".length());
            if (servable(modulePath, version)) {
                entries.put(version, version.getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** Re-decide one version's membership from the store's current state - after a write under {@code @v}, a hold,
     *  a release or a mark. */
    void refresh(String modulePath, String version) throws IOException {
        if (blobs.exists("go/" + modulePath + "/@v/" + version + ".info") && servable(modulePath, version)) {
            StoredListing.put(store, spec(modulePath), version, version.getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, spec(modulePath), version);
        }
    }

    /** Regenerate the listing at this key if it is a Go module's list ({@code @latest} regenerates with it). */
    boolean rebuild(String listing) throws IOException {
        if (!listing.startsWith("go/")) {
            return false;
        }
        if (listing.endsWith("/@v/list")) {
            StoredListing.rebuild(store, spec(listing.substring("go/".length(), listing.length() - "/@v/list".length())));
            return true;
        }
        return listing.endsWith("/@latest");
    }

    private boolean servable(String modulePath, String version) throws IOException {
        String prefix = "go/" + modulePath + "/@v/" + version;
        if (blobs.withheld(prefix + ".info")
                || blobs.servableNames().keyState(prefix + ".zip") == ServableNames.State.WITHHELD) {
            return false;
        }
        return Lifecycle.read(store, modulePath, version)
                .filter(flag -> flag.state() == Lifecycle.State.YANKED)
                .isEmpty();
    }
}
