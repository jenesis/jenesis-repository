package build.jenesis.repository.compliance.web;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.compliance.ProvenanceSigner;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A store-backed cache of signed provenance attestations, so {@link ProvenanceController} signs and appends an
 * artifact's attestation once rather than per read, which would grow the transparency log without bound. Keyed by the
 * artifact's SHA-256, so re-pointed bytes miss. The whole attestation is kept, envelope and signing-time material, in
 * the log's own JSON shapes.
 */
final class ProvenanceAttestationCache {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArtifactStore store;

    ProvenanceAttestationCache(ArtifactStore store) {
        this.store = store;
    }

    /** The stored attestation for {@code sha256} and {@code path}, or empty before the first generation. */
    Optional<ProvenanceSigner.Attestation> read(String sha256, String path) throws IOException {
        return store.readVersioned(key(sha256, path)).map(versioned -> deserialize(versioned.content()));
    }

    /** Stores a freshly signed attestation create-if-absent, so of two racing generations the first stored wins. */
    void write(String sha256, String path, ProvenanceSigner.Attestation attestation) throws IOException {
        store.writeVersioned(key(sha256, path), serialize(attestation), null);
    }

    /** The store prefix this cache owns, declared by {@link ProvenanceAttestationStorageNamespace}. */
    static final String PREFIX = "provenance-attestation";

    /** The cache key: the artifact's SHA-256, then the hashed path, so two paths sharing content keep their own
     *  statements. The reaper rebuilds it from an {@code onDeleted} descriptor. */
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
