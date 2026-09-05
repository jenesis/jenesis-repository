package build.jenesis.repository.store.gcs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.OwnerOnly;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpResponse;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.StorageObject;

/**
 * An {@link ArtifactStore} backed by a Google Cloud Storage bucket over the JSON API, through Google's API client.
 * A blob is the object at its key; a tenant or repository is a key prefix (see {@link #scope}). A read streams from
 * the media response and a ranged read is a real {@code Range} GET; an upload goes from an owner-only spool file,
 * because the API wants the length up front and the client re-reads the body when it retries a request, which a
 * plain stream cannot give - the shape the S3 store measured as a publish answering 500 under two nodes' contention.
 *
 * <p>The version token is the object <em>generation</em>: GCS's per-incarnation number, which a delete and re-create
 * never re-issues, so a compare-and-set from before the delete is refused. {@link #writeVersioned} is an insert under
 * {@code ifGenerationMatch} ({@code 0} = only if absent) whose {@code 412 Precondition Failed} becomes a
 * {@code false} return, so the caller re-reads and retries; concurrent listing edits and lock acquisitions across
 * many nodes therefore resolve through GCS itself, with no database or lock service. A conditional write is
 * idempotent, so the client's backoff re-sends it on a 408, 429 or 5xx as Google's retry guidance says, and an
 * unconditional one re-sends the same spooled bytes.
 */
public final class GcsArtifactStore implements ArtifactStore {

    /** The header every media response carries naming the object's generation - the version token, read with the
     *  bytes in one round trip so the two cannot disagree. */
    static final String GENERATION = "x-goog-generation";
    private static final String BINARY = "application/octet-stream";
    private static final String LISTING_FIELDS = "items(name,size,updated),prefixes,nextPageToken";

    private final Storage storage;
    private final String bucket;
    private final String keyPrefix;
    private final boolean streamingWrites;
    private final GcsSignedUrl signer;

    GcsArtifactStore(Storage storage, String bucket, boolean streamingWrites, GcsSignedUrl signer) {
        this(storage, bucket, "", streamingWrites, signer);
    }

    private GcsArtifactStore(Storage storage, String bucket, String keyPrefix, boolean streamingWrites, GcsSignedUrl signer) {
        this.storage = storage;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
        this.streamingWrites = streamingWrites;
        this.signer = signer;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new GcsArtifactStore(storage, bucket, keyPrefix + ArtifactStore.segment(tenant) + "/", streamingWrites, signer);
    }

    @Override
    public Object identity() {
        return "gcs:" + bucket + "/" + keyPrefix;
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        // A credential that can sign - a service-account key, or the metadata server's account through the IAM
        // signing service - mints a V4 URL; one that cannot leaves the store to stream the bytes itself.
        return signer == null ? Optional.empty() : Optional.of(signer.sign(bucket, keyPrefix + key, ttl));
    }

    // --- reads ---------------------------------------------------------------------------------------------------

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return storage.objects().get(bucket, keyPrefix + key).executeMediaAsInputStream();
        } catch (GoogleJsonResponseException e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try {
            Storage.Objects.Get get = storage.objects().get(bucket, keyPrefix + key);
            if (out instanceof RangedSink ranged) {
                get.getRequestHeaders().setRange("bytes=" + ranged.offset() + "-" + (ranged.offset() + ranged.length() - 1));
                try (InputStream in = get.executeMediaAsInputStream()) {
                    in.transferTo(ranged.sink());
                }
            } else {
                try (InputStream in = get.executeMediaAsInputStream()) {
                    in.transferTo(out);
                }
            }
        } catch (GoogleJsonResponseException e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return metadata(key, "name") != null;
        } catch (IOException e) {
            // Only a 404 means absent, and metadata() has already read that as null; a throttle or an auth failure
            // must fail the request loudly, or a published artifact silently turns into a miss for as long as the
            // backend misbehaves.
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long size(String key) throws IOException {
        StorageObject object = metadata(key, "size");
        return object == null || object.getSize() == null ? -1L : object.getSize().longValueExact();
    }

    @Override
    public Optional<Object> version(String key) throws IOException {
        // A metadata request, never a download: the token is the generation the JSON document carries.
        StorageObject object = metadata(key, "generation");
        if (object == null) {
            return Optional.empty();
        }
        if (object.getGeneration() == null) {
            throw new IOException("The endpoint returned no generation for " + key
                    + " - versioned reads need the JSON API's object document, which carries it");
        }
        return Optional.of(Long.toString(object.getGeneration()));
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        HttpResponse response;
        try {
            response = storage.objects().get(bucket, keyPrefix + key).executeMedia();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw new IOException("Could not read " + key, e);
        }
        try (InputStream in = response.getContent()) {
            byte[] content = in.readAllBytes();
            String generation = response.getHeaders().getFirstHeaderStringValue(GENERATION);
            if (generation == null) {
                // Better no token than a fabricated one: an endpoint that answers a media GET without the generation
                // is not the JSON API this store is written against, and a caller holding a made-up token would have
                // every compare-and-set refused, or worse, honoured.
                throw new IOException("The endpoint returned no " + GENERATION + " header for " + key
                        + " - versioned reads need the JSON API's media response, which carries it");
            }
            return Optional.of(new Versioned(content, generation));
        }
    }

    /** The object's metadata, or {@code null} for an absent object; only a 404 is absence. */
    private StorageObject metadata(String key, String fields) throws IOException {
        try {
            return storage.objects().get(bucket, keyPrefix + key).setFields(fields).execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw new IOException("Could not read the metadata of " + key, e);
        }
    }

    // --- listing -------------------------------------------------------------------------------------------------

    /** The storage prefix of a listing container - the scope's key prefix and the normalised container name with its
     *  trailing delimiter - so a caller's {@code a/b/} and {@code a/b} ask the service for one prefix. */
    private String base(String prefix) {
        String container = ArtifactStore.container(prefix);
        return keyPrefix + (container.isEmpty() ? "" : container + "/");
    }

    private static String name(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /** One page of the listing. {@code startOffset} is inclusive on the JSON API where S3's start-after is not, so a
     *  caller that must not see the boundary's own object drops it itself. */
    private Objects listPage(String prefix, String delimiter, String startOffset, long maxResults, String pageToken)
            throws IOException {
        Storage.Objects.List list = storage.objects().list(bucket).setPrefix(prefix).setMaxResults(maxResults)
                .setFields(LISTING_FIELDS);
        if (delimiter != null) {
            list.setDelimiter(delimiter);
        }
        if (startOffset != null && !startOffset.isEmpty()) {
            list.setStartOffset(startOffset);
        }
        if (pageToken != null) {
            list.setPageToken(pageToken);
        }
        return list.execute();
    }

    private static List<StorageObject> items(Objects page) {
        return page.getItems() == null ? List.of() : page.getItems();
    }

    private static List<String> prefixes(Objects page) {
        return page.getPrefixes() == null ? List.of() : page.getPrefixes();
    }

    /** A child as the listing saw it. {@code object} is null for a grouped prefix - a container - which reports no
     *  size or age because it has none; both halves of a leaf's metadata ride along in the response already. */
    private static Listed listed(String prefix, String name, StorageObject object) {
        String container = ArtifactStore.container(prefix);
        String key = container.isEmpty() ? name : container + "/" + name;
        return object == null ? Listed.of(key) : listed(object, key);
    }

    private static Listed listed(StorageObject object, String key) {
        return Listed.of(key,
                object.getSize() == null ? 0L : object.getSize().longValueExact(),
                object.getUpdated() == null ? Instant.EPOCH : Instant.ofEpochMilli(object.getUpdated().getValue()));
    }

    @Override
    public List<String> list(String prefix) {
        String base = base(prefix);
        TreeSet<String> names = new TreeSet<>();
        try {
            String token = null;
            do {
                Objects page = listPage(base, "/", null, 1000, token);
                for (String common : prefixes(page)) {
                    String name = common.substring(base.length());
                    if (name.endsWith("/")) {
                        name = name.substring(0, name.length() - 1);
                    }
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
                for (StorageObject object : items(page)) {
                    String name = object.getName().substring(base.length());
                    if (!name.isEmpty() && name.indexOf('/') < 0) {
                        names.add(name);
                    }
                }
                token = page.getNextPageToken();
            } while (token != null);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + prefix, e);
        }
        return new ArrayList<>(names);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        // The names-only view of pageListed: the ordering rules there are subtle enough that a second copy would
        // drift, so this form derives from that one rather than repeating it.
        pageListed(prefix, startAfter, limit, listed -> consumer.accept(name(listed.key())));
    }

    /** Whether {@code name} may not be paged out yet at stream position {@code relative}: a proper prefix of it
     *  whose next character sorts below {@code '/'} could still arrive as a grouped prefix (its container key
     *  {@code prefix + "/"} sorts at or past the position), and that shorter child name must page first. */
    private static boolean held(String name, String relative) {
        for (int index = 1; index < name.length(); index++) {
            if (name.charAt(index) < '/' && relative.compareTo(name.substring(0, index) + "/") <= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            return;
        }
        String base = base(prefix);
        // The stream arrives in raw key order, where a container shows up as a grouped prefix at `name + "/"` -
        // AFTER any sibling whose name extends this one past a character below '/' (the object `app.txt` precedes
        // the grouped prefix `app/`, yet the child `app` must page before `app.txt`). Emitting in child-NAME order
        // therefore parks every name and releases the smallest parked one only once no smaller-named child can
        // still arrive - see held(). A released name at or below startAfter is dropped: the start offset is
        // inclusive and does not skip a same-named container's grouped prefix, and a prefix-child of the boundary
        // (`app` for `app.txt`) re-arrives here yet was already paged by the call that emitted the boundary itself.
        TreeMap<String, Listed> pending = new TreeMap<>();
        int emitted = 0;
        String last = null;
        try {
            String token = null;
            do {
                Objects page = listPage(base, "/", startAfter.isEmpty() ? null : base + startAfter,
                        Math.min(ArtifactStore.oneMoreThan(limit), 1000), token);
                List<String> ordered = new ArrayList<>();
                Map<String, StorageObject> objects = new HashMap<>();
                for (StorageObject object : items(page)) {
                    String relative = object.getName().substring(base.length());
                    if (!relative.isEmpty() && relative.indexOf('/') < 0) {
                        ordered.add(relative);
                        objects.put(relative, object);
                    }
                }
                for (String common : prefixes(page)) {
                    String relative = common.substring(base.length());
                    if (relative.length() > 1 && relative.indexOf('/') == relative.length() - 1) {
                        ordered.add(relative);
                    }
                }
                Collections.sort(ordered);
                for (String relative : ordered) {
                    while (!pending.isEmpty() && !held(pending.firstKey(), relative)) {
                        Map.Entry<String, Listed> entry = pending.pollFirstEntry();
                        String name = entry.getKey();
                        if (name.compareTo(startAfter) > 0) {
                            consumer.accept(entry.getValue());
                            last = name;
                            if (++emitted == limit) {
                                return;
                            }
                        }
                    }
                    String name = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
                    if (!name.equals(last)) {
                        // A leaf and a same-named container page as one child; the leaf's metadata is kept, because
                        // that is what a GET of this key resolves to.
                        pending.merge(name, listed(prefix, name, objects.get(relative)),
                                (kept, arriving) -> kept.size().isPresent() ? kept : arriving);
                    }
                }
                token = page.getNextPageToken();
            } while (token != null);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not page " + prefix, e);
        }
        for (Map.Entry<String, Listed> entry : pending.entrySet()) {
            if (entry.getKey().compareTo(startAfter) > 0) {
                consumer.accept(entry.getValue());
                if (++emitted == limit) {
                    return;
                }
            }
        }
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        String base = base(prefix);
        // No delimiter, and therefore none of page()'s name-order repair: a recursive scan wants every object under
        // the prefix, and without grouped prefixes the listing arrives in exactly the key order this method owes.
        // The page asks for limit + 1 so the object after the last delivered one is what proves whether more
        // remains, rather than a second request asking; the cursor's own object, which the inclusive start offset
        // re-lists, is dropped.
        long steps = 0;
        long delivered = 0;
        String last = null;
        String token = null;
        do {
            Objects page = listPage(base, null, startAfter == null || startAfter.isEmpty() ? null : keyPrefix + startAfter,
                    Math.min(ArtifactStore.oneMoreThan(limit), 1000), token);
            steps++;
            for (StorageObject object : items(page)) {
                String key = object.getName().substring(keyPrefix.length());
                if (key.equals(startAfter)) {
                    continue;
                }
                if (delivered == limit) {
                    return Scan.truncated(last, delivered, steps);
                }
                // Both halves come out of the listing response, so a scanned page costs exactly its listing calls.
                consumer.accept(listed(object, key));
                delivered++;
                last = key;
            }
            token = page.getNextPageToken();
        } while (token != null);
        return Scan.exhausted(delivered, steps);
    }

    // --- writes --------------------------------------------------------------------------------------------------

    @Override
    public void write(String key, InputStream in) throws IOException {
        ArtifactStore.key(key);
        Path temporary = spool();
        try {
            fill(temporary, in);
            insert(keyPrefix + key, new FileContent(BINARY, temporary.toFile()), null);
        } catch (GoogleJsonResponseException e) {
            throw new IOException("Could not write " + key, e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        // A content-addressed key is the hash of the very bytes being written, so the body is spooled while it is
        // digested and uploaded from the file under blobs/<hash> - never held whole in memory.
        Path temporary = spool();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String key = "blobs/" + HexFormat.of().formatHex(digest.digest());
            if (!exists(key)) {
                insert(keyPrefix + key, new FileContent(BINARY, temporary.toFile()), null);
            }
            return key.substring("blobs/".length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (GoogleJsonResponseException e) {
            throw new IOException("Could not write blob", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return put(key, new ByteArrayContent(BINARY, content), expected);
    }

    /**
     * The streaming compare-and-set: the same {@code ifGenerationMatch} precondition over a body of known length,
     * spooled first so a retried request reads the same bytes.
     */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        if (!streamingWrites) {
            return put(key, new ByteArrayContent(BINARY, content.readAllBytes()), expected);
        }
        Path temporary = spool();
        try {
            fill(temporary, content);
            return put(key, new FileContent(BINARY, temporary.toFile()), expected);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            storage.objects().delete(bucket, keyPrefix + key).execute();
        } catch (GoogleJsonResponseException e) {
            // Deleting what is not there is the same afterwards as deleting what was.
            if (e.getStatusCode() != 404) {
                throw new IOException("Could not delete " + key, e);
            }
        }
    }

    @Override
    public List<BatchOutcome> writeBatch(List<BatchWrite> writes) throws IOException {
        // Best-effort, per-key compare-and-set, not a transaction (GCS has no multi-object atomicity): the
        // conditional inserts go bounded-parallel so a k-write commit is about one round trip instead of k, each
        // 412 classified exactly as writeVersioned. The shared helper keeps input order and never overlaps two
        // writes to one key.
        return ArtifactStore.writeBatchParallel(this, writes);
    }

    /** Both conditional writes; the generation precondition is identical, only the body differs. */
    private boolean put(String key, AbstractInputStreamContent body, Object expected) throws IOException {
        ArtifactStore.key(key);
        long generation;
        try {
            generation = expected == null ? 0L : Long.parseLong((String) expected);
        } catch (NumberFormatException | ClassCastException e) {
            throw new IllegalArgumentException("Not a version token of this store: " + expected, e);
        }
        try {
            insert(keyPrefix + key, body, generation);
            return true;
        } catch (GoogleJsonResponseException e) {
            // The precondition: another incarnation is stored, or the one expected is gone. Only that reads as a
            // lost compare-and-set; a missing bucket, a refusal or a throttle the retries did not outlast must
            // surface, or the caller's retry loop turns an outage into silent exhaustion.
            if (e.getStatusCode() == 412) {
                return false;
            }
            throw new IOException("Could not write " + key
                    + (e.getStatusCode() == 404 ? ": bucket " + bucket + " does not exist" : ""), e);
        }
    }

    /** One insert: a direct {@code uploadType=media} request over a body whose length is known, under the
     *  generation precondition when the write is conditional, and never gzip-encoded - an encoded upload is stored
     *  as an encoded object, which is not what was written. */
    private StorageObject insert(String name, AbstractInputStreamContent body, Long ifGenerationMatch) throws IOException {
        Storage.Objects.Insert insert = storage.objects().insert(bucket, null, body).setName(name);
        if (ifGenerationMatch != null) {
            insert.setIfGenerationMatch(ifGenerationMatch);
        }
        insert.setDisableGZipContent(true);
        insert.getMediaHttpUploader().setDirectUploadEnabled(true).setDisableGZipContent(true);
        return insert.execute();
    }

    /** The owner-only upload spool ({@link OwnerOnly}): the API wants a content length up front and a retried
     *  request wants the body again, so a body is buffered here before upload, and never where the plaintext
     *  artifact bytes would be world-readable for the life of the upload. */
    private static Path spool() throws IOException {
        return OwnerOnly.createTempFile("gcs-artifact-", null);
    }

    /** Fill the spool through a TRUNCATE_EXISTING open of the already-0600 file, never a copy that recreates it
     *  under the process umask. */
    private static void fill(Path temporary, InputStream in) throws IOException {
        try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
    }
}
