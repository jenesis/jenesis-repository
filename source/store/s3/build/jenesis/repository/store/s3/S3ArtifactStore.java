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
import build.jenesis.repository.store.OwnerOnly;

/**
 * An {@link ArtifactStore} over an S3-compatible bucket (AWS S3, GCS through the XML API, MinIO, LocalStack) on the AWS
 * SDK v2. A blob is the object at its key; a tenant or repository is a key prefix ({@link #scope}). The version token
 * is the ETag, so {@link #writeVersioned} is a cross-node compare-and-set: {@code expected == null} is a conditional
 * {@code If-None-Match: *} put and a token an {@code If-Match: <etag>} put; a {@code 412}, or the {@code 409} a
 * concurrent conditional write can raise, becomes {@code false}, so the caller re-reads and retries. Concurrent writers
 * across nodes resolve through S3 itself.
 */
public final class S3ArtifactStore extends S3CompatibleArtifactStore {

    private final S3Presigner presigner;
    /** The KMS key id for {@code aws:kms} encryption, or {@code null} for the SSE-S3 (AES256) default. */
    private final String kmsKeyId;

    /** Whether a conditional write may stream its body; see {@code S3ArtifactStoreProvider}. */
    private final boolean streamingWrites;

    public S3ArtifactStore(S3Client s3, String bucket) {
        this(s3, null, bucket, "", null, true);
    }

    /** As {@link #S3ArtifactStore(S3Client, String)} with a {@link S3Presigner}, built from the client's region,
     *  credentials and endpoint, so {@link #presign} can mint a direct-fetch GET URL. */
    public S3ArtifactStore(S3Client s3, S3Presigner presigner, String bucket) {
        this(s3, presigner, bucket, "", null, true);
    }

    /** The provider's constructor, the only one deciding {@code streamingWrites}; the others take the default. */
    public S3ArtifactStore(S3Client s3, S3Presigner presigner, String bucket, String kmsKeyId,
                           boolean streamingWrites) {
        this(s3, presigner, bucket, "", kmsKeyId, streamingWrites);
    }

    private S3ArtifactStore(S3Client s3, S3Presigner presigner, String bucket, String keyPrefix, String kmsKeyId,
                            boolean streamingWrites) {
        super(s3, bucket, keyPrefix);
        this.presigner = presigner;
        this.kmsKeyId = kmsKeyId;
        this.streamingWrites = streamingWrites;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new S3ArtifactStore(s3, presigner, bucket, keyPrefix + ArtifactStore.segment(tenant) + "/", kmsKeyId,
                streamingWrites);
    }

    @Override
    public Object identity() {
        return "s3:" + bucket + "/" + keyPrefix;
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        // No presigner: degrade to streaming.
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

    /** Applies server-side encryption to an object write; every {@code PutObject} the store issues is built through
     *  here, so nothing is written unencrypted: SSE-S3 ({@link ServerSideEncryption#AES256}) by default, or
     *  {@code aws:kms} with {@code kmsKeyId} ({@code jenrepo.s3.sse-kms-key-id}). There is no way to switch it off; a
     *  blank key means AES256. */
    public static PutObjectRequest.Builder encrypt(PutObjectRequest.Builder builder, String kmsKeyId) {
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            return builder.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
        }
        return builder.serverSideEncryption(ServerSideEncryption.AES256);
    }

    /** The owner-only upload spool ({@link OwnerOnly}): {@code PutObject} needs the length up front (and a
     *  content-addressed write its SHA-256), so a body is buffered here, never world-readable in a shared
     *  {@code /tmp}. */
    private static Path spool() throws IOException {
        return OwnerOnly.createTempFile("s3-artifact-", null);
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        ArtifactStore.key(key);
        // PutObject needs the length up front, so the body is buffered to an owner-only file, not memory.
        Path temporary = spool();
        try {
            // Written through the 0600 spool with WRITE+TRUNCATE_EXISTING, not Files.copy(REPLACE_EXISTING), which
            // would recreate the file under the umask and undo its owner-only permission.
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
        // PutObject needs the length and key up front, but a content-addressed key is the hash of the bytes, so the
        // body is spooled while digested and uploaded from the file under blobs/<hash>.
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
        // A metadata request rather than the inherited download.
        try {
            return Optional.of(s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key)).eTag());
        } catch (NoSuchKeyException _) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            // Only a 404 is absence; a throttle or auth failure surfaces rather than reading as "unchanged".
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

        /** The streaming compare-and-set: the same {@code If-Match} / {@code If-None-Match} precondition over a stream
         *  of known length, which S3 needs to start the upload. */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        if (!streamingWrites) {
            return put(key, RequestBody.fromBytes(content.readAllBytes()), expected);
        }
        // Spooled to an owner-only file first, as write(..) is: the SDK retries a PutObject refused with a retryable
        // status (a 503, a dropped connection) by re-reading the body, which a plain stream cannot give. The file gives
        // every attempt the whole body and the heap none of it.
        Path temporary = spool();
        try {
            try (OutputStream out = Files.newOutputStream(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                content.transferTo(out);
            }
            return put(key, RequestBody.fromFile(temporary), expected);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return put(key, RequestBody.fromBytes(content), expected);
    }

    /** Both conditional writes, differing only in how the body is carried. */
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
            // A NoSuchBucket is a misconfiguration or outage, not a CAS conflict: as false it would become silent retry
            // exhaustion. Only a key-level 404 (the If-Match target deleted), a 412 or a 409 is a conflict a retry
            // resolves.
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
