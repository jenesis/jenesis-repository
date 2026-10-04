package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.ProvenanceSigner;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provenance API: the signed attestation of an artifact's identity and the material bound to it. An attestation is
 * an in-toto Statement over the artifact's SHA-256, taken from its {@code blobs/<sha256>} key, signed by the configured
 * {@link ProvenanceSigner} and, for a transparency-logged signer, appended to the log before it is served. It is cached
 * by that SHA-256 ({@link ProvenanceAttestationCache}), so an artifact is signed and appended once, and the first
 * generation is audited. With no signer the endpoints answer {@code 404}. Gated {@code manage:read}; the tenant is the
 * routing's, and an unsafe name is a {@code 400}.
 */
@RestController
public class ProvenanceController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final Supplier<ProvenanceSigner> provenanceSigner;
    private final AuditTrail audit;

    public ProvenanceController(Repositories repositories, RepositoryRouting routing,
                                Supplier<ProvenanceSigner> provenanceSigner, AuditTrail audit) {
        this.repositories = repositories;
        this.routing = routing;
        this.provenanceSigner = provenanceSigner;
        this.audit = audit;
    }

    @GetMapping(value = "/api/provenance", produces = "application/json", params = "!material")
    @ResponseBody
    public String provenance(@RequestParam("repo") String repo,
                             @RequestParam("path") String path,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProvenanceSigner.Attestation attestation = attested(repo, path, key, request, response);
        return attestation == null ? null : attestation.envelope();
    }

    /**
     * With {@code material}, the attestation with the material bound at signing: the certificate chain and the
     * transparency-log entry, each {@code null} where the signer has none (a bare key publishes {@link #provenanceKey}).
     * It travels with the envelope because a keyless signer's certificate rotates every few minutes; the keyless verify
     * path checks the chain to the pinned Fulcio root, the inclusion proof to the signed root, and the envelope to the
     * leaf key.
     */
    @GetMapping(value = "/api/provenance", produces = "application/json", params = "material")
    @ResponseBody
    public ProvenanceMaterialView provenanceMaterial(@RequestParam("repo") String repo,
                                                     @RequestParam("path") String path,
                                                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) throws IOException {
        ProvenanceSigner.Attestation attestation = attested(repo, path, key, request, response);
        if (attestation == null) {
            return null;
        }
        ProvenanceSigner.TransparencyLogEntry entry = attestation.transparencyLog();
        return new ProvenanceMaterialView(attestation.envelope(), attestation.certificateChainPem(),
                entry == null ? null : new TransparencyLogView(entry.uuid(), entry.logId(), entry.logIndex(),
                        entry.integratedTime(), entry.signedEntryTimestamp(), entry.canonicalizedBody(),
                        new InclusionProofView(entry.inclusionProof().logIndex(), entry.inclusionProof().treeSize(),
                                entry.inclusionProof().rootHash(), entry.inclusionProof().hashes(),
                                entry.inclusionProof().checkpoint())));
    }

    /** The cached or freshly signed attestation of an artifact, or {@code null} after setting the status when access is
     *  refused, no signer is configured or the artifact is absent. */
    private ProvenanceSigner.Attestation attested(String repo, String path, String key, HttpServletRequest request,
                                                  HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return null;
        }
        if (!provenanceSigner.get().enabled()) {
            response.setStatus(404);
            return null;
        }
        RepositoryRequests.rejectTraversal(path);
        ArtifactStore store = repositories.store(tenant, repo);
        // The attestation names the artifact by the path a client uses; the publication by the format's layout.
        Optional<String> located = new Publication(store).located(repositories.formatPath(tenant, repo, path));
        if (located.isEmpty()) {
            response.setStatus(404);
            return null;
        }
        // located is blobs/<sha256>, so the hash is read off the key rather than recomputed.
        String sha256 = located.get().substring(located.get().indexOf('/') + 1);
        // Keyed by the SHA-256, so a path re-pointed to other bytes misses and is attested afresh.
        ProvenanceAttestationCache cache = new ProvenanceAttestationCache(store);
        Optional<ProvenanceSigner.Attestation> cached = cache.read(sha256, path);
        if (cached.isPresent()) {
            return cached.get();
        }
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("repository", repo);
        predicate.put("tenant", tenant);
        predicate.put("attestedAt", Instant.now().toString());
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("_type", "https://in-toto.io/Statement/v1");
        statement.put("subject", List.of(Map.of("name", path, "digest",
                Map.of("sha256", sha256))));
        statement.put("predicateType", "https://jenesis.build/attestation/v1");
        statement.put("predicate", predicate);
        ProvenanceSigner.Attestation attestation;
        try {
            attestation = provenanceSigner.get().attest(statement);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign the provenance attestation", e);
        }
        // Minting and appending is security-relevant, so the generation is audited.
        cache.write(sha256, path, attestation);
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), "provenance.generate", repo + path);
        return attestation;
    }

    @GetMapping(value = "/api/provenance/key", produces = "application/x-pem-file")
    @ResponseBody
    public String provenanceKey(HttpServletResponse response) {
        if (!provenanceSigner.get().enabled()) {
            response.setStatus(404);
            return null;
        }
        return provenanceSigner.get().publicKeyPem();
    }

    /** The certificate chain binding the signing key to an identity, for a certificate-shaped signer (keyless
     *  Fulcio); a bare-key deployment answers 404 and publishes only {@link #provenanceKey}. */
    @GetMapping(value = "/api/provenance/certificate", produces = "application/x-pem-file")
    @ResponseBody
    public String provenanceCertificate(HttpServletResponse response) {
        String chain = provenanceSigner.get().enabled() ? provenanceSigner.get().certificateChainPem() : null;
        if (chain == null) {
            response.setStatus(404);
            return null;
        }
        return chain;
    }

    /** A traversal-unsafe repository, tenant or path name is a {@code 400}. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** An attestation with its signing-time verification material; {@code certificateChain} and
     *  {@code transparencyLog} are {@code null} where the configured signer has none. */
    public record ProvenanceMaterialView(String envelope, String certificateChain, TransparencyLogView transparencyLog) {
    }

    /** The transparency-log entry recording an envelope, in the log's own shapes (hex hashes, base64 body and
     *  receipt), so a consumer verifies offline and audits the entry in the log by its UUID or index. */
    public record TransparencyLogView(String uuid, String logId, long logIndex, long integratedTime,
                                      String signedEntryTimestamp, String canonicalizedBody,
                                      InclusionProofView inclusionProof) {
    }

    /** The RFC 6962/9162 Merkle audit path from the entry's leaf hash to the signed root the checkpoint publishes. */
    public record InclusionProofView(long logIndex, long treeSize, String rootHash, List<String> hashes,
                                     String checkpoint) {
    }
}
