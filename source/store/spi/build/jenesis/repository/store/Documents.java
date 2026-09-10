package build.jenesis.repository.store;

import module java.base;

/**
 * A small Properties document over an {@link ArtifactStore}: the product's own state, keyed and read as documents
 * rather than as artifacts.
 *
 * <h2>Why this type exists</h2>
 * Because the admin console reached for the <em>cache</em> SPI's general-purpose file API -
 * {@code readFile}/{@code writeFileVersioned}/{@code listDir} - to store its tenants, its members, its membership
 * index and its SCIM token. It was never a cache: {@code rootStorage} was already
 * {@code new DelegatingCacheStorage(repositoryStore)}, so those documents were in the repository's store all
 * along and the cache interface was borrowed for its shape. Borrowing an interface for its shape is how a type
 * nobody owns acquires two meanings - it is why a fixture storing console state had to be told which of two
 * constructors to call, and why a SCIM provisioning module had a compile-time dependency on the build cache.
 *
 * <p>So this is the shape, named for what it is, over the store the documents were always in. Nothing moves: the
 * keys are the same keys.
 *
 * <p><b>{@code Authorization} keeps its own pair of helpers of this shape, and the reason is not neglect.</b> It
 * reads through {@link StoreCache}, which is a final class rather than an {@link ArtifactStore}, so sharing this
 * type would mean a seam over both - a change to the caching path rather than to the document shape, and a
 * different change from this one. Two implementations of one idea is a thing to fix, and it is written down here
 * rather than left to be rediscovered.
 *
 * <h2>What a document is</h2>
 * A {@link Properties} object at one key. Reads answer an empty document rather than null for an absent or
 * unaddressable path, because every caller here asks a question of the content ("what role", "which tenants") and
 * an absent document answers it with "none" - a null would make each of them write the same guard. A
 * <em>version</em> is read without the body, so a compare-and-set revalidation costs a metadata request rather
 * than a download.
 *
 * <h2>Containers, not keys</h2>
 * {@link #containers} is the one method whose meaning is not obvious from its name, and it is the one that
 * matters: it answers the immediate child <em>containers</em> of a prefix, never the documents beside them. A
 * store's {@code page} merges a blob and a same-named container into one name by design, so a child is a
 * container if and only if something is stored beneath it - one bounded probe per candidate, bounded by the page
 * size rather than by the container's size. A listing that skipped that probe would report a document sitting
 * beside the containers as one of them, which for a member directory means a phantom member.
 *
 * <h2>Bounded, like everything else that reads the store</h2>
 * {@link #containers} is a page with a cursor and {@link #deleteAll} deletes as it walks, so neither cost grows
 * with what the store holds. There is deliberately no "read every document under this prefix": a caller that
 * wants many pages asks for many pages.
 */
public final class Documents {

    /** The most a path may carry, and the most one segment may: a filesystem store maps a segment to a file name. */
    private static final int MAX_PATH = 1024, MAX_SEGMENT = 255;

    private final ArtifactStore store;

    private Documents(ArtifactStore store) {
        this.store = Objects.requireNonNull(store, "A document store needs a store to keep them in");
    }

    /** Documents in {@code store}, at the keys the caller names. */
    public static Documents over(ArtifactStore store) {
        return new Documents(store);
    }

    /** The same documents one scope down - a tenant's, typically - so a caller holds a view rather than a prefix
     *  it has to remember to compose. */
    public Documents scope(String name) {
        return new Documents(store.scope(name));
    }

    /** The store these documents are kept in, for a caller that also has artifact-shaped work to do at the same
     *  scope (measuring the volume, say) and should not open a second view of it to do so. */
    public ArtifactStore store() {
        return store;
    }

    /** The document at {@code path}, or an empty one when there is none - see the class note on why absent is not
     *  null. An unaddressable path reads as absent rather than throwing: it cannot name a document, so it has none. */
    public Properties read(String path) {
        Properties properties = new Properties();
        if (!addressable(path)) {
            return properties;
        }
        try {
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(path);
            if (stored.isPresent()) {
                properties.load(new ByteArrayInputStream(stored.get().content()));
            }
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the document at '" + path + "'", e);
        }
    }

    /** The version token of the document at {@code path}, or null when there is none - read <em>without</em> the
     *  body, so revalidating a compare-and-set costs a metadata request rather than a download. */
    public Object version(String path) {
        if (!addressable(path)) {
            return null;
        }
        try {
            return store.version(path).orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the version of the document at '" + path + "'", e);
        }
    }

    /** Write the document at {@code path}, unconditionally - the last writer wins. Where two writers can meet on
     *  one document, {@link #writeVersioned} is the one to reach for. */
    public void write(String path, Properties properties) throws IOException {
        store.write(path, new ByteArrayInputStream(bytes(properties)));
    }

    /** Write the document at {@code path} only if its version is still {@code expected} ({@code null} meaning it
     *  must not exist), answering whether it was written. The loser of a race retries through {@code Retries}; the
     *  point of the token is that it never has to re-read the body to find out it lost. */
    public boolean writeVersioned(String path, Properties properties, Object expected) throws IOException {
        return store.writeVersioned(path, bytes(properties), expected);
    }

    /** A document as the bytes it is stored as. No comment line, so two writes of equal content are equal bytes -
     *  which is what lets a store dedupe them and a reader compare them. */
    private static byte[] bytes(Properties properties) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, null);
        return bytes.toByteArray();
    }

    /** Delete every document under {@code prefix}, a bounded page at a time.
     *
     * <p>It deletes as it walks, so the next page starts where this one ended rather than re-listing from the top,
     * and a crash halfway leaves a partly deleted subtree that a re-run finishes - which is what a recursive delete
     * can promise on a store with no atomic subtree operation. */
    public void deleteAll(String prefix) throws IOException {
        String cursor = "";
        while (true) {
            List<String> keys = new ArrayList<>();
            ArtifactStore.Scan scan = store.scan(prefix, cursor, PAGE, listed -> keys.add(listed.key()));
            for (String key : keys) {
                store.delete(key);
            }
            if (!scan.truncated()) {
                return;
            }
            cursor = scan.cursor().orElseThrow();
        }
    }

    /** The page size a subtree delete and an unbounded caller's own paging walk in. */
    public static final int PAGE = 1000;

    /** One page of a listing: the names went to the consumer, and {@code next} is the key to resume strictly after,
     *  empty when the container is drained. */
    public record Page(Optional<String> next) {

        public Page {
            Objects.requireNonNull(next, "next");
        }

        /** Whether this page drained the container, so there is nothing to resume from. */
        public boolean exhausted() {
            return next.isEmpty();
        }
    }

    /**
     * One bounded page of the immediate child <em>containers</em> of {@code prefix}, resuming strictly after
     * {@code cursor} ({@code null} or empty starts at the beginning). See the class note: a document beside the
     * containers is not one of them, and telling them apart costs one bounded probe per candidate.
     */
    public Page containers(String prefix, String cursor, int limit, Consumer<String> names) {
        if (limit < 1) {
            throw new IllegalArgumentException("A page size must be positive, not " + limit);
        }
        String pageCursor = child(prefix, cursor);
        List<String> page = new ArrayList<>();
        try {
            while (page.size() <= limit) {
                List<String> batch = new ArrayList<>();
                store.page(prefix, pageCursor, ArtifactStore.oneMoreThan(limit), batch::add);
                for (String name : batch) {
                    if (page.size() > limit) {
                        break;
                    }
                    if (container(prefix, name)) {
                        page.add(name);
                    }
                }
                if (batch.size() < ArtifactStore.oneMoreThan(limit)) {
                    break;              // the underlying page was short, so the container is drained
                }
                pageCursor = batch.getLast();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list the children of '" + prefix + "'", e);
        }
        // Deliver at most `limit`, and let the extra name the page carries decide the answer: reaching it means
        // there IS more, so the cursor is the last name delivered rather than a guess that there might be.
        int delivered = 0;
        String previous = null;
        for (String name : page) {
            if (delivered == limit) {
                return new Page(Optional.of(key(prefix, previous)));
            }
            names.accept(name);
            previous = name;
            delivered++;
        }
        return new Page(Optional.empty());
    }

    /** Whether a child name is a container: one bounded scan that stops at the first key beneath it. */
    private boolean container(String prefix, String name) throws IOException {
        return store.scan(key(prefix, name), "", 1, _ -> { }).delivered() > 0;
    }

    /** The immediate child NAME a listing resumes after, taken out of a full-key cursor - refused rather than
     *  silently reinterpreted when it names something that is not an immediate child, because an enumeration
     *  resumed from a foreign cursor skips whatever sorts below it and reports the container drained. */
    private static String child(String prefix, String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return "";
        }
        String name = cursor;
        if (!prefix.isEmpty()) {
            if (!cursor.startsWith(prefix + "/")) {
                throw new IllegalArgumentException("Cursor '" + cursor + "' is not a child key of '" + prefix + "'");
            }
            name = cursor.substring(prefix.length() + 1);
        }
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "Cursor '" + cursor + "' is not an immediate child key of '" + prefix + "'");
        }
        return name;
    }

    private static String key(String prefix, String name) {
        return prefix == null || prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /** Whether a path can name a document at all: no traversal, no empty segment, no backslash, bounded. The store
     *  screens what it writes; this screens what is asked for, so an unaddressable read answers absent rather than
     *  reaching the backend with a key nobody meant. */
    private static boolean addressable(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH) {
            return false;
        }
        if (path.indexOf('\\') >= 0 || path.startsWith("/") || path.endsWith("/")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT) {
                return false;
            }
        }
        return true;
    }
}
