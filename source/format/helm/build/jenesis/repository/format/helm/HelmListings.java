package build.jenesis.repository.format.helm;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * {@code index.yaml} as a stored listing: one entry per chart, each entry being that chart's whole block of versions,
 * separated from the next chart by a blank line - which is what lets the document split and rejoin by chart while
 * staying valid YAML, since blank lines between sequence items are whitespace.
 *
 * <p>One listing rather than two. A per-chart version list deriving a repository index would mirror the CocoaPods
 * shard shape, but the two levels would need two separators and the inner one would collide with the outer. Keying
 * the single document by chart instead makes a publish rewrite that chart's block, read from its own stored version
 * stanzas - bounded by how many versions the chart has, never by how many charts the repository holds.
 *
 * <p>A version appears exactly when its archive is stored, is not withheld and is not yanked. That is the same screen
 * the download applies, written once here so the index cannot advertise a chart the fetch will refuse.
 */
final class HelmListings {

    /** The frame Helm's index loader requires. {@code apiVersion} is the one field it validates; {@code generated} is
     *  informational and is deliberately absent, because this document is amended per write and was never generated at
     *  any single moment - see the module javadoc. */
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

    /** The chart a block belongs to: its first line is {@code "  <name>:"}, which the block is built to guarantee. */
    private static String chartOf(String block) {
        String first = block.strip();
        int newline = first.indexOf('\n');
        if (newline >= 0) {
            first = first.substring(0, newline);
        }
        return first.endsWith(":") ? first.substring(0, first.length() - 1).strip() : first.strip();
    }

    /**
     * Emit a block per chart, in the order the scan yields them.
     *
     * <p>The index names every chart in the repository, so collecting them into a map held the repository. The
     * scan's order is the sink's order: {@code BoundedChildren} delivers children in the store's lexicographic
     * order, which is where the sorted map's ordering came from and is now the thing that supplies it.
     */
    private void generate(String repo, StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, HelmFormat.entryPrefix(repo), chart -> {
            Optional<byte[]> block = block(repo, chart);
            if (block.isPresent()) {
                sink.accept(chart, block.get());
            }
        });
    }

    /** The stride the repository-wide index is enumerated in. It <b>drains</b>: the index names every package by
     *  definition, so neither the names nor the round-trips that fetch them may cap it, and what is bounded is how
     *  many names are in hand at once. Capping either one silently omits packages - or, once the entry cap alone was
     *  lifted, stopped omitting them and started throwing instead, at exactly {@code steps x page} names. That is
     *  the ceiling the OCI tag canary hit at a million: a generator that raises {@code TraversalException} does not
     *  answer short, it never materialises the document at all. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();


    /**
     * One chart's block: its name as a key, then every servable version's stanza in order. Empty when the chart has no
     * servable version left, which is what removes it from the index rather than leaving an empty key behind.
     */
    private Optional<byte[]> block(String repo, String chart) throws IOException {
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, chart);
        StringBuilder block = new StringBuilder("  ").append(chart).append(":\n");
        boolean any = false;
        List<String> versions = new ArrayList<>(blobs.list(HelmFormat.entryPrefix(repo) + "/" + chart));
        versions.sort(Comparator.reverseOrder());
        for (String version : versions) {
            Lifecycle.Flag flag = marks.get(version);
            if (flag != null && flag.state() == Lifecycle.State.YANKED) {
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
            if (flag != null && flag.state() == Lifecycle.State.DEPRECATED) {
                // Helm has a native word for this: a chart marked deprecated stays listed and resolvable, and the
                // client warns on it. So the mark is rendered into the entry rather than removed from the document -
                // the opposite of a yank, which is why the two states are not one screen.
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

    /** Re-decide one chart's whole block - after a publish, a hold, a release, a mark or a removal. */
    void refresh(String repo, String chart) throws IOException {
        Optional<byte[]> block = block(repo, chart);
        if (block.isPresent()) {
            StoredListing.put(store, indexSpec(repo), chart, block.get());
        } else {
            StoredListing.remove(store, indexSpec(repo), chart);
        }
    }
}
