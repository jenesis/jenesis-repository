package build.jenesis.repository.format.swift;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonParser;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A package's release list as a stored listing - the one document a Swift registry maintains.
 *
 * <p>The shape is the specification's: a JSON object under a top-level {@code releases} key, whose members are
 * version numbers. So the entries are those members, keyed by version, and a publish rewrites one of them.
 *
 * <h2>Two ways a release can be unavailable, and they are not the same</h2>
 *
 * <p>A withheld release <b>leaves the document</b>. Listing it would disclose that this repository holds a version
 * it has decided not to serve, which is the disclosure the withhold rule exists to prevent.
 *
 * <p>A <em>yanked</em> one stays, carrying the specification's own word for it: a {@code problem} object, which a
 * client is told to read as "unavailable for the purposes of package resolution". That is a native lifecycle
 * surface rather than an absence, so the mark is rendered into the entry - the same shape Helm's {@code deprecated}
 * has, and the opposite of the ecosystems whose only way to retire a version is to stop listing it.
 */
final class SwiftListings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The frame the specification requires; the members inside it are the releases. */
    private static final String HEADER = "{\"releases\":";

    private static final String FOOTER = "}";

    static final StoredListing.Codec RELEASES = StoredListing.framed(HEADER, FOOTER, new StoredListing.Codec() {

        @Override
        public SortedMap<String, byte[]> split(byte[] document) {
            SortedMap<String, byte[]> entries = new TreeMap<>();
            MAPPER.readTree(document).properties().forEach(member ->
                    entries.put(member.getKey(), MAPPER.writeValueAsBytes(member.getValue())));
            return entries;
        }

        /** The releases one member at a time through a streaming parser - the framed codec around this hands it
         *  the bare object - so a publish into a package never holds every release of it in heap. */
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

        /**
         * The same object, written as the releases arrive.
         *
         * <p>One package's releases is a bounded document, so this is not a memory fix - it is the parity half of
         * one. A codec that implements only {@code split} and {@code join} inherits an appender that collects
         * every entry into a map and joins it at close, which is the shape that made the OCI tag list fail after
         * three fixes above it; a format left in that state is a format whose generator streams into a buffer.
         */
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

    /** The release document endpoint 4.2 answers, written by the publish that knows the archive's digest. */
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

    /**
     * One release's entry, or empty when it should not be listed at all.
     *
     * <p>No {@code url} member. The specification makes it optional and tells a client to expand
     * {@code /{scope}/{name}/{version}} on the originating host when it is absent - so omitting it keeps a host
     * name out of a stored document, which is the difference between a document that survives being reached
     * through a different name and one that sends every client back to whichever host happened to publish.
     */
    private Optional<byte[]> entry(String repo, String scope, String name, String version) throws IOException {
        String archive = archiveKey(repo, scope, name, version);
        if (!blobs.exists(archive) || blobs.withheld(archive)) {
            return Optional.empty();
        }
        Optional<Lifecycle.Flag> flag = Lifecycle.read(store, scope + "." + name, version);
        if (flag.isPresent() && flag.get().state() == Lifecycle.State.YANKED) {
            // The specification's own word for a release a client must not resolve, and it stays LISTED - which is
            // what makes a yank different from a hold here rather than a second spelling of it.
            String detail = flag.get().message() == null || flag.get().message().isBlank()
                    ? "this release was removed from the registry"
                    : flag.get().message();
            return Optional.of(("{\"problem\":{\"status\":410,\"title\":\"Gone\",\"detail\":"
                    + MAPPER.writeValueAsString(detail) + "}}").getBytes(StandardCharsets.UTF_8));
        }
        return Optional.of("{}".getBytes(StandardCharsets.UTF_8));
    }

    /** The document as it would be built from the store - the first materialisation and the repair path. */
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
