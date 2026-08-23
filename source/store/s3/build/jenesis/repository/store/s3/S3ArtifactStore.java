package build.jenesis.repository.store.s3;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * An {@link ArtifactStore} backed by an S3-compatible bucket (AWS S3, GCS via the XML API, MinIO,
 * LocalStack) on the AWS SDK v2. A blob is the object at its key; a tenant or repository is a key
 * prefix (see {@link #scope}). The version token is the object ETag, so {@link #writeVersioned} is a
 * true cross-node compare-and-set: {@code expected == null} maps to a conditional {@code If-None-Match: *}
 * put (write only if the key is still absent) and a non-null token to an {@code If-Match: <etag>} put
 * (write only if the stored object is unchanged); a {@code 412 Precondition Failed} (or the {@code 409}
 * a concurrent conditional write can raise) becomes a {@code false} return, so the caller re-reads and
 * retries. Concurrent {@code maven-metadata.xml} edits and lock acquisitions across many nodes therefore
 * resolve through S3 itself, with no database or lock service.
 */
public final class S3ArtifactStore implements ArtifactStore {

    /** Owner-only (0600) permissions for the upload spool file - see {@link #spool()}. */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String keyPrefix;
    /** The KMS key id for {@code aws:kms} encryption, or {@code null} for the SSE-S3 (AES256) default. */
    private final String kmsKeyId;

    public S3ArtifactStore(S3Client s3, String bucket) {
        this(s3, null, bucket, "", null);
    }

    /** As {@link #S3ArtifactStore(S3Client, String)} but with a {@link S3Presigner} - built by the provider from the
     *  same region, credentials and endpoint as {@code s3} - so {@link #presign} can mint a direct-fetch GET URL. */
    public S3ArtifactStore(S3Client s3, S3Presigner presigner, String bucket) {
        this(s3, presigner, bucket, "", null);
    }

    /** As {@link #S3ArtifactStore(S3Client, String)} but with an {@code aws:kms} key id for server-side encryption. */
    public S3ArtifactStore(S3Client s3, String bucket, String kmsKeyId) {
        this(s3, null, bucket, "", kmsKeyId);
    }

    /** Full store: a {@link S3Presigner} for direct-fetch GET URLs ({@link #presign}) and an {@code aws:kms} key id
     *  for server-side encryption ({@link #encrypt}). Either may be {@code null} to fall back to streaming / SSE-S3. */
    public S3ArtifactStore(S3Client s3, S3Presigner presigner, String bucket, String kmsKeyId) {
        this(s3, presigner, bucket, "", kmsKeyId);
    }

    private S3ArtifactStore(S3Client s3, S3Presigner presigner, String bucket, String keyPrefix, String kmsKeyId) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new S3ArtifactStore(s3, presigner, bucket, keyPrefix + ArtifactStore.segment(tenant) + "/", kmsKeyId);
    }

    @Override
    public Object identity() {
        return "s3:" + bucket + "/" + keyPrefix;
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        // No presigner configured (the two-arg constructor, or a store built without one): degrade to streaming.
        if (presigner == null) {
            return Optional.empty();
        }
        try {
            PresignedGetObjectRequest presigned = presigner.presignGetObject(b -> b
                    .signatureDuration(ttl)
                    .getObjectRequest(r -> r.bucket(bucket).key(keyPrefix + key)));
            return Optional.of(presigned.url().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Presigned S3 URL is not a valid URI for " + key, e);
        }
    }

    /**
     * Applies the store's server-side encryption to an object write. Every {@code PutObject} the store issues -
     * plain, content-addressed or conditional - is built through here, so an object is never written unencrypted:
     * SSE-S3 ({@link ServerSideEncryption#AES256}) by default, or {@code aws:kms} with {@code kmsKeyId} when one is
     * configured ({@code jenreg.s3.sse-kms-key-id}). There is deliberately no way to switch encryption off
     * - a blank or absent key simply falls back to the AES256 default rather than disabling it.
     */
    public static PutObjectRequest.Builder encrypt(PutObjectRequest.Builder builder, String kmsKeyId) {
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            return builder.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
        }
        return builder.serverSideEncryption(ServerSideEncryption.AES256);
    }

    /**
     * A temp file for spooling an artifact body, readable and writable only by its owner. A blob is buffered here to
     * learn its length (and, for a content-addressed write, its SHA-256) before the length-prefixed S3 {@code PutObject}
     * - the body cannot stream straight through the sync client without its length up front. A shared {@code /tmp}
     * spool would leave the plaintext artifact bytes world-readable for the life of the upload, so on a POSIX
     * filesystem the file is created {@code 0600} at open time (never briefly world-readable). A non-POSIX filesystem
     * that cannot express owner-only permissions at create time falls back to a default temp file, then tightens it
     * best-effort through the {@link File} API.
     */
    private static Path spool() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile("s3-artifact-", null, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        Path temporary = Files.createTempFile("s3-artifact-", null);
        File file = temporary.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        return temporary;
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key));
            return true;
        } catch (S3Exception e) {
            // Only a 404 means absent; a throttle or auth failure must fail the request loudly, or a published
            // artifact silently turns into a miss (served as 404) for as long as the backend misbehaves.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public long size(String key) throws IOException {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key)).contentLength();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return -1L;
            }
            throw new IOException("Could not size " + key, e);
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try {
            if (out instanceof ArtifactStore.RangedSink ranged) {
                String range = "bytes=" + ranged.offset() + "-" + (ranged.offset() + ranged.length() - 1);
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                        b -> b.bucket(bucket).key(keyPrefix + key).range(range))) {
                    in.transferTo(ranged.sink());
                }
            } else {
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key))) {
                    in.transferTo(out);
                }
            }
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key));
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        ArtifactStore.key(key);
        // S3 PutObject needs the content length up front, so buffer the (possibly large) body to an owner-only
        // temp file rather than into memory, then upload from the file.
        Path temporary = spool();
        try {
            // Write through the already-0600 spool with a TRUNCATE_EXISTING open, NOT Files.copy(REPLACE_EXISTING):
            // the latter deletes the target and recreates it CREATE_NEW under the process umask (typically world-
            // readable 0644), silently undoing spool()'s owner-only attribute for the life of the upload. Opening the
            // existing file WRITE+TRUNCATE_EXISTING preserves its 0600 permissions (writeBlob already does this).
            try (OutputStream out = Files.newOutputStream(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key), kmsKeyId), RequestBody.fromFile(temporary));
        } catch (S3Exception e) {
            throw new IOException("Could not write " + key, e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        // S3 PutObject needs the content length and the key up front, but a content-addressed key is the hash of
        // the very bytes being written; buffer the (possibly large) body to a temp file while digesting it, then
        // upload from the file under blobs/<hash> - never holding the whole artifact in memory.
        Path temporary = spool();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String key = "blobs/" + HexFormat.of().formatHex(digest.digest());
            if (!exists(key)) {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key), kmsKeyId), RequestBody.fromFile(temporary));
            }
            return key.substring("blobs/".length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (S3Exception e) {
            throw new IOException("Could not write blob", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(keyPrefix + key));
        } catch (S3Exception e) {
            throw new IOException("Could not delete " + key, e);
        }
    }

    /** The storage prefix of a listing container - the scope's key prefix and the normalised container name with its
     *  trailing delimiter - so a caller's {@code a/b/} and {@code a/b} ask the service for one prefix. */
    private String base(String prefix) {
        String container = ArtifactStore.container(prefix);
        return keyPrefix + (container.isEmpty() ? "" : container + "/");
    }

    @Override
    public List<String> list(String prefix) {
        String base = base(prefix);
        TreeSet<String> names = new TreeSet<>();
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(base).delimiter("/"))) {
            page.commonPrefixes().forEach(common -> {
                String name = common.prefix().substring(base.length());
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                if (!name.isEmpty()) {
                    names.add(name);
                }
            });
            for (S3Object object : page.contents()) {
                String name = object.key().substring(base.length());
                if (!name.isEmpty() && name.indexOf('/') < 0) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        // The names-only view of pageListed: the ordering rules below are subtle enough that a second copy
        // would drift, so this form derives from that one rather than repeating it.
        pageListed(prefix, startAfter, limit, listed -> consumer.accept(name(listed.key())));
    }

    private static String name(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    @Override
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            return;
        }
        String base = base(prefix);
        // The stream arrives in raw key order, where a container shows up as a grouped prefix at `name + "/"` -
        // AFTER any sibling whose name extends this one past a character below '/' (the object `app.txt`
        // precedes the grouped prefix `app/`, yet the child `app` must page before `app.txt`). Emitting in
        // child-NAME order therefore parks every name and releases the smallest parked one only once no
        // smaller-named child can still arrive - see held(). A released name at or below startAfter is dropped:
        // the server-side start-after skips the boundary's own object but not a same-named container's grouped
        // prefix, and a prefix-child of the boundary (`app` for `app.txt`) re-arrives here yet was already paged
        // by the call that emitted the boundary itself.
        // Keyed by child NAME, valued by what the listing said about it: a leaf carries its
        // size and age straight off the response, a grouped prefix carries neither because a
        // container has none of its own. Nothing here issues a request to fill them.
        TreeMap<String, Listed> pending = new TreeMap<>();
        int emitted = 0;
        String last = null;
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> {
            b.bucket(bucket).prefix(base).delimiter("/").maxKeys(Math.min(ArtifactStore.oneMoreThan(limit), 1000));
            if (!startAfter.isEmpty()) {
                b.startAfter(base + startAfter);
            }
        })) {
            List<String> ordered = new ArrayList<>();
            Map<String, S3Object> objects = new HashMap<>();
            for (S3Object object : page.contents()) {
                String relative = object.key().substring(base.length());
                if (!relative.isEmpty() && relative.indexOf('/') < 0) {
                    ordered.add(relative);
                    objects.put(relative, object);
                }
            }
            for (CommonPrefix common : page.commonPrefixes()) {
                String relative = common.prefix().substring(base.length());
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
                    // A leaf and a same-named container page as one child; the leaf's metadata
                    // is kept, because that is what a GET of this key resolves to.
                    pending.merge(name, listed(prefix, name, objects.get(relative)),
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

    /** A child as the listing saw it. {@code object} is null for a grouped prefix - a container - which reports no
     *  size or age because it has none; both halves of a leaf's metadata ride along in the response already. */
    private static Listed listed(String prefix, String name, S3Object object) {
        String container = ArtifactStore.container(prefix);
        String key = container.isEmpty() ? name : container + "/" + name;
        if (object == null) {
            return Listed.of(key);
        }
        return Listed.of(key,
                object.size() == null ? 0L : object.size(),
                object.lastModified() == null ? Instant.EPOCH : object.lastModified());
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
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        String base = base(prefix);
        // No delimiter, and therefore none of page()'s name-order repair: a recursive scan wants every object under
        // the prefix, and without grouped prefixes the listing arrives in exactly the key order this method owes.
        // maxKeys is limit + 1 so the page after the last delivered key is what proves whether more remains, rather
        // than a second request asking.
        long steps = 0;
        long delivered = 0;
        String last = null;
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(b -> {
            b.bucket(bucket).prefix(base).maxKeys(Math.min(ArtifactStore.oneMoreThan(limit), 1000));
            if (startAfter != null && !startAfter.isEmpty()) {
                b.startAfter(keyPrefix + startAfter);
            }
        })) {
            steps++;
            for (S3Object object : page.contents()) {
                if (delivered == limit) {
                    return Scan.truncated(last, delivered, steps);
                }
                String key = object.key().substring(keyPrefix.length());
                // Both halves come out of the listing response, so a scanned page costs exactly its listing calls.
                consumer.accept(Listed.of(key,
                        object.size() == null ? 0L : object.size(),
                        object.lastModified() == null ? Instant.EPOCH : object.lastModified()));
                delivered++;
                last = key;
            }
        }
        return Scan.exhausted(delivered, steps);
    }

    @Override
    public Optional<Object> version(String key) throws IOException {
        // A metadata request, where the inherited default would download the object to read its ETag.
        try {
            return Optional.of(s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key)).eTag());
        } catch (NoSuchKeyException _) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            // Only a 404 is absence. A throttle or an auth failure must surface, not read as "unchanged".
            throw new IOException("Could not read the version of " + key, e);
        }
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(keyPrefix + key))) {
            byte[] content = in.readAllBytes();
            return Optional.of(new Versioned(content, in.response().eTag()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        ArtifactStore.key(key);
        try {
            if (expected == null) {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key).ifNoneMatch("*"), kmsKeyId), RequestBody.fromBytes(content));
            } else {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key).ifMatch((String) expected), kmsKeyId), RequestBody.fromBytes(content));
            }
            return true;
        } catch (S3Exception e) {
            // A bucket-level 404 (NoSuchBucket) is a misconfiguration or outage, not a CAS conflict: mapping it to a
            // false return would turn a missing/renamed bucket into silent retry-exhaustion at the caller. Surface it
            // as a real IOException. Only a key-level 404 (the object an If-Match refers to has been deleted) is the
            // benign conflict a re-read-and-retry resolves, alongside the 412/409 precondition rejections.
            if (e.awsErrorDetails() != null && "NoSuchBucket".equals(e.awsErrorDetails().errorCode())) {
                throw new IOException("Could not write " + key + ": bucket " + bucket + " does not exist", e);
            }
            int status = e.statusCode();
            if (status == 412 || status == 409 || status == 404) {
                return false;
            }
            throw new IOException("Could not write " + key, e);
        }
    }

    @Override
    public List<BatchOutcome> writeBatch(List<BatchWrite> writes) throws IOException {
        // Best-effort, per-key CAS, not a transaction (S3 has no multi-object atomicity): issue the conditional PUTs
        // bounded-parallel so a k-write commit is ~1 round-trip instead of k, classifying each 412/409/404 conflict
        // exactly as writeVersioned. The shared helper keeps input order and never overlaps two writes to one key.
        return ArtifactStore.writeBatchParallel(this, writes);
    }
}
