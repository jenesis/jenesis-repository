package build.jenesis.repository.format.huggingface;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Hugging Face revision's file set as a stored listing - one line per stored file naming its content hash, its
 * size and whether it is served - from which the revision's commit id, its file tree and its repository-info
 * document are derived on every write. An upload therefore costs one rewrite of the revision's list and its three
 * derived documents, never a walk of the revision's other files.
 *
 * <p>The commit id is the SHA-1 over every stored file and its hash, held or not - a hold must not silently change a
 * commit id - while the tree and the info's {@code siblings} list only the served files, as the on-read generation
 * screened them.
 */
final class HuggingFaceListings {

    /** {@code <path>\0<sha256>\0<size>\0<served>} per file, keyed by the path. */
    static final StoredListing.Codec FILES = StoredListing.Codec.delimited("\n", line -> line.substring(0,
            line.indexOf('\0')));

    private final Blobs blobs;
    private final ArtifactStore store;

    HuggingFaceListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String files(String base, String revision) {
        return base + "/revs/" + revision + "/files";
    }

    static String commit(String base, String revision) {
        return base + "/revs/" + revision + "/commit";
    }

    static String tree(String base, String revision) {
        return base + "/revs/" + revision + "/tree";
    }

    static String info(String base, String revision) {
        return base + "/revs/" + revision + "/info";
    }

    record Entry(String path, String hash, long size, boolean served) {

        static Entry parse(byte[] line) {
            String[] fields = new String(line, StandardCharsets.UTF_8).split("\0", -1);
            return new Entry(fields[0], fields[1], fields.length > 2 ? Long.parseLong(fields[2]) : -1L,
                    fields.length > 3 && fields[3].equals("1"));
        }

        byte[] line() {
            return (path + '\0' + hash + '\0' + size + '\0' + (served ? "1" : "0")).getBytes(StandardCharsets.UTF_8);
        }
    }

    StoredListing.Spec spec(String base, String revision, String type, String repoId) {
        return StoredListing.Spec.materialising(files(base, revision), FILES, () -> generate(base, revision))
                .deriving(document -> derive(base, revision, type, repoId, document));
    }

    private SortedMap<String, byte[]> generate(String base, String revision) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String encoded : blobs.list(base + "/revs/" + revision + "/files")) {
            Entry entry = entry(base, revision, HuggingFaceFormat.dec(encoded));
            if (entry != null) {
                entries.put(entry.path(), entry.line());
            }
        }
        return entries;
    }

    private Entry entry(String base, String revision, String path) throws IOException {
        String key = base + "/revs/" + revision + "/files/" + HuggingFaceFormat.enc(path);
        Optional<String> hash = blobs.hash(key);
        if (hash.isEmpty()) {
            return null;
        }
        boolean served = !blobs.withheld(key);
        return new Entry(path, hash.get(), served ? blobs.size(key) : -1L, served);
    }

    private void derive(String base, String revision, String type, String repoId, StoredListing.Derived document)
            throws IOException {
        long seq = document.header().seq();
        List<Entry> entries = new ArrayList<>();
        for (byte[] line : FILES.split(document.body()).values()) {
            entries.add(Entry.parse(line));
        }
        StoredListing.derive(store, commit(base, revision), seq, commitOf(entries).getBytes(StandardCharsets.UTF_8));
        ArrayNode tree = HuggingFaceFormat.MAPPER.createArrayNode();
        ArrayNode siblings = HuggingFaceFormat.MAPPER.createArrayNode();
        for (Entry entry : entries) {
            if (!entry.served()) {
                continue;
            }
            ObjectNode file = tree.addObject();
            file.put("type", "file");
            file.put("oid", entry.hash());
            file.put("size", entry.size());
            file.put("path", entry.path());
            siblings.addObject().put("rfilename", entry.path());
        }
        StoredListing.derive(store, tree(base, revision), seq, HuggingFaceFormat.MAPPER.writeValueAsBytes(tree));
        String sha = HuggingFaceFormat.isCommit(revision) ? revision : commitOf(entries);
        ObjectNode info = HuggingFaceFormat.MAPPER.createObjectNode();
        info.put("id", repoId);
        if (type.equals("models")) {
            info.put("modelId", repoId);
        }
        info.put("sha", sha);
        info.put("lastModified", Instant.ofEpochMilli(seq).toString());
        info.set("siblings", siblings);
        StoredListing.derive(store, info(base, revision), seq, HuggingFaceFormat.MAPPER.writeValueAsBytes(info));
    }

    /** The synthesised commit id: a SHA-1 over every stored file and its hash, in path order. */
    static String commitOf(List<Entry> entries) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (Entry entry : entries) {
                sha1.update((entry.path() + '\0' + entry.hash() + '\n').getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    /** Regenerate the listing at this key if it is a revision's file list (its commit, tree and info regenerate
     *  with it). */
    boolean rebuild(String listing) throws IOException {
        int revs = listing.indexOf("/revs/");
        if (!listing.startsWith("hf/") || revs < 0) {
            return false;
        }
        String base = listing.substring(0, revs);
        String[] rest = listing.substring(revs + "/revs/".length()).split("/");
        String[] segments = base.split("/");
        if (rest.length != 2 || segments.length < 4) {
            return false;
        }
        if (rest[1].equals("files")) {
            String type = segments[2];
            String repoId = String.join("/", Arrays.copyOfRange(segments, 3, segments.length));
            StoredListing.rebuild(store, spec(base, rest[0], type, repoId));
            return true;
        }
        return rest[1].equals("commit") || rest[1].equals("tree") || rest[1].equals("info");
    }

    /** Re-decide one file's entry from the store's current state - after an upload, a hold or a release. */
    void refresh(String base, String revision, String type, String repoId, String path) throws IOException {
        Entry entry = entry(base, revision, path);
        StoredListing.Spec spec = spec(base, revision, type, repoId);
        if (entry == null) {
            StoredListing.remove(store, spec, path);
        } else {
            StoredListing.put(store, spec, path, entry.line());
        }
    }

    /** A derived document of the revision, materialising the revision's list (and so the document) when absent. */
    Optional<StoredListing.Served> derived(String base, String revision, String type, String repoId, String name)
            throws IOException {
        Optional<StoredListing.Served> served = StoredListing.openDerived(store, name);
        if (served.isEmpty()) {
            StoredListing.read(store, spec(base, revision, type, repoId));
            served = StoredListing.openDerived(store, name);
        }
        return served;
    }
}
