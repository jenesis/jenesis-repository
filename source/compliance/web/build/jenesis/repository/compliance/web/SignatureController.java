package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.inventory.SignatureSection;
import build.jenesis.repository.inventory.SignatureSummaries;
import build.jenesis.repository.server.kernel.Repositories;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment made of the publisher's signature on an artifact - outcome, signer, grade, where the material
 * sat, the trust source, and a keyless signer's issuer, subject and log entry - from the same
 * {@link SignatureSummaries} read the console panel renders, recorded at publish and never re-verified. A path with no
 * coordinate or no record answers {@code 204}, distinct from a signature found wanting.
 */
@RestController
public class SignatureController {

    private final Repositories repositories;
    private final RepositoryRouting routing;

    public SignatureController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    /** The signature summary, whole: {@code source} the trust source's name, {@code admittedBy} it in the operator's
     *  words, {@code details} what else the material stated; each {@code null} or empty where not recorded. */
    public record SignatureView(String outcome, String signer, String grade, String location, String source,
                                String admittedBy, Map<String, String> details) {
    }

    @GetMapping("/api/signature")
    @ResponseBody
    public SignatureView signature(@RequestParam("repo") String repo,
                                   @RequestParam(value = "path", defaultValue = "") String path,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = routing.tenant(request);
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
