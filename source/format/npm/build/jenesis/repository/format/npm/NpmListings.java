package build.jenesis.repository.format.npm;

import module java.base;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonParser;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import build.jenesis.repository.format.Semver;

/**
 * An npm package's packument as a stored listing: the entries are the version documents the publish stored
 * ({@code v:<version>}, completed with their tarball URL and lifecycle deprecation) and the dist-tags
 * ({@code tag:<tag>}). The tarball URL names the host the registry is reached at, so it is stored as the
 * {@value #BASE} placeholder and completed on the way out.
 *
 * <p>A version is listed exactly when its tarball pointer is not withheld, and a dist-tag exactly when its target
 * version is listed - the screens the on-read generation applied. A package with no stored dist-tags document gets
 * a computed {@code latest}, the highest listed semantic version, as before.
 */
final class NpmListings {

    static final String BASE = "{{jenesis-base}}";

    private static final String VERSION = "v:";
    private static final String TAG = "tag:";

    private final Blobs blobs;
    private final ArtifactStore store;

    NpmListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String packument(String name) {
        return "npm/" + name + "/packument";
    }

    /** The packument codec of one package: {@code {"name":..,"versions":{..},"dist-tags":{..}}}. With
     *  {@code computeLatest}, the dist-tags are not entries but the highest listed version, computed on join. */
    static StoredListing.Codec codec(String name, boolean computeLatest) {
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                SortedMap<String, byte[]> entries = new TreeMap<>();
                JsonNode root = NpmFormat.MAPPER.readTree(document);
                root.path("versions").properties().forEach(version ->
                        entries.put(VERSION + version.getKey(), NpmFormat.MAPPER.writeValueAsBytes(version.getValue())));
                if (!computeLatest) {
                    root.path("dist-tags").properties().forEach(tag ->
                            entries.put(TAG + tag.getKey(), NpmFormat.MAPPER.writeValueAsBytes(tag.getValue())));
                }
                return entries;
            }

            /** The versions - and, when the tags are stored, the tags - one member at a time through a streaming
             *  parser: the listing mechanism reads the packument through this on every publish into the package,
             *  and without it fell back to reading the whole document into heap and splitting it as a tree - a
             *  package's every version several times over in heap per publish, which the npm-packument canary
             *  showed as a failed publish at fifty thousand versions in a 512 MiB container. */
            @Override
            public Reader read(InputStream in, long ignored) throws IOException {
                JsonParser parser = NpmFormat.MAPPER.createParser(in);
                return new Reader() {
                    private String section;
                    private boolean drained = parser.nextToken() != JsonToken.START_OBJECT;

                    @Override
                    public Optional<Map.Entry<String, byte[]>> next() {
                        while (!drained) {
                            JsonToken token = parser.nextToken();
                            if (token == null || token == JsonToken.END_OBJECT && section == null) {
                                drained = true;
                                break;
                            }
                            if (token == JsonToken.END_OBJECT) {
                                section = null;                 // a section closed; the next property is top-level
                                continue;
                            }
                            if (token != JsonToken.PROPERTY_NAME) {
                                continue;
                            }
                            String name = parser.currentName();
                            if (section != null) {
                                parser.nextToken();
                                JsonNode member = parser.readValueAsTree();
                                return Optional.of(Map.entry(section + name, NpmFormat.MAPPER.writeValueAsBytes(member)));
                            }
                            parser.nextToken();
                            if ("versions".equals(name) && parser.currentToken() == JsonToken.START_OBJECT) {
                                section = VERSION;
                            } else if (!computeLatest && "dist-tags".equals(name)
                                    && parser.currentToken() == JsonToken.START_OBJECT) {
                                section = TAG;
                            } else {
                                parser.skipChildren();          // name, dist-tags when computed, an unknown subtree
                            }
                        }
                        return Optional.empty();
                    }

                    @Override
                    public void close() {
                        parser.close();
                    }
                };
            }

            /**
             * The packument, written as the entries arrive.
             *
             * <p>Two sections and a summary, none of which forces the document to be held. A {@code Sink}
             * delivers one ascending run, and {@code TAG } sorts before {@code VERSION }, so every tag arrives
             * before the first version - which is the opposite of the order they are written in. That inversion
             * is what to design around, and it costs less here than {@code CondaListings} pays: a package has a
             * handful of dist-tags, so the ones that arrive early are held in a map rather than spooled to a
             * file, while the versions - the part that grows - stream straight out as they come.
             *
             * <p>What is still held is one <em>name</em> per version, because a tag is disclosed only when its
             * target is a listed version and that cannot be known until the versions have gone by. A name is not
             * a version's metadata: this holds a set of short strings where {@link #join} held every version
             * object in the package.
             */
            @Override
            public Appender append(OutputStream out) {
                return new Appender() {

                    private final Map<String, byte[]> tags = new LinkedHashMap<>();

                    private final Set<String> listed = new HashSet<>();

                    private String latest;

                    private boolean opened, wroteVersion;

                    @Override
                    public void append(String id, byte[] entry) throws IOException {
                        if (id.startsWith(TAG)) {
                            tags.put(id.substring(TAG.length()), entry);   // written at close, after the versions
                            return;
                        }
                        open();
                        String version = id.substring(VERSION.length());
                        listed.add(version);
                        if (latest == null || Semver.compare(version, latest) > 0) {
                            latest = version;
                        }
                        if (wroteVersion) {
                            out.write(',');
                        }
                        wroteVersion = true;
                        out.write(NpmFormat.MAPPER.writeValueAsString(version).getBytes(StandardCharsets.UTF_8));
                        out.write(':');
                        out.write(entry);
                    }

                    @Override
                    public void close() throws IOException {
                        open();
                        out.write("},\"dist-tags\":{".getBytes(StandardCharsets.UTF_8));
                        ObjectNode resolved = NpmFormat.MAPPER.createObjectNode();
                        if (computeLatest) {
                            if (latest != null) {
                                resolved.put("latest", latest);
                            }
                        } else {
                            tags.forEach((tag, fragment) -> {
                                JsonNode target = NpmFormat.MAPPER.readTree(fragment);
                                // A tag whose target is not listed (held, or never published) is not disclosed.
                                if (listed.contains(target.asString(""))) {
                                    resolved.set(tag, target);
                                }
                            });
                        }
                        boolean wroteTag = false;
                        for (Map.Entry<String, JsonNode> tag : resolved.properties()) {
                            if (wroteTag) {
                                out.write(',');
                            }
                            wroteTag = true;
                            out.write(NpmFormat.MAPPER.writeValueAsString(tag.getKey()).getBytes(StandardCharsets.UTF_8));
                            out.write(':');
                            out.write(NpmFormat.MAPPER.writeValueAsBytes(tag.getValue()));
                        }
                        out.write("}}".getBytes(StandardCharsets.UTF_8));
                    }

                    private void open() throws IOException {
                        if (!opened) {
                            out.write(("{\"name\":" + NpmFormat.MAPPER.writeValueAsString(name) + ",\"versions\":{")
                                    .getBytes(StandardCharsets.UTF_8));
                            opened = true;
                        }
                    }
                };
            }

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                ObjectNode root = NpmFormat.MAPPER.createObjectNode();
                root.put("name", name);
                ObjectNode versions = root.putObject("versions");
                entries.forEach((id, fragment) -> {
                    if (id.startsWith(VERSION)) {
                        versions.set(id.substring(VERSION.length()), NpmFormat.MAPPER.readTree(fragment));
                    }
                });
                ObjectNode tags = root.putObject("dist-tags");
                if (computeLatest) {
                    versions.propertyNames().stream().max(Semver::compare)
                            .ifPresent(latest -> tags.put("latest", latest));
                } else {
                    entries.forEach((id, fragment) -> {
                        if (id.startsWith(TAG)) {
                            JsonNode target = NpmFormat.MAPPER.readTree(fragment);
                            // A tag whose target is not listed (held, or never published) is not disclosed.
                            if (versions.has(target.asString(""))) {
                                tags.set(id.substring(TAG.length()), target);
                            }
                        }
                    });
                }
                return NpmFormat.MAPPER.writeValueAsBytes(root);
            }
        };
    }

    StoredListing.Spec spec(String name) throws IOException {
        boolean computeLatest = !blobs.exists("npm/" + name + "/dist-tags");
        return StoredListing.Spec.materialising(packument(name), codec(name, computeLatest), () -> generate(name));
    }

    private SortedMap<String, byte[]> generate(String name) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        String shortName = NpmFormat.shortName(name);
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, name);
        for (String version : blobs.list("npm/" + name + "/versions")) {
            if (blobs.withheld(NpmFormat.tarballKey(name, shortName, version))) {
                continue;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (blobs.read("npm/" + name + "/versions/" + version, buffer)) {
                byte[] rendered = render(buffer.toByteArray(), shortName, version, marks.get(version));
                if (rendered != null) {
                    entries.put(VERSION + version, rendered);
                }
            }
        }
        entries.putAll(storedTags(name));
        return entries;
    }

    /** The {@code tag:} entries of the stored dist-tags document, or none when there is no such document. */
    private SortedMap<String, byte[]> storedTags(String name) throws IOException {
        SortedMap<String, byte[]> tags = new TreeMap<>();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (blobs.read("npm/" + name + "/dist-tags", buffer)) {
            try {
                NpmFormat.MAPPER.readTree(buffer.toByteArray()).properties().forEach(tag ->
                        tags.put(TAG + tag.getKey(), NpmFormat.MAPPER.writeValueAsBytes(tag.getValue())));
            } catch (RuntimeException malformed) {
                // a dist-tags document that is not a JSON object lists no tags, as before
            }
        }
        return tags;
    }

    /** A version's packument entry: its stored metadata with the tarball URL (the placeholder base) and the
     *  lifecycle deprecation; {@code null} when the stored metadata is not a JSON object. */
    private static byte[] render(byte[] metadata, String shortName, String version, Lifecycle.Flag flag)
            throws IOException {
        if (!(NpmFormat.MAPPER.readTree(metadata) instanceof ObjectNode object)) {
            return null;
        }
        ObjectNode dist = object.get("dist") instanceof ObjectNode existing ? existing : object.putObject("dist");
        dist.put("tarball", BASE + "/-/" + shortName + "-" + version + ".tgz");
        if (flag != null) {
            object.put("deprecated", NpmFormat.deprecation(flag));
        }
        return NpmFormat.MAPPER.writeValueAsBytes(object);
    }

    /** Regenerate the listing at this key if it is an npm packument. */
    boolean rebuild(String listing) throws IOException {
        if (!listing.startsWith("npm/") || !listing.endsWith("/packument")) {
            return false;
        }
        StoredListing.rebuild(store, spec(listing.substring("npm/".length(), listing.length() - "/packument".length())));
        return true;
    }

    /** An envelope was indexed: its versions and (when it carried them) its dist-tags join the stored packument. */
    void published(String name, Map<String, byte[]> versions, boolean distTags) throws IOException {
        String shortName = NpmFormat.shortName(name);
        StoredListing.Changes changes = new StoredListing.Changes();
        boolean listed = false;
        for (String version : versions.keySet()) {
            listed |= change(changes, name, shortName, version);
        }
        // Dist-tags join the document only once it lists a version: an envelope that published no tarball has no
        // packument, so its tags alone must not bring one into being.
        if (distTags && (listed || StoredListing.present(store, packument(name)))) {
            changes.removePrefix(TAG);
            storedTags(name).forEach(changes::put);
        }
        if (!changes.isEmpty()) {
            StoredListing.update(store, spec(name), changes);
        }
    }

    /** Re-decide one version's entry from the store's current state - after a hold, a release or a mark - and
     *  re-admit the stored dist-tags, so a tag screened out while its target was held returns with it. */
    void refresh(String name, String version) throws IOException {
        StoredListing.Changes changes = new StoredListing.Changes();
        change(changes, name, NpmFormat.shortName(name), version);
        storedTags(name).forEach(changes::put);
        StoredListing.update(store, spec(name), changes);
    }

    /** Put or remove one version's entry into {@code changes}; whether it was put. */
    private boolean change(StoredListing.Changes changes, String name, String shortName, String version)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] rendered = null;
        if (!blobs.withheld(NpmFormat.tarballKey(name, shortName, version))
                && blobs.read("npm/" + name + "/versions/" + version, buffer)) {
            rendered = render(buffer.toByteArray(), shortName, version,
                    Lifecycle.read(store, name, version).orElse(null));
        }
        if (rendered == null) {
            changes.remove(VERSION + version);
            return false;
        }
        changes.put(VERSION + version, rendered);
        return true;
    }
}
