package build.jenesis.repository.format.cargo;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.databind.node.ObjectNode;

/**
 * A crate's sparse-index file as a stored listing: one stored index line per version, keyed by version, each
 * carrying its {@code yanked} flag from the lifecycle mark. A version is listed exactly when its crate pointer is not
 * withheld - the screen the on-read generation applied per version, applied here to the one version a write touches.
 */
final class CargoListings {

    static final StoredListing.Codec LINES = StoredListing.Codec.delimited("\n", line -> {
        return CargoFormat.MAPPER.readTree(line).path("vers").asString("");
    });

    private final Blobs blobs;
    private final ArtifactStore store;

    CargoListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String index(String repo, String crate) {
        return "cargo/" + repo + "/index/" + crate;
    }

    StoredListing.Spec spec(String repo, String crate) {
        return StoredListing.Spec.materialising(index(repo, crate), LINES, () -> generate(repo, crate));
    }

    private SortedMap<String, byte[]> generate(String repo, String crate) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, repo + "/" + crate);
        for (String version : blobs.list(CargoFormat.indexPrefix(repo, crate))) {
            if (blobs.withheld(CargoFormat.crateKey(repo, crate, version))) {
                continue;
            }
            byte[] line = line(repo, crate, version, marks.containsKey(version));
            if (line != null) {
                entries.put(version, line);
            }
        }
        return entries;
    }

    /** The stored index line of a version, with {@code yanked} set when the version carries a lifecycle mark. */
    private byte[] line(String repo, String crate, String version, boolean yanked) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(CargoFormat.indexKey(repo, crate, version), buffer)) {
            return null;
        }
        String line = buffer.toString(StandardCharsets.UTF_8).strip();
        if (yanked && CargoFormat.MAPPER.readTree(line.getBytes(StandardCharsets.UTF_8)) instanceof ObjectNode entry) {
            entry.put("yanked", true);
            line = CargoFormat.MAPPER.writeValueAsString(entry);
        }
        return line.getBytes(StandardCharsets.UTF_8);
    }

    /** Regenerate the listing at this key if it is a Cargo sparse-index file. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (segments.length != 4 || !segments[0].equals("cargo") || !segments[2].equals("index")) {
            return false;
        }
        StoredListing.rebuild(store, spec(segments[1], segments[3]));
        return true;
    }

    /** Re-decide one version's entry from the store's current state - after a publish, a hold, a release or a mark. */
    void refresh(String repo, String crate, String version) throws IOException {
        byte[] line = blobs.withheld(CargoFormat.crateKey(repo, crate, version)) ? null
                : line(repo, crate, version, Lifecycle.read(store, repo + "/" + crate, version).isPresent());
        if (line == null) {
            StoredListing.remove(store, spec(repo, crate), version);
        } else {
            StoredListing.put(store, spec(repo, crate), version, line);
        }
    }
}
