package build.jenesis.repository.store.gcs;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import build.jenesis.repository.store.s3compatible.S3CompatibleArtifactStore;

/**
 * An {@link ArtifactStore} backed by a Google Cloud Storage bucket over GCS's S3-compatible XML API
 * on the AWS SDK v2. A blob is the object at its key; a tenant or repository is a key prefix (see
 * {@link #scope}). The streaming surface matches the {@code s3} backend: reads transfer straight from
 * the response stream, a ranged read issues a real {@code Range} GET, and an upload spills to a temp
 * file (the XML API needs the content length up front), never to the heap. Conditional writes differ:
 * GCS honours {@code If-Match} / {@code If-None-Match} only on reads, so the version token is the
 * object <em>generation</em> (the {@code x-goog-generation} response header) and {@link #writeVersioned}
 * maps to GCS's own precondition - {@code x-goog-if-generation-match: 0} for a create-if-absent and
 * {@code x-goog-if-generation-match: <generation>} for an update-if-unchanged - whose {@code 412
 * Precondition Failed} becomes a {@code false} return, so the caller re-reads and retries. Concurrent
 * {@code maven-metadata.xml} edits and lock acquisitions across many nodes therefore resolve through
 * GCS itself, with no database or lock service. Because the precondition is GCS-specific, versioned
 * writes need a real GCS endpoint; a generic S3-compatible store belongs on the {@code s3} backend.
 */
public final class GcsArtifactStore extends S3CompatibleArtifactStore {

    /** The GCS object-generation response header carrying the version token. */
    private static final String GENERATION = "x-goog-generation";
    /** The GCS write precondition: proceed only if the stored generation matches ({@code 0} = absent). */
    private static final String IF_GENERATION_MATCH = "x-goog-if-generation-match";
    /** Upload-spool permissions: readable and writable only by the owner, matching the {@code s3} backend. */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final S3Presigner presigner;

    /** Whether a conditional write may stream its body; see {@code GcsArtifactStoreProvider}. */
    private final boolean streamingWrites;

    public GcsArtifactStore(S3Client s3, String bucket) {
        this(s3, null, bucket, "", true);
    }

    /** As {@link #GcsArtifactStore(S3Client, String)} but with a {@link S3Presigner} - built by the provider from the
     *  same region, credentials and endpoint as {@code s3} - so {@link #presign} can mint a direct-fetch GET URL.
     *  GCS's S3-compatible XML API signs identically to S3, so this is the same presigner path. */
    public GcsArtifactStore(S3Client s3, S3Presigner presigner, String bucket) {
        this(s3, presigner, bucket, "", true);
    }

    /** The provider's constructor, the only one that decides {@code streamingWrites}. */
    public GcsArtifactStore(S3Client s3, S3Presigner presigner, String bucket, boolean streamingWrites) {
        this(s3, presigner, bucket, "", streamingWrites);
    }

    private GcsArtifactStore(S3Client s3, S3Presigner presigner, String bucket, String keyPrefix,
                             boolean streamingWrites) {
        super(s3, bucket, keyPrefix);
        this.presigner = presigner;
        this.streamingWrites = streamingWrites;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new GcsArtifactStore(s3, presigner, bucket, keyPrefix + ArtifactStore.segment(tenant) + "/",
                streamingWrites);
    }

    @Override
    public Object identity() {
        return "gcs:" + bucket + "/" + keyPrefix;
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        // GCS's S3-compatible XML API honours SigV4 presigned GET URLs exactly as S3 does, so this is the identical
        // presigner path; with no presigner configured, degrade to streaming.
        if (presigner == null) {
            return Optional.empty();
        }
        try {
            PresignedGetObjectRequest presigned = presigner.presignGetObject(b -> b
                    .signatureDuration(ttl)
                    .getObjectRequest(r -> r.bucket(bucket).key(keyPrefix + key)));
            return Optional.of(presigned.url().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Presigned GCS URL is not a valid URI for " + key, e);
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        ArtifactStore.key(key);
        // The XML API needs the content length up front, so buffer the (possibly large) body to an owner-only
        // temp file rather than into memory, then upload from the file.
        Path temporary = spool();
        try {
            // Write through the already-0600 spool with a TRUNCATE_EXISTING open, NOT Files.copy(REPLACE_EXISTING):
            // the latter deletes the target and recreates it CREATE_NEW under the process umask (typically world-
            // readable 0644), silently undoing the owner-only attribute for the life of the upload. Opening the
            // existing file WRITE+TRUNCATE_EXISTING preserves its 0600 permissions.
            try (OutputStream out = Files.newOutputStream(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key), RequestBody.fromFile(temporary));
        } catch (S3Exception e) {
            throw new IOException("Could not write " + key, e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        // The upload needs the content length and the key up front, but a content-addressed key is the hash of
        // the very bytes being written; buffer the (possibly large) body to an owner-only temp file while digesting
        // it, then upload from the file under blobs/<hash> - never holding the whole artifact in memory. The
        // newOutputStream below opens the existing 0600 spool (truncate-in-place), so it keeps the owner-only perms.
        Path temporary = spool();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String key = "blobs/" + HexFormat.of().formatHex(digest.digest());
            if (!exists(key)) {
                s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key), RequestBody.fromFile(temporary));
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

    /**
     * An upload-spool temp file readable and writable only by its owner, matching the {@code s3} backend. The XML API
     * needs a content length up front, so a PUT body is buffered here before upload; a shared {@code /tmp} spool would
     * leave the plaintext artifact bytes world-readable for the life of the upload, so on a POSIX filesystem the file
     * is created {@code 0600} at open time. A non-POSIX filesystem that cannot express owner-only permissions at create
     * time falls back to a default temp file, then tightens it best-effort through the {@link File} API.
     */
    private static Path spool() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile("gcs-artifact-", null, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        Path temporary = Files.createTempFile("gcs-artifact-", null);
        File file = temporary.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        return temporary;
    }

    @Override
    public Optional<Object> version(String key) throws IOException {
        // A metadata request, where the inherited default would download the object to read its generation. The token
        // is the GENERATION, not the ETag: it is what writeVersioned sends back as x-goog-if-generation-match, so a
        // version() that reported an ETag would hand callers a token no write would accept.
        try {
            return Optional.of(s3.headObject(b -> b.bucket(bucket).key(keyPrefix + key))
                    .sdkHttpResponse().firstMatchingHeader(GENERATION).orElseThrow(() -> new IOException(
                            "The endpoint returned no " + GENERATION + " header for " + key
                                    + " - versioned reads need a real GCS XML endpoint.")));
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
            String generation = in.response().sdkHttpResponse().firstMatchingHeader(GENERATION).orElseThrow(
                    () -> new IOException("The endpoint returned no " + GENERATION + " header for " + key
                            + " - versioned writes need a real GCS XML endpoint; use the s3 backend for"
                            + " generic S3-compatible stores"));
            return Optional.of(new Versioned(content, generation));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

        /**
     * The streaming compare-and-set: the same {@code x-goog-if-generation-match} precondition over a stream of
     * known length. GCS needs the length to start the upload.
     */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        return streamingWrites
                ? put(key, RequestBody.fromInputStream(content, length), expected)
                : put(key, RequestBody.fromBytes(content.readAllBytes()), expected);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return put(key, RequestBody.fromBytes(content), expected);
    }

    /** Both conditional writes; the generation precondition is identical, only the body differs. */
    private boolean put(String key, RequestBody body, Object expected) throws IOException {
        ArtifactStore.key(key);
        String generation = expected == null ? "0" : (String) expected;
        try {
            s3.putObject(b -> b.bucket(bucket).key(keyPrefix + key)
                            .overrideConfiguration(c -> c.putHeader(IF_GENERATION_MATCH, generation)),
                    body);
            return true;
        } catch (S3Exception e) {
            // A bucket-level 404 (NoSuchBucket) is a misconfiguration or outage, not a CAS conflict: mapping it to a
            // false return would turn a missing/renamed bucket into silent retry-exhaustion at the caller. Surface it
            // as a real IOException. Only a key-level 404 (the object an if-generation-match refers to has been
            // deleted) is the benign conflict a re-read-and-retry resolves, alongside the 412/409 rejections.
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
