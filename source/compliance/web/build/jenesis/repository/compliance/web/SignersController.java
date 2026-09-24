package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.signatures.SignerIndex;
import build.jenesis.repository.server.kernel.Repositories;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who signed a repository's accepted versions, and everything one signer signed: the read side of the continuity
 * the gate learns as it admits signed versions, so a signer-changed finding's two identities can each be opened,
 * and a key an operator is about to revoke shows what it vouched for before the pin goes. Both answers are pages by
 * cursor over the {@link SignerIndex} the signatures module writes beside its continuity record - the same read
 * the console screen renders, so the two surfaces cannot drift - and nothing here walks the repository.
 *
 * <p>Read-only, gated {@code manage:read} by the security chain; a traversal-unsafe repository or tenant name is a
 * {@code 400}, and so is a signer that is not one (the wire form is {@code <scheme>:<value>}).
 */
@RestController
public class SignersController {

    private final Repositories repositories;

    public SignersController(Repositories repositories) {
        this.repositories = repositories;
    }

    /** A signer the index knows: the wire identity, the hash it is filed under, and - for a keyless identity - the
     *  issuer and the subject it joins, so a client shows them apart without taking the identity to pieces. */
    public record SignerRow(String signer, String id, String issuer, String subject) {

        static SignerRow of(SignerIdentity identity, String id) {
            Optional<SignerIdentity.Sigstore> keyless = identity.sigstore();
            return new SignerRow(identity.wire(), id, keyless.map(SignerIdentity.Sigstore::issuer).orElse(null),
                    keyless.map(SignerIdentity.Sigstore::subject).orElse(null));
        }
    }

    /** A page of signers and the cursor the next page starts after. */
    public record SignersView(List<SignerRow> signers, String next) {
    }

    /** One coordinate a signer signed: how many of its versions, since when, and the last one. */
    public record SignedRow(String ecosystem, String coordinate, int versions, Instant since, String last) {
    }

    /** One signer's coordinates, a page at a time. */
    public record SignedView(String signer, List<SignedRow> coordinates, String next) {
    }

    @GetMapping("/api/signers")
    @ResponseBody
    public SignersView signers(@RequestParam("repo") String repo,
                               @RequestParam(value = "after", required = false) String after,
                               @RequestParam(value = "limit", defaultValue = "200") int limit,
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        SignerIndex.Page<SignerIndex.Signer> page = SignerIndex.signers(repositories.store(tenant, repo),
                blankToNull(after), Math.clamp(limit, 1, SignerIndex.MAX_PAGE));
        return new SignersView(page.rows().stream()
                .map(signer -> SignerRow.of(signer.signer(), signer.id())).toList(), page.next());
    }

    @GetMapping("/api/signers/signed")
    @ResponseBody
    public SignedView signedBy(@RequestParam("repo") String repo,
                               @RequestParam("signer") String signer,
                               @RequestParam(value = "after", required = false) String after,
                               @RequestParam(value = "limit", defaultValue = "200") int limit,
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return null;
        }
        Optional<SignerIdentity> identity = SignerIdentity.ofWire(signer);
        if (identity.isEmpty()) {
            response.setStatus(400);
            return null;
        }
        SignerIndex.Page<SignerIndex.Signed> page = SignerIndex.signedBy(repositories.store(tenant, repo),
                identity.get(), blankToNull(after), Math.clamp(limit, 1, SignerIndex.MAX_PAGE));
        return new SignedView(identity.get().wire(), page.rows().stream()
                .map(row -> new SignedRow(row.ecosystem(), row.coordinate(), row.versions(), row.since(), row.last()))
                .toList(), page.next());
    }

    private static String blankToNull(String after) {
        return after == null || after.isBlank() ? null : after;
    }

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
}
