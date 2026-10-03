package build.jenesis.repository.format.swift;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonParser;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A package's release list as a stored listing: the specification's JSON object under {@code releases}, whose members -
 * one per version - are the entries a publish rewrites.
 *
 * <p>A withheld release <b>leaves the document</b>, since listing it would disclose a version the repository has
 * decided not to serve. A yanked one stays, carrying the specification's {@code problem} object, which a client reads
 * as "unavailable for package resolution" - a native lifecycle surface, the shape Helm's {@code deprecated} has.
 */
final class SwiftListings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The frame the specification requires; the members inside it are the releases. */
    private static final String HEADER = "{\"releases\":";

    private static final String FOOTER = "}";

    static final StoredListing.Codec RELEASES = StoredListing.framed(HEADER, FOOTER, new StoredListing.Codec.Streaming() {

        /** The releases one member at a time through a streaming parser, so a publish never holds every release in
         *  heap. */
        @Override
        public Reader read(InputStream in, long ignored) throws IOException {
            JsonParser parser = MAPPER.createParser(in);
            return new Reader() {
                private boolean drained = parser.nextToken() != JsonToken.START_OBJECT;

                @Override
                public Optional<Map.Entry<String, byte[]>> next() {
                    while (!drained) {
                        JsonToken token = parser.nextToken();
                        if (token == null || token == JsonToken.END_OBJECT) {
                            drained = true;
                            break;
                        }
                        if (token != JsonToken.PROPERTY_NAME) {
                            continue;
                        }
                        String version = parser.currentName();
                        parser.nextToken();
                        JsonNode member = parser.readValueAsTree();
                        return Optional.of(Map.entry(version, MAPPER.writeValueAsBytes(member)));
                    }
                    return Optional.empty();
                }

                @Override
                public void close() {
                    parser.close();
                }
            };
        }

        @Override
        public byte[] join(SortedMap<String, byte[]> entries) {
            ObjectNode members = MAPPER.createObjectNode();
            entries.forEach((version, value) -> members.set(version, MAPPER.readTree(value)));
            return MAPPER.writeValueAsBytes(members);
        }

        /** The same object, written as the releases arrive, so the generator streams rather than collecting every entry
         *  into a map before joining it. */
        @Override
        public Appender append(OutputStream out) {
            return new Appender() {

                private boolean opened, written;

                @Override
                public void append(String id, byte[] entry) throws IOException {
                    open();
                    if (written) {
                        out.write(',');
                    }
                    written = true;
                    out.write(MAPPER.writeValueAsString(id).getBytes(StandardCharsets.UTF_8));
                    out.write(':');
                    out.write(entry);
                }

                @Override
                public void close() throws IOException {
                    open();                                     // an empty document is still {}
                    out.write('}');
                }

                private void open() throws IOException {
                    if (!opened) {
                        out.write('{');
                        opened = true;
                    }
                }
            };
        }
    });

    private final Blobs blobs;

    private final ArtifactStore store;

    SwiftListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    // ---- names ----

    static String releases(String repo, String scope, String name) {
        return "swift/" + repo + "/" + scope + "/" + name + "/releases";
    }

    /** The package's own folder, which is also the raw container a {@code 404} is keyed on. */
    static String packagePrefix(String repo, String scope, String name) {
        return "swift/" + repo + "/" + scope + "/" + name;
    }

    static String archiveKey(String repo, String scope, String name, String version) {
        return packagePrefix(repo, scope, name) + "/" + version + ".zip";
    }

    static String manifestKey(String repo, String scope, String name, String version, String swiftVersion) {
        return packagePrefix(repo, scope, name) + "/" + version + "/Package"
                + (swiftVersion.isEmpty() ? "" : "@swift-" + swiftVersion) + ".swift";
    }

    /** The release document's key, written by the publish that knows the archive's digest. */
    static String metadataKey(String repo, String scope, String name, String version) {
        return packagePrefix(repo, scope, name) + "/" + version + "/metadata";
    }

    StoredListing.Spec releasesSpec(String repo, String scope, String name) {
        return StoredListing.Spec.materialising(releases(repo, scope, name), RELEASES, () -> generate(repo, scope, name));
    }

    // ---- the write path ----

    /** A release was published, held, released or marked: re-decide its one entry. */
    void refresh(String repo, String scope, String name, String version) throws IOException {
        StoredListing.Spec spec = releasesSpec(repo, scope, name);
        Optional<byte[]> entry = entry(repo, scope, name, version);
        if (entry.isPresent()) {
            StoredListing.put(store, spec, version, entry.get());
        } else {
            StoredListing.remove(store, spec, version);
        }
    }

    /** One release's entry, or empty when it is not listed. No {@code url} member: the specification makes it optional
     *  and a client then expands {@code /{scope}/{name}/{version}} on the host it asked, so the stored document names
     *  no host and survives being reached under another name. */
    private Optional<byte[]> entry(String repo, String scope, String name, String version) throws IOException {
        String archive = archiveKey(repo, scope, name, version);
        if (!blobs.exists(archive) || blobs.withheld(archive)) {
            return Optional.empty();
        }
        Optional<Lifecycle.Flag> flag = Lifecycle.read(store, scope + "." + name, version);
        if (flag.isPresent() && flag.get().state() == LifecycleMark.YANKED) {
            // The specification's word for a release a client must not resolve, and it stays listed - unlike a hold.
            String detail = flag.get().message() == null || flag.get().message().isBlank()
                    ? "this release was removed from the registry"
                    : flag.get().message();
            return Optional.of(("{\"problem\":{\"status\":410,\"title\":\"Gone\",\"detail\":"
                    + MAPPER.writeValueAsString(detail) + "}}").getBytes(StandardCharsets.UTF_8));
        }
        return Optional.of("{}".getBytes(StandardCharsets.UTF_8));
    }

    /** The document as built from the store - the first materialisation and the repair path. */
    private SortedMap<String, byte[]> generate(String repo, String scope, String name) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String file : blobs.list(packagePrefix(repo, scope, name))) {
            if (!file.endsWith(".zip")) {
                continue;
            }
            String version = file.substring(0, file.length() - ".zip".length());
            entry(repo, scope, name, version)
                    .ifPresent(value -> entries.put(version, value));
        }
        return entries;
    }

    /** Regenerate the listing at this key if it is a Swift release list. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (segments.length != 5 || !segments[0].equals("swift") || !segments[4].equals("releases")) {
            return false;
        }
        StoredListing.rebuild(store, releasesSpec(segments[1], segments[2], segments[3]));
        return true;
    }
}
