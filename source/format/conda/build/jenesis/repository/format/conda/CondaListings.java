package build.jenesis.repository.format.conda;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import build.jenesis.repository.store.OwnerOnly;

/**
 * A conda subdir's {@code repodata.json} as a stored listing, its {@code .bz2} twin derived on every write. The entries
 * are the stored records keyed by file name, present exactly when the package is servable: archive pointer not
 * withheld, version not yanked. The shape is conda's: {@code info}, {@code packages} ({@code .tar.bz2}),
 * {@code packages.conda} ({@code .conda}), {@code repodata_version}.
 */
final class CondaListings {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(CondaListings.class);


    private static final String CONDA_EXT = ".conda";

    private final Blobs blobs;
    private final ArtifactStore store;

    CondaListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String repodata(String repo, String subdir) {
        return "conda/" + repo + "/" + subdir + "/repodata.json";
    }

    /** The repodata codec of one subdir: the two package sections split into entries and joined back around it. */
    static StoredListing.Codec codec(String subdir) {
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                SortedMap<String, byte[]> entries = new TreeMap<>();
                JsonNode root = CondaFormat.MAPPER.readTree(document);
                for (String section : List.of("packages", "packages.conda")) {
                    root.path(section).properties()
                            .forEach(member -> entries.put(member.getKey(),
                                    CondaFormat.MAPPER.writeValueAsBytes(member.getValue())));
                }
                return entries;
            }

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                LinkedHashMap<String, byte[]> packages = new LinkedHashMap<>();
                LinkedHashMap<String, byte[]> packagesConda = new LinkedHashMap<>();
                entries.forEach((file, record) ->
                        (file.endsWith(CONDA_EXT) ? packagesConda : packages).put(file, record));
                ObjectNode root = CondaFormat.MAPPER.createObjectNode();
                root.putObject("info").put("subdir", subdir);
                members(CondaFormat.MAPPER, root.putObject("packages"), packages);
                members(CondaFormat.MAPPER, root.putObject("packages.conda"), packagesConda);
                root.put("repodata_version", 1);
                return CondaFormat.MAPPER.writeValueAsBytes(root);
            }

            /** The members of both sections, one at a time through a streaming parser, since the document is read on
             *  every update. */
            @Override
            public Reader read(InputStream in, long ignored) throws IOException {
                JsonParser parser = CondaFormat.MAPPER.createParser(in);
                return new Reader() {
                    private boolean inSection;
                    private boolean drained = parser.nextToken() != JsonToken.START_OBJECT;

                    @Override
                    public Optional<Map.Entry<String, byte[]>> next() {
                        while (!drained) {
                            JsonToken token = parser.nextToken();
                            if (token == null || token == JsonToken.END_OBJECT && !inSection) {
                                drained = true;
                                break;
                            }
                            if (token == JsonToken.END_OBJECT) {
                                inSection = false;              // a section closed; the next property is top-level
                                continue;
                            }
                            if (token != JsonToken.PROPERTY_NAME) {
                                continue;
                            }
                            String name = parser.currentName();
                            if (inSection) {
                                parser.nextToken();
                                JsonNode member = parser.readValueAsTree();
                                return Optional.of(Map.entry(name, CondaFormat.MAPPER.writeValueAsBytes(member)));
                            }
                            parser.nextToken();
                            if (("packages".equals(name) || "packages.conda".equals(name))
                                    && parser.currentToken() == JsonToken.START_OBJECT) {
                                inSection = true;
                            } else {
                                parser.skipChildren();          // info, repodata_version, or an unknown subtree
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

            /** The same document, written as the records arrive, with the {@code .conda} section spooled: a
             *  {@code Sink} delivers one ascending run spanning both sections, so the first is written out and the
             *  second to a temporary file appended at close, holding one record at a time. Not
             *  {@link StoredListing#spooling}, which defers a document's opening bytes rather than a whole section. */
            @Override
            public Appender append(OutputStream out) {
                return new Appender() {

                    private Path spool;

                    private OutputStream conda;

                    private boolean opened, wrotePlain, wroteConda;

                    @Override
                    public void append(String id, byte[] entry) throws IOException {
                        if (id.endsWith(CONDA_EXT)) {
                            openSpool();
                            wroteConda = member(conda, id, entry, wroteConda);
                        } else {
                            open();
                            wrotePlain = member(out, id, entry, wrotePlain);
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        open();                                 // an empty subdir is still a whole repodata
                        out.write("},\"packages.conda\":{".getBytes(StandardCharsets.UTF_8));
                        if (spool != null) {
                            try {
                                conda.close();
                                try (InputStream spooled =
                                             new BufferedInputStream(Files.newInputStream(spool))) {
                                    spooled.transferTo(out);
                                }
                            } finally {
                                Files.deleteIfExists(spool);
                            }
                        }
                        out.write("},\"repodata_version\":1}".getBytes(StandardCharsets.UTF_8));
                    }

                    private boolean member(OutputStream section, String id, byte[] entry, boolean written)
                            throws IOException {
                        if (written) {
                            section.write(',');
                        }
                        section.write(CondaFormat.MAPPER.writeValueAsString(id).getBytes(StandardCharsets.UTF_8));
                        section.write(':');
                        section.write(entry);
                        return true;
                    }

                    private void open() throws IOException {
                        if (!opened) {
                            out.write(("{\"info\":{\"subdir\":" + CondaFormat.MAPPER.writeValueAsString(subdir)
                                    + "},\"packages\":{").getBytes(StandardCharsets.UTF_8));
                            opened = true;
                        }
                    }

                    private void openSpool() throws IOException {
                        if (spool == null) {
                            spool = OwnerOnly.createTempFile("jenrepo-repodata-conda", ".tmp");
                            conda = new BufferedOutputStream(Files.newOutputStream(spool));
                        }
                    }
                };
            }
        };
    }

    /** One section of the repodata, written as nodes. */
    private static void members(ObjectMapper mapper, ObjectNode section, Map<String, byte[]> records) {
        records.forEach((file, record) -> section.set(file, mapper.readTree(record)));
    }

    StoredListing.Spec spec(String repo, String subdir) {
        // The .bz2 twin is derived off the publish's thread: compressing a large repodata costs more than the write,
        // and the twin is the legacy form. It lags its source by the derivation's time.
        return StoredListing.Spec.of(repodata(repo, subdir), codec(subdir), sink -> generate(repo, subdir, sink))
                .deriving(document -> {
                    // StoredListing.Derived clause 1: the body is readable only during this call and the compression
                    // runs later, so it is copied to a file this derivation owns first.
                    Path source = OwnerOnly.createTempFile("jenrepo-repodata", ".json");
                    try (InputStream body = document.open()) {
                        Files.copy(body, source, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException | RuntimeException failed) {
                        Files.deleteIfExists(source);
                        throw failed;
                    }
                    String twin = repodata(repo, subdir) + ".bz2";
                    long seq = document.header().seq();
                    // Coalesced: later() replaces a derivation still waiting for the same twin, and superseded()
                    // deletes the copy a replaced one leaves.
                    StoredListing.later(twin, new StoredListing.Coalesced() {

                        @Override
                        public void superseded() {
                            try {
                                Files.deleteIfExists(source);
                            } catch (IOException tidying) {
                                LOGGER.debug("the repodata copy {} was left behind", source, tidying);
                            }
                        }

                        @Override
                        public void run() {
                        try {
                            Path compressed = OwnerOnly.createTempFile("jenrepo-repodata", ".bz2");
                            try {
                                StoredListing.Header header = CondaFormat.bzip2(source, compressed, seq);
                                StoredListing.derive(store, twin, header, header.size(),
                                        () -> new BufferedInputStream(Files.newInputStream(compressed)));
                            } finally {
                                Files.deleteIfExists(compressed);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        } finally {
                            try {
                                Files.deleteIfExists(source);
                            } catch (IOException tidying) {
                                LOGGER.debug("the repodata copy {} was left behind", source, tidying);
                            }
                        }
                        }
                    });
                });
    }

    /** Emit an entry per package in the scan's order, the store's lexicographic child order, which the sink needs since
     *  the key is the child name itself. */
    private void generate(String repo, String subdir, StoredListing.Generator.Sink sink) throws IOException {
        String prefix = CondaFormat.indexPrefix(repo, subdir);
        ENTRIES.scan(store, prefix, file -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (blobs.read(prefix + "/" + file, buffer) && servable(repo, subdir, file, buffer.toByteArray())) {
                sink.accept(file, buffer.toByteArray());
            }
        });
    }

    /** A package was published (or imported): list it if it is servable. */
    void published(String repo, String subdir, String file, byte[] record) throws IOException {
        if (servable(repo, subdir, file, record)) {
            StoredListing.put(store, spec(repo, subdir), file, record);
        } else {
            StoredListing.remove(store, spec(repo, subdir), file);
        }
    }

    /** Re-decide one package's membership from the store's current state. */
    void refresh(String repo, String subdir, String file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(CondaFormat.indexKey(repo, subdir, file), buffer)) {
            StoredListing.remove(store, spec(repo, subdir), file);
            return;
        }
        published(repo, subdir, file, buffer.toByteArray());
    }

    private boolean servable(String repo, String subdir, String file, byte[] record) throws IOException {
        if (blobs.withheld(CondaFormat.packageKey(repo, subdir, file))) {
            return false;
        }
        JsonNode parsed = CondaFormat.MAPPER.readTree(record);
        String name = CondaFormat.text(parsed, "name");
        String version = CondaFormat.text(parsed, "version");
        if (name == null || version == null) {
            return true;
        }
        // A YANKED version leaves the index, its bytes still fetchable, as conda retires a build. Conda has no
        // deprecation.
        return Lifecycle.read(store, name, version)
                .filter(flag -> flag.state() == LifecycleMark.YANKED)
                .isEmpty();
    }

    /** Regenerate the listing at this key if it is a conda repodata; its {@code .bz2} twin regenerates with it. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (!segments[0].equals("conda") || segments.length != 4) {
            return false;
        }
        if (segments[3].equals("repodata.json")) {
            StoredListing.rebuild(store, spec(segments[1], segments[2]));
            return true;
        }
        return segments[3].equals("repodata.json.bz2");
    }

    /** The stride the repository-wide index is enumerated in. It drains: the index names every package, so neither
     *  names nor round-trips are capped - a cap would omit packages or throw and never materialise the document - and
     *  only the names in hand are bounded. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();
}
