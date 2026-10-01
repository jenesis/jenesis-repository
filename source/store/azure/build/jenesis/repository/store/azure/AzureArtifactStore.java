package build.jenesis.repository.store.azure;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlockBlobSimpleUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlobOutputStream;
import com.azure.storage.blob.specialized.BlockBlobClient;
import build.jenesis.repository.store.OwnerOnly;

/**
 * An {@link ArtifactStore} over an Azure Blob Storage container on the {@code azure-storage-blob} SDK. A blob is the
 * object at its name; a tenant or repository is a name prefix ({@link #scope}). The version token is the blob ETag, so
 * {@link #writeVersioned} is a cross-node compare-and-set: {@code expected == null} uploads with
 * {@code If-None-Match: *} and a token with {@code If-Match: <etag>}; a {@code 412}, or the
 * {@code 409 BlobAlreadyExists} {@code If-None-Match: *} raises, becomes {@code false}, so the caller re-reads and
 * retries. Concurrent writers across nodes resolve through Azure itself.
 */
public final class AzureArtifactStore implements ArtifactStore {

    private final BlobContainerClient container;
    private final String keyPrefix;

    /** Whether a conditional write may stream its body; see {@code AzureArtifactStoreProvider}. */
    private final boolean streamingWrites;

    public AzureArtifactStore(BlobContainerClient container) {
        this(container, "", true);
    }

    /** The provider's constructor, the only one that decides {@code streamingWrites}. */
    public AzureArtifactStore(BlobContainerClient container, boolean streamingWrites) {
        this(container, "", streamingWrites);
    }

    private AzureArtifactStore(BlobContainerClient container, String keyPrefix, boolean streamingWrites) {
        this.container = container;
        this.keyPrefix = keyPrefix;
        this.streamingWrites = streamingWrites;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new AzureArtifactStore(container, keyPrefix + ArtifactStore.segment(tenant) + "/",
                streamingWrites);
    }

    @Override
    public Object identity() {
        return "azure:" + container.getBlobContainerUrl() + "/" + keyPrefix;
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        BlobClient blob = container.getBlobClient(keyPrefix + key);
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plus(ttl), new BlobSasPermission().setReadPermission(true));
        try {
            String sas = blob.generateSas(values);
            return Optional.of(URI.create(blob.getBlobUrl() + "?" + sas));
        } catch (RuntimeException noSharedKey) {
            // generateSas needs a shared-key credential; a token or AAD client cannot sign a service SAS, so the read
            // streams instead. The SDK signals the missing key with differing runtime exceptions across versions, so
            // any RuntimeException from signing degrades. A user-delegation-key SAS is not implemented.
            return Optional.empty();
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(container.getBlobClient(keyPrefix + key).exists());
        } catch (BlobStorageException e) {
            // Only a 404 is absence; a throttle or auth failure fails loudly rather than turning an artifact into a
            // miss.
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public long size(String key) throws IOException {
        try {
            return container.getBlobClient(keyPrefix + key).getProperties().getBlobSize();
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return -1L;
            }
            throw new IOException("Could not size " + key, e);
        }
    }

    @Override
    public Optional<Listed> listed(String key) throws IOException {
        try {
            var properties = container.getBlobClient(keyPrefix + key).getProperties();
            return Optional.of(new Listed(key, OptionalLong.of(properties.getBlobSize()),
                    Optional.ofNullable(properties.getLastModified()).map(OffsetDateTime::toInstant)));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw new IOException("Could not describe " + key, e);
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try {
            if (out instanceof ArtifactStore.RangedSink ranged) {
                try (InputStream in = container.getBlobClient(keyPrefix + key).openInputStream(
                        new BlobInputStreamOptions().setRange(new BlobRange(ranged.offset(), ranged.length())))) {
                    in.transferTo(ranged.sink());
                }
            } else {
                container.getBlobClient(keyPrefix + key).downloadStream(out);
            }
        } catch (BlobStorageException e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return open(key, 0L);
    }

    /** The blob from {@code offset} on, asked of the container as a range rather than read and skipped. */
    @Override
    public InputStream open(String key, long offset) throws IOException {
        try {
            BlobClient blob = container.getBlobClient(keyPrefix + key);
            return offset > 0 ? blob.openInputStream(new BlobRange(offset), null) : blob.openInputStream();
        } catch (BlobStorageException e) {
            // The SPI's typed absence: a serve opens the blob before it commits and turns this into a clean 404.
            if (e.getStatusCode() == 404) {
                throw (IOException) new NoSuchFileException(key).initCause(e);
            }
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        ArtifactStore.key(key);
        BlockBlobClient blob = container.getBlobClient(keyPrefix + key).getBlockBlobClient();
        // Close only after a complete transfer: BlobOutputStream commits its block list on close even after a failed
        // source, which would land a truncated blob. An unclosed stream commits nothing, and the service expires the
        // staged blocks.
        BlobOutputStream out = blob.getBlobOutputStream(true);
        try {
            in.transferTo(out);
            out.close();
        } catch (BlobStorageException e) {
            throw new IOException("Could not write " + key, e);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        // A content-addressed key is the hash of the bytes, so the body is spooled to a file while digested and
        // uploaded from it under blobs/<hash>, never held whole.
        Path temporary = spool();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String key = "blobs/" + HexFormat.of().formatHex(digest.digest());
            if (!exists(key)) {
                BlockBlobClient blob = container.getBlobClient(keyPrefix + key).getBlockBlobClient();
                // Close only after a complete transfer (see write): a truncated blob at blobs/<hash> would be
                // permanent, since the dedupe check would skip every later upload of the true content.
                try (InputStream stored = Files.newInputStream(temporary)) {
                    BlobOutputStream out = blob.getBlobOutputStream(true);
                    stored.transferTo(out);
                    out.close();
                }
            }
            return key.substring("blobs/".length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (BlobStorageException e) {
            throw new IOException("Could not write blob", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The owner-only upload spool ({@link OwnerOnly}), so a buffered artifact is never world-readable in a shared
     *  {@code /tmp}. */
    private static Path spool() throws IOException {
        return OwnerOnly.createTempFile("azure-artifact-", null);
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            container.getBlobClient(keyPrefix + key).deleteIfExists();
        } catch (BlobStorageException e) {
            throw new IOException("Could not delete " + key, e);
        }
    }

    /** The storage prefix of a listing container - the scope's prefix plus the normalised container and its delimiter -
     *  so {@code a/b/} and {@code a/b} ask for one prefix. */
    private String base(String prefix) {
        String container = ArtifactStore.container(prefix);
        return keyPrefix + (container.isEmpty() ? "" : container + "/");
    }

    @Override
    public List<String> list(String prefix) {
        String base = base(prefix);
        TreeSet<String> names = new TreeSet<>();
        for (BlobItem item : container.listBlobsByHierarchy(base)) {
            String name = item.getName().substring(base.length());
            if (Boolean.TRUE.equals(item.isPrefix()) && name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            return;
        }
        String base = base(prefix);
        // List Blobs takes a server-side start-at key (ListBlobsOptions.startFrom, distinct from the continuation
        // marker), so a resume seeks to the boundary in one bounded page rather than re-listing from the base. As in
        // the s3 backend, the SDK returns a page's blobs and prefixes as two lists, merged back into key order, where a
        // container's prefix sits at `name + "/"` after a sibling extending the name past a character below '/' (blob
        // `app.txt` precedes prefix `app/`, yet child `app` pages first): so names park and the smallest releases once
        // no smaller one can arrive (held()). A released name at or below startAfter is dropped: startFrom is
        // inclusive, and a prefix-child of the boundary was already paged by the previous call.
        ListBlobsOptions options = new ListBlobsOptions().setPrefix(base).setMaxResultsPerPage(Math.min(ArtifactStore.oneMoreThan(limit), 5000));
        if (!startAfter.isEmpty()) {
            options.setStartFrom(base + startAfter);
        }
        // Keyed by child name; a prefix entry is a container and carries no metadata.
        TreeMap<String, Listed> pending = new TreeMap<>();
        int emitted = 0;
        String last = null;
        for (PagedResponse<BlobItem> page : container.listBlobsByHierarchy("/", options, null).iterableByPage()) {
            List<String> ordered = new ArrayList<>();
            Map<String, BlobItem> blobs = new HashMap<>();
            for (BlobItem item : page.getValue()) {
                String relative = item.getName().substring(base.length());
                if (!Boolean.TRUE.equals(item.isPrefix())) {
                    blobs.put(relative, item);
                }
                if (Boolean.TRUE.equals(item.isPrefix()) && !relative.endsWith("/")) {
                    relative = relative + "/";
                }
                if (!relative.isEmpty() && !relative.equals("/")) {
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
                    // A blob and a same-named container page as one child, keeping the blob's metadata - what a GET
                    // resolves to.
                    pending.merge(name, listed(prefix, name, blobs.get(relative)),
                            (kept, arriving) -> kept.size().isPresent() ? kept : arriving);
                }
            }
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

    /** A child as List Blobs saw it; {@code item} is null for a prefix entry - a container, with no size or age. A
     *  blob's metadata rides in the listing response. */
    private static Listed listed(String prefix, String name, BlobItem item) {
        String container = ArtifactStore.container(prefix);
        String key = container.isEmpty() ? name : container + "/" + name;
        BlobItemProperties properties = item == null ? null : item.getProperties();
        if (properties == null) {
            return Listed.of(key);
        }
        return Listed.of(key,
                properties.getContentLength() == null ? 0L : properties.getContentLength(),
                properties.getLastModified() == null ? Instant.EPOCH : properties.getLastModified().toInstant());
    }

    /** Whether {@code name} must wait at stream position {@code relative}: a proper prefix of it whose next character
     *  sorts below {@code '/'} could still arrive as a hierarchy prefix, and that shorter name must page first. */
    private static boolean held(String name, String relative) {
        for (int index = 1; index < name.length(); index++) {
            if (name.charAt(index) < '/' && relative.compareTo(name.substring(0, index) + "/") <= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        String base = base(prefix);
        // listBlobs, not listBlobsByHierarchy: a recursive scan has no grouped prefixes, so the flat listing arrives in
        // the key order owed, with none of page()'s repair.
        ListBlobsOptions options = new ListBlobsOptions()
                .setPrefix(base)
                .setMaxResultsPerPage(Math.min(ArtifactStore.oneMoreThan(limit), 5000))
                .setDetails(new BlobListDetails().setRetrieveMetadata(false));
        if (startAfter != null && !startAfter.isEmpty()) {
            options.setStartFrom(keyPrefix + startAfter);
        }
        long steps = 0;
        long delivered = 0;
        String last = null;
        for (PagedResponse<BlobItem> page : container.listBlobs(options, null).iterableByPage()) {
            steps++;
            for (BlobItem item : page.getValue()) {
                if (Boolean.TRUE.equals(item.isPrefix())) {
                    continue;
                }
                String key = item.getName().substring(keyPrefix.length());
                // startFrom is inclusive where this cursor is exclusive, so the boundary key returns and is dropped
                // here.
                if (startAfter != null && !startAfter.isEmpty() && key.compareTo(startAfter) <= 0) {
                    continue;
                }
                if (delivered == limit) {
                    return Scan.truncated(last, delivered, steps);
                }
                BlobItemProperties properties = item.getProperties();
                // Both halves ride in the listing; no per-blob request.
                consumer.accept(properties == null
                        ? Listed.of(key)
                        : Listed.of(key,
                                properties.getContentLength() == null ? 0L : properties.getContentLength(),
                                properties.getLastModified() == null
                                        ? Instant.EPOCH : properties.getLastModified().toInstant()));
                delivered++;
                last = key;
            }
        }
        return Scan.exhausted(delivered, steps);
    }

    @Override
    public Optional<Object> version(String key) throws IOException {
        // Blob properties rather than the inherited download.
        try {
            return Optional.of(container.getBlobClient(keyPrefix + key).getProperties().getETag());
        } catch (BlobStorageException e) {
            if (BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode())
                    || BlobErrorCode.CONTAINER_NOT_FOUND.equals(e.getErrorCode())) {
                return Optional.empty();
            }
            // Only a not-found is absence; a throttle or auth failure surfaces rather than reading as "unchanged".
            throw new IOException("Could not read the version of " + key, e);
        }
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        try {
            BlobDownloadContentResponse response = container.getBlobClient(keyPrefix + key)
                    .downloadContentWithResponse(null, null, null, Context.NONE);
            return Optional.of(new Versioned(response.getValue().toBytes(), response.getDeserializedHeaders().getETag()));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return put(key, BinaryData.fromBytes(content), expected);
    }

    /** The streaming compare-and-set: the same {@code If-Match} / {@code If-None-Match} condition over a stream of
     *  known length, which Azure needs to start the upload. */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        if (!streamingWrites) {
            return put(key, BinaryData.fromBytes(content.readAllBytes()), expected);
        }
        // Spooled to an owner-only file first, as write(..) is: the client retries a refused upload by re-reading the
        // body, which a plain stream cannot replay.
        Path temporary = spool();
        try {
            try (OutputStream out = Files.newOutputStream(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                content.transferTo(out);
            }
            return put(key, BinaryData.fromFile(temporary), expected);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Both conditional writes; only the body differs. */
    private boolean put(String key, BinaryData body, Object expected) throws IOException {
        ArtifactStore.key(key);
        BlobRequestConditions conditions = new BlobRequestConditions();
        if (expected == null) {
            conditions.setIfNoneMatch("*");
        } else {
            conditions.setIfMatch((String) expected);
        }
        BlockBlobSimpleUploadOptions options = new BlockBlobSimpleUploadOptions(body)
                .setRequestConditions(conditions);
        try {
            container.getBlobClient(keyPrefix + key).getBlockBlobClient().uploadWithResponse(options, null, Context.NONE);
            return true;
        } catch (BlobStorageException e) {
            // A ContainerNotFound is a misconfiguration or outage, not a CAS conflict: as false it would become silent
            // retry exhaustion. Only a blob-level 404 (the If-Match target deleted), a 412 or a 409 is a conflict a
            // retry resolves.
            if (BlobErrorCode.CONTAINER_NOT_FOUND.equals(e.getErrorCode())) {
                throw new IOException("Could not write " + key + ": container does not exist", e);
            }
            int status = e.getStatusCode();
            if (status == 412 || status == 409 || status == 404) {
                return false;
            }
            throw new IOException("Could not write " + key, e);
        }
    }
}
