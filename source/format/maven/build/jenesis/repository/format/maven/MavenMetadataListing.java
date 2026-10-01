package build.jenesis.repository.format.maven;

import module java.base;

import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.store.StoredListing;

/**
 * The computed {@code maven-metadata.xml} of a coordinate as a stored listing, under the opt-in
 * {@link MavenMetadata#COMPUTE_SETTING}. The entries are the listed versions, and the entry keyed {@code ""} is the
 * template - the publisher's document with its {@code <versions>} hollowed out - so every byte outside the block serves
 * as written. {@code <latest>} and {@code <release>} stay as written unless a hold or yank removes the version they
 * name, when the name is re-derived from the versions that remain. A coordinate with no uploaded document has no
 * template and is rendered whole. A version is listed when its folder is disclosable and it is not yanked.
 *
 * <p>An artifact upload adds its version; a metadata upload resets the listing from the document; a hold or yank
 * removes its version; the {@code .sha1}/{@code .md5} twins are derived on every write.
 */
final class MavenMetadataListing {

    private static final String TEMPLATE = "";

    /** The holes the template keeps for the sections a write substitutes, delimited by a control character no Maven
     *  metadata document can contain. Written as escapes so this file stays text to every search of the tree. */
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

            /** <b>This one collects, and no appender can replace it.</b> Versions are listed in Maven order
             *  ({@link MavenMetadata#compareVersions}), not the ascending order a {@code Sink} delivers, and the frame
             *  is itself an entry ({@link #TEMPLATE}), so neither is known before the last entry arrives. What is held
             *  is one coordinate's versions. */
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

    /** The reconciled document split into entries: the first materialisation, and the reset a metadata upload makes. */
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

    /** Re-decide one version's membership from the store's current state. */
    void refresh(String coordinatePath, String version) throws IOException {
        StoredListing.Spec spec = spec(coordinatePath);
        if (Lifecycle.read(store, MavenMetadata.mavenCoordinate(coordinatePath), version)
                .filter(flag -> flag.state() == Lifecycle.State.YANKED).isEmpty()
                && new ServableNames(store).disclosableVersionFolder("/maven/" + coordinatePath + "/" + version)) {
            StoredListing.put(store, spec, version, version.getBytes(StandardCharsets.UTF_8));
            return;
        }
        StoredListing.Changes changes = new StoredListing.Changes().remove(version);
        // A held or yanked version named by <latest>/<release> in the template is re-derived from the versions that
        // remain.
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
