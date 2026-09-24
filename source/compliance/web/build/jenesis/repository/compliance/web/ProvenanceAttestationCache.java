package build.jenesis.repository.compliance.web;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.compliance.ProvenanceSigner;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A store-backed cache of signed provenance attestations, so the {@link ProvenanceController} signs and
 * transparency-log-appends an artifact's attestation exactly once rather than on every read. A re-sign per {@code GET}
 * grows the append-only transparency log without bound and pays a signature - and a Rekor round trip - for a read; the
 * cache serves the attestation minted on first generation instead.
 *
 * <p>The cache is content-addressed: the key carries the artifact's SHA-256, so a path re-pointed to different bytes
 * never serves the old bytes' attestation (Principle 5). The full attestation is persisted - the envelope <em>and</em>
 * the verification material bound to it at signing time (the certificate chain and the transparency-log entry) - so the
 * material endpoint serves the same signing-time material a re-sign would have rotated, from the one certificate that
 * signed. It is stored as the log's own JSON shapes (hex hashes, base64 body and receipt), read back the way
 * {@code RekorTransparencyLog} reads Rekor's answer, so the round trip needs no reflective record binding.
 */
final class ProvenanceAttestationCache {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArtifactStore store;

    ProvenanceAttestationCache(ArtifactStore store) {
        this.store = store;
    }

    /** The stored attestation for this artifact ({@code sha256}) and coordinate ({@code path}), or empty when none
     *  has been generated yet - the miss that drives the one-time sign-and-append. */
    Optional<ProvenanceSigner.Attestation> read(String sha256, String path) throws IOException {
        return store.readVersioned(key(sha256, path)).map(versioned -> deserialize(versioned.content()));
    }

    /** Persist a freshly signed attestation under its content-and-coordinate key. Compare-and-set create: a reader
     *  that raced the first generation and already stored one wins, and its equivalent attestation is what later reads
     *  serve - neither racer loses correctness, and the store is never written twice for the same content. */
    void write(String sha256, String path, ProvenanceSigner.Attestation attestation) throws IOException {
        store.writeVersioned(key(sha256, path), serialize(attestation), null);
    }

    /** The store prefix this cache owns - declared by {@link ProvenanceAttestationStorageNamespace} and reaped by
     *  {@link ProvenanceAttestationReaper} when a served pointer is unpublished. */
    static final String PREFIX = "provenance-attestation";

    /** The content-addressed cache key: the artifact's SHA-256 first (so changed bytes miss and regenerate), then the
     *  hashed coordinate (so two paths sharing content keep their own per-path statements distinct). Package-private
     *  so the pointer-deletion reaper rebuilds the identical key from an {@code onDeleted} descriptor's blob hash and
     *  served path, reclaiming this content-keyed sidecar the one way its (blob, path) identity allows. */
    static String key(String sha256, String path) {
        return PREFIX + "/" + sha256 + "/" + Checksums.sha256(path.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] serialize(ProvenanceSigner.Attestation attestation) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("envelope", attestation.envelope());
        root.put("certificateChainPem", attestation.certificateChainPem());
        ProvenanceSigner.TransparencyLogEntry entry = attestation.transparencyLog();
        if (entry != null) {
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("uuid", entry.uuid());
            log.put("logId", entry.logId());
            log.put("logIndex", entry.logIndex());
            log.put("integratedTime", entry.integratedTime());
            log.put("signedEntryTimestamp", entry.signedEntryTimestamp());
            log.put("canonicalizedBody", entry.canonicalizedBody());
            ProvenanceSigner.InclusionProof proof = entry.inclusionProof();
            if (proof != null) {
                Map<String, Object> inclusion = new LinkedHashMap<>();
                inclusion.put("logIndex", proof.logIndex());
                inclusion.put("treeSize", proof.treeSize());
                inclusion.put("rootHash", proof.rootHash());
                inclusion.put("hashes", proof.hashes());
                inclusion.put("checkpoint", proof.checkpoint());
                log.put("inclusionProof", inclusion);
            }
            root.put("transparencyLog", log);
        }
        return JSON.writeValueAsBytes(root);
    }

    private static ProvenanceSigner.Attestation deserialize(byte[] bytes) {
        JsonNode root = JSON.readTree(bytes);
        ProvenanceSigner.TransparencyLogEntry entry = null;
        JsonNode log = root.path("transparencyLog");
        if (log.isObject()) {
            JsonNode proof = log.path("inclusionProof");
            ProvenanceSigner.InclusionProof inclusion = null;
            if (proof.isObject()) {
                List<String> hashes = new ArrayList<>();
                for (JsonNode hash : proof.path("hashes")) {
                    hashes.add(hash.asString(""));
                }
                inclusion = new ProvenanceSigner.InclusionProof(
                        proof.path("logIndex").asLong(-1),
                        proof.path("treeSize").asLong(0),
                        proof.path("rootHash").asString(""),
                        List.copyOf(hashes),
                        proof.path("checkpoint").asString(""));
            }
            entry = new ProvenanceSigner.TransparencyLogEntry(
                    log.path("uuid").asString(null),
                    log.path("logId").asString(null),
                    log.path("logIndex").asLong(-1),
                    log.path("integratedTime").asLong(0),
                    log.path("signedEntryTimestamp").asString(null),
                    log.path("canonicalizedBody").asString(null),
                    inclusion);
        }
        return new ProvenanceSigner.Attestation(
                root.path("envelope").asString(null),
                root.path("certificateChainPem").asString(null),
                entry);
    }
}
