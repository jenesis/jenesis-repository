package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.ProvenanceSigner;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provenance surface - the signed attestation of an artifact's identity and the verification material bound to it -
 * peeled out of the {@code RepositoryController} monolith into the compliance {@code web} adapter and
 * contributed through the {@code ServerModuleProvider} seam. An attestation is an in-toto Statement over the artifact's
 * SHA-256 (taken from the content-addressed {@code blobs/<sha256>} key, never re-read or re-hashed off the store),
 * signed by the configured {@link ProvenanceSigner}; a transparency-logged signer publishes it to the log before it
 * is served. The signed attestation is cached content-addressed by the artifact's SHA-256 (a
 * {@link ProvenanceAttestationCache} over the same store), so an artifact is signed - and transparency-log-appended -
 * exactly once and every later read serves that one attestation, rather than re-signing and re-appending on every
 * {@code GET} (an append-only log grown without bound, and a signature paid per read); generating it is a
 * security-relevant event, so the first generation is recorded on the {@link AuditTrail}. With no signer
 * configured the endpoints answer {@code 404}, exactly as they did in the monolith. Every route is under {@code /api/}
 * and is gated {@code manage:read} by the security chain before the request is reached; the tenant comes from the key,
 * so two tenants never read each other's attestations, and a traversal-unsafe repository, tenant or path name is a
 * {@code 400}. Provenance is a compliance surface, so it rides the compliance module's discovery rather than a module
 * of its own.
 */
@RestController
public class ProvenanceController {

    private final Repositories repositories;
    private final ProvenanceSigner provenanceSigner;
    private final AuditTrail audit;

    public ProvenanceController(Repositories repositories, ProvenanceSigner provenanceSigner, AuditTrail audit) {
        this.repositories = repositories;
        this.provenanceSigner = provenanceSigner;
        this.audit = audit;
    }

    @GetMapping(value = "/api/provenance", produces = "application/json", params = "!material")
    @ResponseBody
    public String provenance(@RequestParam("repo") String repo,
                             @RequestParam("path") String path,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletResponse response) throws IOException {
        ProvenanceSigner.Attestation attestation = attested(repo, path, key, response);
        return attestation == null ? null : attestation.envelope();
    }

    /**
     * With {@code material}, the attestation together with the verification material bound to it at signing time:
     * the certificate chain that certified the signing key and the transparency-log entry recording the envelope,
     * each {@code null} where the signer has none (a bare-key deployment's material is {@link #provenanceKey}).
     * The material has to travel with the envelope rather than be re-read from {@link #provenanceCertificate} - a
     * keyless signer's certificate rotates every few minutes, so the currently published chain may no longer be
     * the one that signed. This is the keyless verify path's input: the chain validates to the pinned Fulcio root,
     * the log entry's inclusion proof recomputes the log's signed root, and the envelope verifies against the
     * certified leaf key.
     */
    @GetMapping(value = "/api/provenance", produces = "application/json", params = "material")
    @ResponseBody
    public ProvenanceMaterialView provenanceMaterial(@RequestParam("repo") String repo,
                                                     @RequestParam("path") String path,
                                                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                                                     HttpServletResponse response) throws IOException {
        ProvenanceSigner.Attestation attestation = attested(repo, path, key, response);
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

    /** The signed attestation of an artifact's identity: an in-toto Statement over the artifact's SHA-256 (taken from
     *  the content-addressed {@code blobs/<sha256>} key the path resolves to, never re-read or re-hashed off the
     *  store), signed by the configured signer; a transparency-logged signer publishes it to the log before it is
     *  served. Signed and appended once and then cached content-addressed - a later read serves the cached attestation
     *  rather than re-signing and re-appending - and the first generation is audited. {@code null} after setting the
     *  response status when access is refused, no signer is configured or the artifact is absent. */
    private ProvenanceSigner.Attestation attested(String repo, String path, String key,
                                                  HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        if (!provenanceSigner.enabled()) {
            response.setStatus(404);
            return null;
        }
        RepositoryRequests.rejectTraversal(path);
        ArtifactStore store = repositories.store(tenant, repo);
        // The path is the one a client names within the repository; the publication records it as the format lays it
        // out. The attestation names the artifact by the former, which is how anybody holding it refers to it.
        Optional<String> located = new Publication(store).located(repositories.formatPath(tenant, repo, path));
        if (located.isEmpty()) {
            response.setStatus(404);
            return null;
        }
        // The blob is content-addressed: {@code located} is {@code blobs/<sha256>}, so the artifact's SHA-256 is its
        // own storage key. Take it from the key rather than streaming the whole blob back through a digest just to
        // recompute a hash the store already holds.
        String sha256 = located.get().substring(located.get().indexOf('/') + 1);
        // Serve the attestation minted on first generation rather than re-signing on every read: a re-sign per GET
        // grows the append-only transparency log without bound and pays a signature (and a Rekor round trip) for a
        // read. The cache is keyed by the artifact's SHA-256, so a path re-pointed to different bytes misses and a
        // fresh attestation for the new bytes is generated - a changed artifact never serves its predecessor's.
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
            attestation = provenanceSigner.attest(statement);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign the provenance attestation", e);
        }
        // Cache the signed attestation before serving it and audit the generation: this endpoint is manage:read, but
        // this branch mints and transparency-log-appends a fresh attestation - a security-relevant event the trail
        // must record - and every later read serves the cached one without re-signing.
        cache.write(sha256, path, attestation);
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), "provenance.generate", repo + path);
        return attestation;
    }

    @GetMapping(value = "/api/provenance/key", produces = "application/x-pem-file")
    @ResponseBody
    public String provenanceKey(HttpServletResponse response) {
        if (!provenanceSigner.enabled()) {
            response.setStatus(404);
            return null;
        }
        return provenanceSigner.publicKeyPem();
    }

    /** The certificate chain binding the signing key to an identity, for a certificate-shaped signer (keyless
     *  Fulcio); a bare-key deployment answers 404 and publishes only {@link #provenanceKey}. */
    @GetMapping(value = "/api/provenance/certificate", produces = "application/x-pem-file")
    @ResponseBody
    public String provenanceCertificate(HttpServletResponse response) {
        String chain = provenanceSigner.enabled() ? provenanceSigner.certificateChainPem() : null;
        if (chain == null) {
            response.setStatus(404);
            return null;
        }
        return chain;
    }

    /** A traversal-unsafe repository, tenant or path name is a {@code 400}; the guard came with the endpoints from the
     *  monolith, where it was a shared controller-level exception handler. */
    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /**
     * Validates the named repository and resolves the request's tenant from the {@code Jenesis-Repository-Key} header,
     * answering {@code 400} for a traversal-unsafe repository or tenant name and {@code null} so the caller returns at
     * once. Rights are enforced by Spring Security before the request reaches the controller, so this makes no
     * authorization decision - the same guard the monolith carried, unchanged by the move.
     */
    private String access(String repo, String key, HttpServletResponse response) {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        return tenant;
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
