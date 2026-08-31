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
import build.jenesis.repository.store.s3compatible.S3CompatibleArtifactStore;

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
public final class S3ArtifactStore extends S3CompatibleArtifactStore {

    /** Owner-only (0600) permissions for the upload spool file - see {@link #spool()}. */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final S3Presigner presigner;
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
        super(s3, bucket, keyPrefix);
        this.presigner = presigner;
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

        /**
     * The streaming compare-and-set: the same {@code If-Match} / {@code If-None-Match} precondition, with the body
     * as a stream of known length rather than an array. S3 needs the length to start the upload, which is why the
     * caller supplies one.
     */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        return put(key, RequestBody.fromInputStream(content, length), expected);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return put(key, RequestBody.fromBytes(content), expected);
    }

    /** Both conditional writes, which differ only in how the body is carried. */
    private boolean put(String key, RequestBody body, Object expected) throws IOException {
        ArtifactStore.key(key);
        try {
            if (expected == null) {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key).ifNoneMatch("*"), kmsKeyId), body);
            } else {
                s3.putObject(b -> encrypt(b.bucket(bucket).key(keyPrefix + key).ifMatch((String) expected), kmsKeyId), body);
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

}
