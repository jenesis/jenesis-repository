package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The deployment root a contract check binds as the signal snapshot space: an in-memory {@link ArtifactStore}, held
 * for exactly as long as one check runs. A feed that mirrors a whole catalogue - today only the CISA one - commits its
 * snapshot and its staleness stamp here through {@link SignalContext#snapshots()}, and the durable half of its read
 * behaviour becomes observable: a source created afresh over the same space must answer without a request, which is
 * precisely what a process-local cache cannot do.
 *
 * <p><strong>A fresh space per check, not per suite.</strong> The space is keyed on the signal's name rather than on
 * its endpoint, so a snapshot one check committed would be rendered by the next check's source no matter which
 * recorded endpoint it was pointed at - the page-cap leg would answer from the recorded-payload leg's snapshot and
 * never paginate, and the read-purity leg would render instead of reaching for the vendor. Binding one of these per
 * check keeps each check's story its own.
 *
 * <p><strong>Why it is not the free store testkit's.</strong> There is no in-memory {@code ArtifactStore} in the free
 * core yet - is the ticket that puts one there and cuts the forty-odd test edges onto the filesystem backend.
 * Until it lands, a contract kit that needed a store either dragged {@code store.filesystem} and a temporary directory
 * into every consumer or wrote the eleven methods itself; this is the second, deliberately minimal and deliberately
 * local, and it should be deleted in favour of the free one the day that exists. It is <em>not</em> a
 * contract-complete backend and makes no attempt to be: it is a map with the traversal screens the real backends
 * apply, enough for the snapshot pointer, the snapshot bodies and the prune sweep that ride it.
 */
public final class SnapshotSpace implements ArtifactStore {
    @Override
    public Object identity() {
        return this;   // a standalone fake IS its own subspace
    }

    /** One stored object and the token a compare-and-set is checked against. */
    private record Stored(byte[] content, long version) {
    }

    private final String prefix;
    private final ConcurrentMap<String, Stored> objects;
    private final AtomicLong versions;

    private SnapshotSpace(String prefix, ConcurrentMap<String, Stored> objects, AtomicLong versions) {
        this.prefix = prefix;
        this.objects = objects;
        this.versions = versions;
    }

    /** A fresh, empty deployment root. */
    public static SnapshotSpace root() {
        return new SnapshotSpace("", new ConcurrentHashMap<>(), new AtomicLong());
    }

    /** Every key this space holds, fully qualified - a diagnostic, so a check can say what a refresh committed. */
    public Set<String> keys() {
        return Set.copyOf(objects.keySet());
    }

    @Override
    public ArtifactStore scope(String segment) {
        return new SnapshotSpace(prefix + ArtifactStore.segment(segment) + "/", objects, versions);
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(qualified(key));
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        out.write(required(key).content());
    }

    @Override
    public InputStream open(String key) throws IOException {
        return new ByteArrayInputStream(required(key).content());
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        objects.put(qualified(key), new Stored(in.readAllBytes(), versions.incrementAndGet()));
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        byte[] content = in.readAllBytes();
        String digest = HexFormat.of().formatHex(sha256().digest(content));
        objects.put(qualified("blobs/" + digest), new Stored(content, versions.incrementAndGet()));
        return "blobs/" + digest;
    }

    @Override
    public long size(String key) throws IOException {
        Stored stored = objects.get(qualified(key));
        return stored == null ? -1 : stored.content().length;
    }

    @Override
    public void delete(String key) {
        objects.remove(qualified(key));
    }

    @Override
    public List<String> list(String prefix) {
        String under = qualified(prefix).isEmpty() ? "" : qualified(prefix) + "/";
        Set<String> children = new TreeSet<>();
        for (String key : objects.keySet()) {
            if (key.startsWith(under)) {
                String rest = key.substring(under.length());
                int slash = rest.indexOf('/');
                children.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        return List.copyOf(children);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) {
        Stored stored = objects.get(qualified(key));
        return stored == null ? Optional.empty() : Optional.of(new Versioned(stored.content(), stored.version()));
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) {
        String qualified = qualified(key);
        // The compare-and-set the snapshot pointer rides: absent-if-null, matching-token otherwise. Done under the
        // map's own atomicity so a concurrent refresh loses rather than interleaves - the same guarantee the real
        // backends give, which is what makes "an incomplete refresh commits nothing" mean anything here.
        Stored replacement = new Stored(content.clone(), versions.incrementAndGet());
        if (expected == null) {
            return objects.putIfAbsent(qualified, replacement) == null;
        }
        Stored current = objects.get(qualified);
        return current != null && Objects.equals(current.version(), expected)
                && objects.replace(qualified, current, replacement);
    }

    private Stored required(String key) throws IOException {
        Stored stored = objects.get(qualified(key));
        if (stored == null) {
            throw new FileNotFoundException("no object at " + qualified(key));
        }
        return stored;
    }

    private String qualified(String key) {
        return key == null || key.isEmpty() ? trimmed() : prefix + ArtifactStore.key(key);
    }

    private String trimmed() {
        return prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JDK", e);
        }
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
