package build.jenesis.repository.store.gcs;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
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
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.DelimitedPages;
import build.jenesis.repository.store.PrimitiveArtifactStore;
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
 * An {@link ArtifactStore} over a Google Cloud Storage bucket through the JSON API and Google's API client. A blob is
 * the object at its key; a tenant or repository is a key prefix ({@link #scope}). A read streams the media response and
 * a ranged read is a real {@code Range} GET. An upload goes from an owner-only spool file, because the API wants the
 * length up front and the client re-reads the body when it retries.
 *
 * <p>The version token is the object <em>generation</em>, which a delete and re-create never re-issues, so a
 * compare-and-set from before a delete is refused. {@link #writeVersioned} is an insert under {@code ifGenerationMatch}
 * ({@code 0} = only if absent) whose {@code 412} becomes {@code false}, so concurrent writers across nodes resolve
 * through GCS itself. A conditional write is idempotent, so the client's backoff re-sends it on a 408, 429 or 5xx, and
 * an unconditional one re-sends the same spooled bytes.
 */
public final class GcsArtifactStore implements PrimitiveArtifactStore {

    /** The header naming the object's generation on every media response - the version token, read with the bytes in
     *  one round trip. */
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

    // ---- reads

    @Override
    public InputStream open(String key) throws IOException {
        return open(key, 0L);
    }

    /** The object from {@code offset} on, asked of the bucket as a range rather than read and skipped. */
    @Override
    public InputStream open(String key, long offset) throws IOException {
        try {
            Storage.Objects.Get get = storage.objects().get(bucket, keyPrefix + key);
            if (offset > 0) {
                get.getRequestHeaders().setRange("bytes=" + offset + "-");
            }
            return get.executeMediaAsInputStream();
        } catch (GoogleJsonResponseException e) {
            // The SPI's typed absence: a serve opens the blob before it commits and turns this into a clean 404.
            if (e.getStatusCode() == 404) {
                throw (IOException) new NoSuchFileException(key).initCause(e);
            }
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
            // Only a 404 is absence, already read as null by metadata(); a throttle or an auth failure fails loudly
            // rather than turning a published artifact into a miss.
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long size(String key) throws IOException {
        StorageObject object = metadata(key, "size");
        return object == null || object.getSize() == null ? -1L : object.getSize().longValueExact();
    }

    @Override
    public Optional<Listed> listed(String key) throws IOException {
        // The metadata request size makes, with the update time - never a download.
        StorageObject object = metadata(key, "size,updated");
        if (object == null) {
            return Optional.empty();
        }
        return Optional.of(new Listed(key,
                object.getSize() == null ? OptionalLong.empty() : OptionalLong.of(object.getSize().longValueExact()),
                object.getUpdated() == null
                        ? Optional.empty() : Optional.of(Instant.ofEpochMilli(object.getUpdated().getValue()))));
    }

    @Override
    public Optional<Object> version(String key) throws IOException {
        // A metadata request: the token is the generation the JSON document carries.
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
                // No token rather than a fabricated one: an endpoint answering a media GET without the generation is
                // not the JSON API, and a made-up token would have every compare-and-set refused, or worse, honoured.
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

    // ---- listing

    /** The storage prefix of a listing container - the scope's prefix plus the normalised container and its delimiter -
     *  so {@code a/b/} and {@code a/b} ask for one prefix. */
    private String base(String prefix) {
        String container = ArtifactStore.container(prefix);
        return keyPrefix + (container.isEmpty() ? "" : container + "/");
    }

    /** One page of the listing. {@code startOffset} is inclusive on the JSON API, unlike S3's start-after, so a caller
     *  that must not see the boundary's own object drops it. */
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

    /** A child as the listing saw it. {@code object} is null for a grouped prefix - a container, which has no size or
     *  age; a leaf's metadata rides in the response. */
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
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            return;
        }
        String base = base(prefix);
        DelimitedPages pages = new DelimitedPages(startAfter, limit, consumer);
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
                if (pages.page(ordered, (relative, name) -> listed(prefix, name, objects.get(relative)))) {
                    return;
                }
                token = page.getNextPageToken();
            } while (token != null);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not page " + prefix, e);
        }
        pages.finish();
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        String base = base(prefix);
        // No delimiter, so none of page()'s name-order repair: without grouped prefixes the listing arrives in exactly
        // the key order owed. The page asks for limit + 1 so the extra object proves whether more remain; the cursor's
        // own object, re-listed by the inclusive start offset, is dropped.
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
                // Both halves come from the listing response, so a scanned page costs only its listing calls.
                consumer.accept(listed(object, key));
                delivered++;
                last = key;
            }
            token = page.getNextPageToken();
        } while (token != null);
        return Scan.exhausted(delivered, steps);
    }

    // ---- writes

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
        // A content-addressed key is the hash of the bytes, so the body is spooled while digested and uploaded from the
        // file.
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

    /** The streaming compare-and-set: the same {@code ifGenerationMatch} precondition over a spooled body, so a retry
     *  reads the same bytes. */
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
            // Deleting what is absent leaves the same state as deleting what was there.
            if (e.getStatusCode() != 404) {
                throw new IOException("Could not delete " + key, e);
            }
        }
    }

    /** Both conditional writes; only the body differs. */
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
            // Only the precondition reads as a lost compare-and-set; a missing bucket, a refusal or an outlasting
            // throttle surfaces, so a caller's retry loop never turns an outage into silent exhaustion.
            if (e.getStatusCode() == 412) {
                return false;
            }
            throw new IOException("Could not write " + key
                    + (e.getStatusCode() == 404 ? ": bucket " + bucket + " does not exist" : ""), e);
        }
    }

    /** One insert: a direct {@code uploadType=media} request of known length, under the generation precondition when
     *  conditional, never gzip-encoded - an encoded upload is stored encoded. */
    private StorageObject insert(String name, AbstractInputStreamContent body, Long ifGenerationMatch) throws IOException {
        Storage.Objects.Insert insert = storage.objects().insert(bucket, null, body).setName(name);
        if (ifGenerationMatch != null) {
            insert.setIfGenerationMatch(ifGenerationMatch);
        }
        insert.setDisableGZipContent(true);
        insert.getMediaHttpUploader().setDirectUploadEnabled(true).setDisableGZipContent(true);
        return insert.execute();
    }

    /** The owner-only upload spool ({@link OwnerOnly}): the API wants a length up front and a retry wants the body
     *  again, so a body is buffered here, never world-readable. */
    private static Path spool() throws IOException {
        return OwnerOnly.createTempFile("gcs-artifact-", null);
    }

    /** Fill the spool by a TRUNCATE_EXISTING open of the already-0600 file, never a copy that recreates it under the
     *  umask. */
    private static void fill(Path temporary, InputStream in) throws IOException {
        try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
    }
}
