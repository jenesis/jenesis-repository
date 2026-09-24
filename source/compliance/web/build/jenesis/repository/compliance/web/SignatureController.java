package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.inventory.SignatureSection;
import build.jenesis.repository.inventory.SignatureSummaries;
import build.jenesis.repository.server.kernel.Repositories;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment made of the publisher's signature on a published artifact: the outcome, who signed it, how
 * much the signature is worth, where the material sat, where the signer's trust came from - and that in the
 * operator's words - and what else the material stated: a keyless signer's issuer and subject, its
 * transparency-log entry. Everything the console's panel shows, since a capability the console renders and the
 * API does not is one a headless tool cannot reach.
 *
 * <p>It lives here, beside the other compliance reads, rather than with the console's own APIs, because the console
 * is not in every composition this product ships - the format and lifecycle harnesses boot the repository without it
 * deliberately - and a capability reachable only through the console is one a headless operator tool, an audit export
 * and the CLI cannot reach at all. The console panel and this answer come from the same
 * {@link SignatureSummaries} read, so neither can drift from the other about what a path's signature is.
 *
 * <p>It re-verifies nothing: the answer is the durable summary recorded when the artifact was published (&sect;10,
 * reads render only stored state). Re-running the cryptography here would cost more the more the page is looked at,
 * and would disagree with the verdict the gate actually reached the moment a key changed.
 *
 * <p>A path naming no coordinate, or one for which nothing was recorded, answers {@code 204}. A version published
 * before signatures were checked here is a different thing from one checked and found wanting, and inventing an
 * outcome for the first would make the two indistinguishable.
 */
@RestController
public class SignatureController {

    private final Repositories repositories;

    public SignatureController(Repositories repositories) {
        this.repositories = repositories;
    }

    /** The signature as a surface reports it: the section's summary, whole. {@code source} is the trust source's
     *  name and {@code admittedBy} the same in the operator's words; {@code details} is what else the material
     *  stated, keyed as the gate's {@code Signature} names them. Each {@code null} or empty on a record from
     *  before it was kept. */
    public record SignatureView(String outcome, String signer, String grade, String location, String source,
                                String admittedBy, Map<String, String> details) {
    }

    @GetMapping("/api/signature")
    @ResponseBody
    public SignatureView signature(@RequestParam("repo") String repo,
                                   @RequestParam(value = "path", defaultValue = "") String path,
                                   @RequestHeader(value = Repositories.KEY, required = false) String key,
                                   HttpServletResponse response) {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        Optional<SignatureSection.Summary> summary;
        try {
            summary = SignatureSummaries.of(repositories.store(tenant, repo),
                    repositories.formatPath(tenant, repo, path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (summary.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return null;
        }
        return new SignatureView(summary.get().outcome(), summary.get().signer(), summary.get().grade(),
                summary.get().location(), summary.get().source(), summary.get().admittedBy(),
                summary.get().details());
    }
}
