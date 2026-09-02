package build.jenesis.repository.format.maven;

import module java.base;

import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.store.StoredListing;

/**
 * The computed {@code maven-metadata.xml} of a coordinate as a stored listing, under the opt-in
 * {@link MavenMetadata#COMPUTE_SETTING}: the entries are the listed versions, and the one entry keyed {@code ""} is the
 * document's template - the publisher's own document with its {@code <versions>} block hollowed out - so every byte
 * outside the block is served as the publisher wrote it, and the block is rendered from the entries on every join.
 * {@code <latest>} and {@code <release>} stay as written; only when a hold or a yank removes the version one of them
 * names is that name re-derived from the versions that remain, as the on-read reconciliation did. A coordinate whose publisher never uploaded a
 * document has no template and is rendered whole. A version is listed exactly when its folder is disclosable and it
 * is not yanked - the screen the on-read reconciliation applied.
 *
 * <p>An artifact upload adds its version; a metadata upload resets the template and the versions from the uploaded
 * document; a hold or a yank removes its version; {@code .sha1}/{@code .md5} twins are derived on every write.
 */
final class MavenMetadataListing {

    private static final String TEMPLATE = "";

    /**
     * The places the template keeps for the sections a write substitutes. A control character delimits them because
     * no Maven metadata document can contain one, so a hole cannot be spelled by the content it stands in for. They
     * are written as escapes deliberately - as raw bytes they made this file {@code data} rather than text, which
     * silently excluded it from every recursive search of the tree.
     */
    private static final String VERSIONS_HOLE = "\u0001versions\u0001";
    private static final String LATEST_HOLE = "\u0001latest\u0001";
    private static final String RELEASE_HOLE = "\u0001release\u0001";

    /** The document's codec for one coordinate: the versions as entries, the rest as the template. */
    static StoredListing.Codec codec(String groupId, String artifactId) {
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                SortedMap<String, byte[]> entries = new TreeMap<>();
                String xml = new String(document, StandardCharsets.UTF_8);
                int open = xml.indexOf("<versions>");
                int close = open < 0 ? -1 : xml.indexOf("</versions>", open);
                if (open < 0 || close < 0) {
                    return entries;   // no versions block: nothing is listed (a derived document always has one)
                }
                for (String version : MavenMetadata.listedVersions(xml.substring(open + "<versions>".length(), close))) {
                    entries.put(version, version.getBytes(StandardCharsets.UTF_8));
                }
                String template = xml.substring(0, open + "<versions>".length()) + VERSIONS_HOLE
                        + xml.substring(close);
                entries.put(TEMPLATE, template.getBytes(StandardCharsets.UTF_8));
                return entries;
            }

            /**
             * <b>This one collects, and no appender can replace it.</b>
             *
             * <p>Two reasons, either of which would be enough. The document lists versions in <em>semantic</em>
             * order ({@link MavenMetadata#compareVersions}), which is not the ascending id order a {@code Sink}
             * delivers, so the order is a function of every entry and unknown until the last arrives. And the
             * surrounding XML is itself an entry - {@link #TEMPLATE}, carrying the document with a hole where the
             * versions go - so the frame is not known when the first version is written either.
             *
             * <p>{@code StoredListing.spooling} does not rescue this: a spool defers the opening bytes, it does
             * not reorder the body. The document is one coordinate's versions, so what is held is bounded by a
             * coordinate rather than by the repository - which is why this is acceptable and why it is written
             * down, since a codec silently lacking an appender is the defect that made a streaming generator
             * write into a buffer in six other formats.
             */
            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                List<String> versions = new ArrayList<>(entries.keySet());
                versions.remove(TEMPLATE);
                versions.sort(MavenMetadata::compareVersions);
                byte[] template = entries.get(TEMPLATE);
                if (template == null) {
                    return versions.isEmpty() ? new byte[0] : MavenMetadata.metadata(groupId, artifactId, versions);
                }
                String xml = new String(template, StandardCharsets.UTF_8);
                int hole = xml.indexOf(VERSIONS_HOLE);
                String indent = hole < 0 ? "" : MavenMetadata.indentBefore(xml, xml.lastIndexOf("<versions>", hole));
                StringBuilder block = new StringBuilder();
                for (String version : versions) {
                    block.append('\n').append(indent).append("  <version>").append(MavenMetadata.xmlText(version))
                            .append("</version>");
                }
                block.append('\n').append(indent);
                return xml.replace(VERSIONS_HOLE, block).getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private final ArtifactStore store;
    private final MavenMetadata metadata;

    MavenMetadataListing(ArtifactStore store) {
        this.store = store;
        this.metadata = new MavenMetadata(store);
    }

    static String listing(String coordinatePath) {
        return "maven/" + coordinatePath + "/maven-metadata.xml";
    }

    StoredListing.Spec spec(String coordinatePath) {
        int slash = coordinatePath.lastIndexOf('/');
        String artifactId = slash < 0 ? coordinatePath : coordinatePath.substring(slash + 1);
        String groupId = slash < 0 ? "" : coordinatePath.substring(0, slash).replace('/', '.');
        StoredListing.Codec codec = codec(groupId, artifactId);
        return StoredListing.Spec.materialising(listing(coordinatePath), codec, () -> generate(coordinatePath, codec))
                .deriving(document -> {
                    StoredListing.derive(store, listing(coordinatePath) + ".sha1", document.header().seq(),
                            Checksums.hex("SHA-1", document.body()).getBytes(StandardCharsets.UTF_8));
                    StoredListing.derive(store, listing(coordinatePath) + ".md5", document.header().seq(),
                            Checksums.hex("MD5", document.body()).getBytes(StandardCharsets.UTF_8));
                });
    }

    /** The reconciled document the read used to compute, split into its entries - the first materialisation and the
     *  reset a metadata upload makes. */
    private SortedMap<String, byte[]> generate(String coordinatePath, StoredListing.Codec codec) throws IOException {
        Optional<byte[]> computed = metadata.computed("/maven/" + coordinatePath + "/maven-metadata.xml");
        return computed.isEmpty() ? new TreeMap<>() : codec.split(computed.get());
    }

    /** Regenerate the listing at this key if it is a computed maven-metadata.xml (its checksums regenerate with it). */
    boolean rebuild(String listing) throws IOException {
        if (!listing.startsWith("maven/")) {
            return false;
        }
        if (listing.endsWith("/maven-metadata.xml")) {
            StoredListing.rebuild(store, spec(listing.substring("maven/".length(),
                    listing.length() - "/maven-metadata.xml".length())));
            return true;
        }
        return listing.endsWith("/maven-metadata.xml.sha1") || listing.endsWith("/maven-metadata.xml.md5");
    }

    /** A metadata document was uploaded: the listing is reset from it. */
    void uploaded(String coordinatePath) throws IOException {
        StoredListing.rebuild(store, spec(coordinatePath));
    }

    /** Re-decide one version's membership from the store's current state - after an upload, a hold, a release or a
     *  mark. */
    void refresh(String coordinatePath, String version) throws IOException {
        StoredListing.Spec spec = spec(coordinatePath);
        if (Lifecycle.read(store, MavenMetadata.mavenCoordinate(coordinatePath), version)
                .filter(flag -> flag.state() == Lifecycle.State.YANKED).isEmpty()
                && new ServableNames(store).disclosableVersionFolder("/maven/" + coordinatePath + "/" + version)) {
            StoredListing.put(store, spec, version, version.getBytes(StandardCharsets.UTF_8));
            return;
        }
        StoredListing.Changes changes = new StoredListing.Changes().remove(version);
        // A held or yanked version must not survive in <latest>/<release> either: when the template names it, the
        // name is re-derived from the versions that remain (the F5 rule of the on-read reconciliation).
        Optional<StoredListing.Document> current = StoredListing.read(store, spec);
        if (current.isPresent()) {
            SortedMap<String, byte[]> entries = spec.codec().split(current.get().body());
            byte[] template = entries.get(TEMPLATE);
            if (template != null) {
                List<String> remaining = new ArrayList<>(entries.keySet());
                remaining.remove(TEMPLATE);
                remaining.remove(version);
                remaining.sort(MavenMetadata::compareVersions);
                String xml = new String(template, StandardCharsets.UTF_8);
                String rewritten = xml;
                if (version.equals(MavenMetadata.element(xml, "latest"))) {
                    rewritten = MavenMetadata.rederiveElement(rewritten, "latest",
                            remaining.isEmpty() ? null : remaining.getLast());
                }
                if (version.equals(MavenMetadata.element(xml, "release"))) {
                    String release = null;
                    for (String candidate : remaining) {
                        if (!candidate.endsWith("-SNAPSHOT")) {
                            release = candidate;
                        }
                    }
                    rewritten = MavenMetadata.rederiveElement(rewritten, "release", release);
                }
                if (!rewritten.equals(xml)) {
                    changes.put(TEMPLATE, rewritten.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        StoredListing.update(store, spec, changes);
    }
}
