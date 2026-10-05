package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A published version's transitive closure as the closure pass resolved it from what the repository and the
 * repositories its fallbacks name hold: its state, every component with the repository holding it, and every
 * dependency that did not resolve with why. One point read of the version's document, through the same
 * {@link ClosureSection#answer} the console's version page renders, so the two cannot disagree; nothing is resolved on
 * the request. A cached copy answers {@code CACHED} - it has no closure of its own and is screened by its own
 * coordinate - and a release the pass has not reached {@code PENDING}, never an empty closure.
 *
 * <p>Under {@code /api/repository/}, so it takes the repository's read right; an invalid name is a {@code 400} and a
 * version the repository does not hold a {@code 404}. The answer is bounded by what one document holds - the pass stops
 * a closure at its component bound and says so in {@code truncated}.
 */
@RestController
public class ClosureController {

    private final Repositories repositories;
    private final RepositoryRouting routing;

    public ClosureController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    /** One version's closure: {@code resolved} is when the pass resolved it, {@code kind} which kind of source
     *  produced it - {@code BILL}, {@code RESOLVER}, {@code SCANNER} or {@code DECLARATIONS} - and {@code source} that
     *  source's name, each {@code null} with no closure; a component's {@code repository} is empty where the version's
     *  own repository holds it. */
    public record ClosureView(String repository, String ecosystem, String coordinate, String version, String state,
                              String resolved, String kind, String source, boolean truncated,
                              List<ClosureSection.Component> components, List<ClosureSection.Cut> cuts) {
    }

    @GetMapping("/api/repository/closure")
    @ResponseBody
    public ClosureView closure(@RequestParam("repo") String repo,
                               @RequestParam("ecosystem") String ecosystem,
                               @RequestParam("coordinate") String coordinate,
                               @RequestParam("version") String version,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        String tenant = routing.tenant(request);
        if (!Repositories.valid(repo) || !Repositories.valid(tenant) || ecosystem.isBlank() || coordinate.isBlank()
                || version.isBlank()) {
            response.setStatus(400);
            return null;
        }
        Optional<ClosureSection.Answer> answer;
        try {
            Optional<MetadataDocument> document = MetadataProvider.installed()
                    .over(repositories.store(tenant, repo)).read(ecosystem, coordinate, version);
            answer = document.flatMap(ClosureSection::answer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (answer.isEmpty()) {
            response.setStatus(404);
            return null;
        }
        ClosureSection.Closure closure = answer.get().closure();
        return new ClosureView(repo, ecosystem, coordinate, version, answer.get().state().name(),
                closure == null ? null : closure.resolved().toString(),
                closure == null ? null : closure.kind().name(), closure == null ? null : closure.source(),
                closure != null && closure.truncated(),
                closure == null ? List.of() : closure.components(), closure == null ? List.of() : closure.cuts());
    }
}
