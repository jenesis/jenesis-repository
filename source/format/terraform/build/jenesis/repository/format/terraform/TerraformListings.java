package build.jenesis.repository.format.terraform;

import module java.base;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonParser;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The three documents a Terraform client reads, each a stored listing the publish maintains: a module's version list, a
 * provider's version list, and a provider release's {@code SHA256SUMS} with its detached signature derived beside it.
 *
 * <p><b>Version documents are stored as served:</b> {@code {"modules":[{"versions":[...]}]}} and
 * {@code {"id":..,"versions":[...]}}, entries keyed by each element's {@code version}, so the served bytes are a
 * document with a stable validator rather than a render.
 *
 * <p><b>{@code SHA256SUMS}</b> is one line per platform keyed by its file, so a publish rewrites one line. Its
 * signature is a derived twin ordered by the document's sequence, so a client never verifies a signature against a list
 * it does not describe.
 */
final class TerraformListings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Each {@code SHA256SUMS} line is {@code <sha256>  <file>}, two spaces, as {@code sha256sum} writes it. */
    static final StoredListing.Codec SHA256SUMS =
            StoredListing.Codec.delimited("\n", TerraformListings::fileOf);

    private final Blobs blobs;

    private final ArtifactStore store;

    private final Function<Blobs, OpenPgpSigner> signer;

    TerraformListings(Blobs blobs, Function<Blobs, OpenPgpSigner> signer) {
        this.blobs = blobs;
        this.store = blobs.store();
        this.signer = signer;
    }

    static String moduleVersions(String repo, String namespace, String name, String system) {
        return TerraformCoordinates.ROOT + repo + "/v1/modules/" + namespace + "/" + name + "/" + system
                + "/versions";
    }

    static String providerVersions(String repo, String namespace, String type) {
        return TerraformCoordinates.ROOT + repo + "/v1/providers/" + namespace + "/" + type + "/versions";
    }

    static String shaSums(String repo, String namespace, String type, String version) {
        return TerraformCoordinates.ROOT + repo + "/providers/" + namespace + "/" + type + "/" + version
                + "/SHA256SUMS";
    }

    static String shaSumsSignature(String repo, String namespace, String type, String version) {
        return shaSums(repo, namespace, type, version) + ".sig";
    }

    /** The file a {@code SHA256SUMS} line names - everything after the digest and its two spaces. */
    private static String fileOf(String line) {
        int at = line.indexOf("  ");
        return at < 0 ? "" : line.substring(at + 2).strip();
    }

    StoredListing.Spec moduleVersionsSpec(String repo, String namespace, String name, String system) {
        String source = TerraformCoordinates.moduleCoordinate(namespace, name, system);
        return StoredListing.Spec.materialising(moduleVersions(repo, namespace, name, system),
                versions("{\"modules\":[{\"source\":" + MAPPER.writeValueAsString(source) + ",\"versions\":[", "]}]}"),
                () -> generateModuleVersions(repo, namespace, name, system));
    }

    StoredListing.Spec providerVersionsSpec(String repo, String namespace, String type) {
        String id = TerraformCoordinates.providerCoordinate(namespace, type);
        return StoredListing.Spec.materialising(providerVersions(repo, namespace, type),
                versions("{\"id\":" + MAPPER.writeValueAsString(id) + ",\"versions\":[", "]}"),
                () -> generateProviderVersions(repo, namespace, type));
    }

    /** The {@code SHA256SUMS} of one provider release, its detached signature - binary, as
     *  {@code registry.terraform.io}'s {@code .sig} - derived after every write. */
    StoredListing.Spec shaSumsSpec(String repo, String namespace, String type, String version) {
        return StoredListing.Spec.materialising(shaSums(repo, namespace, type, version), SHA256SUMS,
                        () -> generateShaSums(repo, namespace, type, version))
                .deriving(document -> {
                    OpenPgpSigner signing = signer.apply(blobs);
                    if (signing != null) {
                        StoredListing.derive(store, shaSumsSignature(repo, namespace, type, version),
                                document.header().seq(),
                                signing.detachedSignature(document.body(), OpenPgpSigner.Encoding.BINARY));
                    }
                });
    }

    /** A codec over a JSON array of version objects in a fixed header and footer, each element keyed by its
     *  {@code version} and kept verbatim, so replacing one entry never re-serialises the rest and an unmodelled field
     *  passes through. */
    private static StoredListing.Codec versions(String header, String footer) {
        return new StoredListing.Codec.Streaming() {

            /** The versions one element at a time through a streaming parser, from the {@code versions} array either
             *  shape carries. */
            @Override
            public Reader read(InputStream in, long ignored) throws IOException {
                JsonParser parser = MAPPER.createParser(in);
                return new Reader() {
                    private boolean inVersions;
                    private boolean drained;

                    @Override
                    public Optional<Map.Entry<String, byte[]>> next() {
                        while (!drained) {
                            JsonToken token = parser.nextToken();
                            if (token == null) {
                                drained = true;
                                break;
                            }
                            if (inVersions) {
                                if (token == JsonToken.END_ARRAY) {
                                    drained = true;             // the one versions array is the whole listing
                                    break;
                                }
                                JsonNode element = parser.readValueAsTree();
                                String version = element.path("version").asString("");
                                if (!version.isEmpty()) {
                                    return Optional.of(Map.entry(version, MAPPER.writeValueAsBytes(element)));
                                }
                                continue;
                            }
                            if (token == JsonToken.PROPERTY_NAME && "versions".equals(parser.currentName())) {
                                parser.nextToken();
                                if (parser.currentToken() == JsonToken.START_ARRAY) {
                                    inVersions = true;
                                } else {
                                    parser.skipChildren();
                                }
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

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                StringJoiner elements = new StringJoiner(",", header, footer);
                for (byte[] entry : entries.values()) {
                    elements.add(new String(entry, StandardCharsets.UTF_8));
                }
                return elements.toString().getBytes(StandardCharsets.UTF_8);
            }

            /** The same document, written as the versions arrive. Not {@code StoredListing.framed} over a
             *  comma-delimited codec: an element is a JSON object that may contain a comma. */
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
                        out.write(entry);
                    }

                    @Override
                    public void close() throws IOException {
                        open();                                 // an empty list is still the frame
                        out.write(footer.getBytes(StandardCharsets.UTF_8));
                    }

                    private void open() throws IOException {
                        if (!opened) {
                            out.write(header.getBytes(StandardCharsets.UTF_8));
                            opened = true;
                        }
                    }
                };
            }
        };
    }

    /** A module version was published: list it if it is servable, drop it otherwise. */
    void moduleRefresh(String repo, String namespace, String name, String system, String version) throws IOException {
        StoredListing.Spec spec = moduleVersionsSpec(repo, namespace, name, system);
        if (moduleServable(repo, namespace, name, system, version)) {
            StoredListing.put(store, spec, version, moduleEntry(version).getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, spec, version);
        }
    }

    /** A provider platform was published: re-decide the version's entry and its {@code SHA256SUMS} line. */
    void providerRefresh(String repo, String namespace, String type, String version, String file) throws IOException {
        String key = TerraformCoordinates.providerArchive(repo, namespace, type, version, file);
        Optional<Blobs.Located> located = blobs.locate(key);
        StoredListing.Spec sums = shaSumsSpec(repo, namespace, type, version);
        if (located.isPresent() && !blobs.withheld(key) && !yanked(namespace, type, version)) {
            StoredListing.put(store, sums, file,
                    (located.get().hash() + "  " + file).getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, sums, file);
        }
        providerVersionRefresh(repo, namespace, type, version);
    }

    /** Re-decide one provider version's entry from the platforms that are servable now. */
    void providerVersionRefresh(String repo, String namespace, String type, String version) throws IOException {
        StoredListing.Spec spec = providerVersionsSpec(repo, namespace, type);
        List<String[]> platforms = servablePlatforms(repo, namespace, type, version);
        if (platforms.isEmpty()) {
            StoredListing.remove(store, spec, version);
        } else {
            StoredListing.put(store, spec, version,
                    providerEntry(version, platforms).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A module version is servable when its archive is stored, published here rather than fetched from the upstream,
     *  not withheld and not yanked. */
    private boolean moduleServable(String repo, String namespace, String name, String system, String version)
            throws IOException {
        String key = TerraformCoordinates.moduleArchive(repo, namespace, name, system, version);
        return blobs.exists(key) && !blobs.withheld(key) && !TerraformCoordinates.isCached(store, key)
                && !yanked(TerraformCoordinates.moduleCoordinate(namespace, name, system), version);
    }

    private List<String[]> servablePlatforms(String repo, String namespace, String type, String version)
            throws IOException {
        List<String[]> platforms = new ArrayList<>();
        if (yanked(namespace, type, version)) {
            return platforms;
        }
        String prefix = TerraformCoordinates.ROOT + repo + "/providers/" + namespace + "/" + type + "/" + version;
        for (String file : blobs.list(prefix)) {
            if (blobs.withheld(prefix + "/" + file) || TerraformCoordinates.isCached(store, prefix + "/" + file)) {
                continue;
            }
            TerraformCoordinates.platformOf(type, version, file).ifPresent(platforms::add);
        }
        platforms.sort(Comparator.comparing(platform -> platform[0] + "_" + platform[1]));
        return platforms;
    }

    private boolean yanked(String namespace, String type, String version) throws IOException {
        return yanked(TerraformCoordinates.providerCoordinate(namespace, type), version);
    }

    private boolean yanked(String coordinate, String version) throws IOException {
        return Lifecycle.read(store, coordinate, version)
                .filter(flag -> flag.state() == LifecycleMark.YANKED)
                .isPresent();
    }

    /** A module version element: a version and the empty root the client expects. */
    private static String moduleEntry(String version) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("version", version);
        ObjectNode root = entry.putObject("root");
        root.putArray("providers");
        root.putArray("dependencies");
        entry.putArray("submodules");
        return MAPPER.writeValueAsString(entry);
    }

    /** A provider version element: the version, the plugin protocols it speaks and the platforms held. The protocols
     *  are declared, since they live in the binary's handshake; {@code 5.0} is what every provider since Terraform 0.12
     *  speaks. */
    private static String providerEntry(String version, List<String[]> platforms) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("version", version);
        entry.putArray("protocols").add("5.0");
        ArrayNode shapes = entry.putArray("platforms");
        for (String[] platform : platforms) {
            ObjectNode shape = shapes.addObject();
            shape.put("os", platform[0]);
            shape.put("arch", platform[1]);
        }
        return MAPPER.writeValueAsString(entry);
    }

    private SortedMap<String, byte[]> generateModuleVersions(String repo, String namespace, String name,
                                                             String system) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        String prefix = TerraformCoordinates.ROOT + repo + "/modules/" + namespace + "/" + name + "/" + system;
        for (String file : blobs.list(prefix)) {
            if (!file.endsWith(".tar.gz")) {
                continue;
            }
            String version = file.substring(0, file.length() - ".tar.gz".length());
            if (moduleServable(repo, namespace, name, system, version)) {
                entries.put(version, moduleEntry(version).getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private SortedMap<String, byte[]> generateProviderVersions(String repo, String namespace, String type)
            throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        String prefix = TerraformCoordinates.ROOT + repo + "/providers/" + namespace + "/" + type;
        for (String version : blobs.list(prefix)) {
            List<String[]> platforms = servablePlatforms(repo, namespace, type, version);
            if (!platforms.isEmpty()) {
                entries.put(version, providerEntry(version, platforms).getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private SortedMap<String, byte[]> generateShaSums(String repo, String namespace, String type, String version)
            throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        String prefix = TerraformCoordinates.ROOT + repo + "/providers/" + namespace + "/" + type + "/" + version;
        for (String file : blobs.list(prefix)) {
            if (TerraformCoordinates.platformOf(type, version, file).isEmpty() || blobs.withheld(prefix + "/" + file)
                    || TerraformCoordinates.isCached(store, prefix + "/" + file) || yanked(namespace, type, version)) {
                continue;
            }
            Optional<Blobs.Located> located = blobs.locate(prefix + "/" + file);
            if (located.isPresent()) {
                entries.put(file, (located.get().hash() + "  " + file).getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** Regenerate the listing at this key if it is a Terraform one; a derived signature comes back with its source. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (segments.length < 4 || !segments[0].equals("terraform")) {
            return false;
        }
        String repo = segments[1];
        if (segments.length == 7 && segments[2].equals("v1") && segments[3].equals("modules")
                && segments[6].equals("versions")) {
            return false;   // a module versions key is eight segments; seven is not one of ours
        }
        if (segments.length == 8 && segments[2].equals("v1") && segments[3].equals("modules")
                && segments[7].equals("versions")) {
            StoredListing.rebuild(store, moduleVersionsSpec(repo, segments[4], segments[5], segments[6]));
            return true;
        }
        if (segments.length == 7 && segments[2].equals("v1") && segments[3].equals("providers")
                && segments[6].equals("versions")) {
            StoredListing.rebuild(store, providerVersionsSpec(repo, segments[4], segments[5]));
            return true;
        }
        if (segments.length == 7 && segments[2].equals("providers") && segments[6].equals("SHA256SUMS")) {
            StoredListing.rebuild(store, shaSumsSpec(repo, segments[3], segments[4], segments[5]));
            return true;
        }
        return segments.length == 7 && segments[2].equals("providers") && segments[6].equals("SHA256SUMS.sig");
    }
}
