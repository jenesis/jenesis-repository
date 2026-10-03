package build.jenesis.repository.format.helm;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * {@code index.yaml} as a stored listing: one entry per chart - that chart's whole block of versions - separated by a
 * blank line, which keeps the document valid YAML while it splits and rejoins by chart. A publish rewrites one chart's
 * block from its stored version stanzas, bounded by the chart's versions, never by the repository's charts. (A
 * per-chart list deriving a repository index would need two separators that collide.)
 *
 * <p>A version appears exactly when its archive is stored, not withheld and not yanked - the download's own screen,
 * stated once so the index cannot advertise a chart the fetch refuses.
 */
final class HelmListings {

    /** The frame Helm's index loader requires; it validates only {@code apiVersion}. There is no {@code generated}
     *  field: this document is amended per write, so no single moment generated it. */
    private static final String HEADER = "apiVersion: v1\nentries:\n";

    /** Charts are separated by a blank line; a chart's own versions are not, so the separator is unambiguous. */
    static final StoredListing.Codec INDEX = StoredListing.framed(HEADER, "",
            StoredListing.Codec.delimited("\n\n", HelmListings::chartOf));

    private final Blobs blobs;
    private final ArtifactStore store;

    HelmListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String index(String repo) {
        return "helm/" + repo + "/index.yaml";
    }

    StoredListing.Spec indexSpec(String repo) {
        return StoredListing.Spec.of(index(repo), INDEX, sink -> generate(repo, sink));
    }

    /** The chart a block belongs to: its first line is {@code "  <name>:"}, as the block is built. */
    private static String chartOf(String block) {
        String first = block.strip();
        int newline = first.indexOf('\n');
        if (newline >= 0) {
            first = first.substring(0, newline);
        }
        return first.endsWith(":") ? first.substring(0, first.length() - 1).strip() : first.strip();
    }

    /** Emit a block per chart in the scan's order - the store's lexicographic order - so the index is never collected
     *  into a map holding the repository. */
    private void generate(String repo, StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, HelmFormat.entryPrefix(repo), chart -> {
            Optional<byte[]> block = block(repo, chart);
            if (block.isPresent()) {
                sink.accept(chart, block.get());
            }
        });
    }

    /** The stride the repository-wide index is enumerated in. It drains: the index names every chart, so neither the
     *  names nor the round trips may cap it - a cap would omit charts or, at {@code steps x page}, throw and leave the
     *  document unmaterialised. What is bounded is how many names are in hand at once. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();


    /** One chart's block: its name as a key, then every servable version's stanza. Empty when no version is servable,
     *  which removes the chart from the index rather than leaving an empty key. */
    private Optional<byte[]> block(String repo, String chart) throws IOException {
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, chart);
        StringBuilder block = new StringBuilder("  ").append(chart).append(":\n");
        boolean any = false;
        List<String> versions = new ArrayList<>(blobs.list(HelmFormat.entryPrefix(repo) + "/" + chart));
        versions.sort(Comparator.reverseOrder());
        for (String version : versions) {
            Lifecycle.Flag flag = marks.get(version);
            if (flag != null && flag.state() == LifecycleMark.YANKED) {
                continue;
            }
            if (blobs.withheld(HelmFormat.blobKey(repo, chart, version))) {
                continue;
            }
            ByteArrayOutputStream stanza = new ByteArrayOutputStream();
            if (!blobs.read(HelmFormat.entryKey(repo, chart, version), stanza)) {
                continue;
            }
            block.append(new String(stanza.toByteArray(), StandardCharsets.UTF_8));
            if (flag != null && flag.state() == LifecycleMark.DEPRECATED) {
                // Helm's native word: a deprecated chart stays listed and resolvable and the client warns, so the mark
                // is rendered into the entry - the opposite of a yank.
                block.append("    deprecated: true\n");
            }
            any = true;
        }
        return any ? Optional.of(block.toString().getBytes(StandardCharsets.UTF_8)) : Optional.empty();
    }

    /** Regenerate the listing at this key if it is the Helm index. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (segments.length != 3 || !segments[0].equals("helm") || !segments[2].equals("index.yaml")) {
            return false;
        }
        StoredListing.rebuild(store, indexSpec(segments[1]));
        return true;
    }

    /** Re-decide one chart's whole block. */
    void refresh(String repo, String chart) throws IOException {
        Optional<byte[]> block = block(repo, chart);
        if (block.isPresent()) {
            StoredListing.put(store, indexSpec(repo), chart, block.get());
        } else {
            StoredListing.remove(store, indexSpec(repo), chart);
        }
    }
}
