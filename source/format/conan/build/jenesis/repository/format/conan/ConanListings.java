package build.jenesis.repository.format.conan;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.JsonToken;

/**
 * A Conan recipe's and package's revision index as stored listings: the {@code revisions} document of a parent (a
 * recipe reference or a package id under a recipe revision), whose entries are its revisions with their upload
 * times, newest first; its {@code latest}, derived from the revisions on every write as the newest entry; and the
 * {@code files} document of one revision, whose entries are the file names it serves. Each lives beside the raw
 * subtree it describes, under an {@code @}-prefixed name no client-computed revision carries.
 *
 * <p>A file is listed exactly when its pointer is not withheld - the screen the on-read generation applied per
 * file; a revision is listed exactly when it still exists and either lists a file or holds none at all (a revision
 * nothing has been uploaded to names no withheld coordinate, so only a hold that took every file unlists it).
 */
final class ConanListings {

    /** The listing names beside a raw parent ({@code revisions}, {@code latest}) and a raw revision ({@code files}). */
    static final String REVISIONS = "@revisions";
    static final String LATEST = "@latest";
    static final String FILES = "@files";

    /** {@code {"revisions":[{"revision":"<rev>","time":"<iso>"},...]}}, entries by revision, newest first. */
    static final StoredListing.Codec REVISION_ENTRIES = new StoredListing.Codec() {
        @Override
        public SortedMap<String, byte[]> split(byte[] document) {
            SortedMap<String, byte[]> entries = new TreeMap<>();
            for (JsonNode element : ConanFormat.MAPPER.readTree(document).path("revisions")) {
                entries.put(element.path("revision").asString(""), ConanFormat.MAPPER.writeValueAsBytes(element));
            }
            return entries;
        }

        /**
         * <b>This one collects, and no appender can replace it.</b>
         *
         * <p>Every other codec here writes its entries in the ascending id order a {@code Sink} delivers them in,
         * which is why an appender can emit as they arrive. This document is ordered by {@link #ordered} - newest
         * revision first, by a timestamp read out of each entry - so its order is a function of <em>all</em> the
         * entries and is not knowable until the last one has been seen. A spooling appender does not help either:
         * a spool defers the opening bytes, it does not reorder the body.
         *
         * <p>The document is one recipe's revisions, so collecting it is bounded by what a publisher pushes to one
         * recipe rather than by the repository. That is the reason this is acceptable, and it is worth saying
         * outright: "the codec has no appender" is otherwise indistinguishable from the oversight that made a
         * streaming generator write into a buffer everywhere else.
         */
        @Override
        public byte[] join(SortedMap<String, byte[]> entries) {
            StringBuilder array = new StringBuilder("{\"revisions\":[");
            boolean first = true;
            for (String revision : ordered(entries)) {
                if (!first) {
                    array.append(',');
                }
                first = false;
                array.append(new String(entries.get(revision), StandardCharsets.UTF_8));
            }
            return array.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        }
    };

    /** {@code {"files":{"<name>":{},...}}}, entries by file name. */
    static final StoredListing.Codec FILE_ENTRIES = new StoredListing.Codec() {
        @Override
        public SortedMap<String, byte[]> split(byte[] document) {
            SortedMap<String, byte[]> entries = new TreeMap<>();
            try (Reader reader = read(new ByteArrayInputStream(document), document.length)) {
                for (Optional<Map.Entry<String, byte[]>> entry = reader.next();
                     entry.isPresent(); entry = reader.next()) {
                    entries.put(entry.get().getKey(), entry.get().getValue());
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
            return entries;
        }

        @Override
        public byte[] join(SortedMap<String, byte[]> entries) {
            LinkedHashMap<String, String> files = new LinkedHashMap<>();
            entries.forEach((name, value) -> files.put(name, new String(value, StandardCharsets.UTF_8)));
            ObjectNode root = ConanFormat.MAPPER.createObjectNode();
            ObjectNode members = root.putObject("files");
            files.forEach((name, value) -> members.set(name, ConanFormat.MAPPER.readTree(value)));
            return ConanFormat.MAPPER.writeValueAsBytes(root);
        }

        /**
         * The stored entries, pulled one at a time rather than split out of the whole document.
         *
         * <p>The counterpart of the appender below, and the half that was missing: with only {@code append} the
         * generation streamed while every incremental update still read the document back through the
         * materialising default. One recipe's files is bounded by a publisher rather than by the repository, so
         * this is parity rather than a memory fix - but the two halves belong together, and a codec with one is
         * a codec somebody will assume has both.
         */
        @Override
        public Reader read(InputStream in, long ignored) throws IOException {
            JsonParser parser = ConanFormat.MAPPER.createParser(in);
            boolean found = false;
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (!found && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    boolean wanted = "files".equals(parser.currentName());
                    parser.nextToken();
                    if (wanted && parser.currentToken() == JsonToken.START_OBJECT) {
                        found = true;
                    } else {
                        parser.skipChildren();
                    }
                }
            }
            boolean inFiles = found;
            return new Reader() {

                private boolean drained = !inFiles;

                @Override
                public Optional<Map.Entry<String, byte[]>> next() {
                    if (drained || parser.nextToken() != JsonToken.PROPERTY_NAME) {
                        drained = true;
                        return Optional.empty();
                    }
                    String name = parser.currentName();
                    parser.nextToken();
                    return Optional.of(Map.entry(name, ConanFormat.MAPPER.writeValueAsBytes(parser.readValueAsTree())));
                }

                @Override
                public void close() {
                    parser.close();
                }
            };
        }

        /** The same object, written as the names arrive - unlike its sibling above, this document is in the id
         *  order a {@code Sink} delivers, so it needs nothing held. */
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
                    out.write(ConanFormat.MAPPER.writeValueAsString(id).getBytes(StandardCharsets.UTF_8));
                    out.write(':');
                    out.write(entry);
                }

                @Override
                public void close() throws IOException {
                    open();                                     // an empty document is still {"files":{}}
                    out.write("}}".getBytes(StandardCharsets.UTF_8));
                }

                private void open() throws IOException {
                    if (!opened) {
                        out.write("{\"files\":{".getBytes(StandardCharsets.UTF_8));
                        opened = true;
                    }
                }
            };
        }
    };

    private static final byte[] EMPTY_FILE = "{}".getBytes(StandardCharsets.UTF_8);

    /** The revisions newest first, then by id - the order the document lists them in and {@code latest} picks from. */
    private static List<String> ordered(SortedMap<String, byte[]> entries) {
        List<String> revisions = new ArrayList<>(entries.keySet());
        revisions.sort(Comparator.comparingLong((String revision) -> timeOf(entries.get(revision))).reversed()
                .thenComparing(Comparator.naturalOrder()));
        return revisions;
    }

    /** The epoch milliseconds of an entry's {@code time}; an entry without a readable one sorts as oldest. */
    private static long timeOf(byte[] entry) {
        JsonNode time = ConanFormat.MAPPER.readTree(entry).path("time");
        if (time == null) {
            return 0L;
        }
        try {
            return Instant.parse(time.asString()).toEpochMilli();
        } catch (DateTimeParseException | IllegalArgumentException unreadable) {
            return 0L;
        }
    }

    /** A revision's entry: {@code {"revision":"<rev>","time":"<iso>"}}. */
    static byte[] entry(String revision, long time) {
        ObjectNode node = ConanFormat.MAPPER.createObjectNode();
        node.put("revision", revision);
        node.put("time", Instant.ofEpochMilli(time).toString());
        return ConanFormat.MAPPER.writeValueAsBytes(node);
    }


    private final Blobs blobs;
    private final ArtifactStore store;

    ConanListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String revisions(String parent) {
        return parent + "/" + REVISIONS;
    }

    static String latest(String parent) {
        return parent + "/" + LATEST;
    }

    static String files(String revBase) {
        return revBase + "/" + FILES;
    }

    // ---- specs ----

    /** The revisions of a parent - a recipe reference ({@code conan/<repo>/r/<name>/<version>/<user>/<channel>}) or a
     *  package id under a recipe revision ({@code .../<rrev>/pkg/<pid>}) - with {@code latest} derived. */
    StoredListing.Spec revisionsSpec(String parent) {
        return StoredListing.Spec.materialising(revisions(parent), REVISION_ENTRIES, () -> generateRevisions(parent))
                .deriving(document -> {
                    SortedMap<String, byte[]> entries = REVISION_ENTRIES.split(document.body());
                    List<String> ordered = ordered(entries);
                    StoredListing.derive(store, latest(parent), document.header().seq(),
                            ordered.isEmpty() ? new byte[0] : entries.get(ordered.getFirst()));
                });
    }

    /** The files of one revision ({@code <parent>/<rev>}). */
    StoredListing.Spec filesSpec(String revBase) {
        return StoredListing.Spec.materialising(files(revBase), FILE_ENTRIES, () -> generateFiles(revBase));
    }

    // ---- generation: the first materialisation and the repair pass ----

    private SortedMap<String, byte[]> generateRevisions(String parent) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String revision : store.list(parent)) {
            if (revision.startsWith("@")) {
                continue;
            }
            String revBase = parent + "/" + revision;
            if (visible(revBase)) {
                entries.put(revision, entry(revision, readTime(revBase)));
            }
        }
        return entries;
    }

    private SortedMap<String, byte[]> generateFiles(String revBase) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Traversal.Result result = ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD)
                .scan(store, revBase + "/files", (name, _) -> entries.put(name, EMPTY_FILE));
        if (result.truncated()) {
            throw new IOException("The file set of " + revBase + "/files exceeds the bounded enumeration; refusing "
                    + "to store a partial revision files listing");
        }
        return entries;
    }

    /** Whether a revision is listed: it still exists, and it lists a file or holds none at all. */
    private boolean visible(String revBase) throws IOException {
        if (store.isEmpty(revBase)) {
            return false;
        }
        if (store.isEmpty(revBase + "/files")) {
            return true;
        }
        return ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD).take(1)
                .any(store, revBase + "/files");
    }

    /** A revision's stored upload time, or {@code 0} when absent or unreadable. */
    long readTime(String revBase) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(revBase + "/time");
        if (stored.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(new String(stored.get().content(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException unreadable) {
            return 0L;
        }
    }

    // ---- the write path ----

    /** Re-decide one file's entry and its revision's from the store's current state - after an upload, a proxy fill,
     *  a hold, a release or a removal. */
    void refresh(String parent, String revision, String filename) throws IOException {
        String revBase = parent + "/" + revision;
        String key = revBase + "/files/" + filename;
        if (blobs.exists(key) && !blobs.withheld(key)) {
            StoredListing.put(store, filesSpec(revBase), filename, EMPTY_FILE);
        } else {
            StoredListing.remove(store, filesSpec(revBase), filename);
        }
        refreshRevision(parent, revision);
    }

    /** Re-decide one revision's entry: listed while it exists and its stored files listing names a file, or while it
     *  holds no file at all. */
    void refreshRevision(String parent, String revision) throws IOException {
        String revBase = parent + "/" + revision;
        boolean listed = false;
        if (!store.isEmpty(revBase)) {
            if (store.isEmpty(revBase + "/files")) {
                listed = true;
            } else {
                Optional<StoredListing.Document> files = StoredListing.read(store, filesSpec(revBase));
                listed = files.isPresent() && !FILE_ENTRIES.split(files.get().body()).isEmpty();
            }
        }
        if (listed) {
            StoredListing.put(store, revisionsSpec(parent), revision, entry(revision, readTime(revBase)));
        } else {
            StoredListing.remove(store, revisionsSpec(parent), revision);
        }
    }

    /** Regenerate the listing at this key if it is a Conan one: a revisions document, a files document, or the
     *  {@code latest} that regenerates with its revisions. */
    boolean rebuild(String listing) throws IOException {
        if (!listing.startsWith("conan/")) {
            return false;
        }
        int slash = listing.lastIndexOf('/');
        String base = slash < 0 ? "" : listing.substring(0, slash);
        String name = listing.substring(slash + 1);
        switch (name) {
            case REVISIONS -> StoredListing.rebuild(store, revisionsSpec(base));
            case FILES -> StoredListing.rebuild(store, filesSpec(base));
            case LATEST -> { }
            default -> {
                return false;
            }
        }
        return true;
    }
}
