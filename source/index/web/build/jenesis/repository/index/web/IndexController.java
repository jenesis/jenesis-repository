package build.jenesis.repository.index.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.index.PublishedIndex;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The published-index surface: {@code GET /api/index} returns the chain descriptor as JSON (revalidated, since the
 * chain grows), and {@code GET /api/index/chunks/{id}} streams one immutable, content-addressed chunk with an
 * {@code ETag} equal to its SHA-256 id and {@code Cache-Control: public, max-age=…, immutable}, honouring
 * {@code If-None-Match} with a {@code 304}, so every chunk fetch is cached forever. The tenant is the routing's for the
 * request, so two tenants never read each other's index; a traversal-unsafe repository or chunk id is a {@code 400}.
 * The security chain enforces rights before the request reaches the controller.
 */
@RestController
public class IndexController {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final Repositories repositories;
    private final RepositoryRouting routing;

    public IndexController(Repositories repositories, RepositoryRouting routing) {
        this.repositories = repositories;
        this.routing = routing;
    }

    @GetMapping("/api/index")
    public void descriptor(@RequestParam("repo") String repo,
                           HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return;
        }
        byte[] json = new PublishedIndex(repositories.store(tenant, repo)).descriptorJson();
        response.setStatus(200);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-cache");
        response.setContentLength(json.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(json);
        }
    }

    @GetMapping("/api/index/chunks/{id}")
    public void chunk(@PathVariable("id") String id,
                      @RequestParam("repo") String repo,
                      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = RepositoryRequests.access(routing, repo, request, response);
        if (tenant == null) {
            return;
        }
        if (!chunkId(id)) {
            response.setStatus(400);
            return;
        }
        PublishedIndex index = new PublishedIndex(repositories.store(tenant, repo));
        // One probe answers existence and length (-1 when absent), so a full fetch pays size + read.
        long size = index.chunkSize(id);
        if (size < 0) {
            response.setStatus(404);
            return;
        }
        String etag = "\"" + id + "\"";
        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", IMMUTABLE);
        if (etag.equals(ifNoneMatch)) {
            response.setStatus(304);
            return;
        }
        response.setStatus(200);
        response.setContentType("application/zstd");
        response.setContentLengthLong(size);
        try (OutputStream out = response.getOutputStream()) {
            index.streamChunk(id, out);
        }
    }

    /** A chunk id is a 64-character lowercase SHA-256 hex string, so a path parameter can never escape the subtree. */
    private static boolean chunkId(String id) {
        if (id.length() != 64) {
            return false;
        }
        for (int index = 0; index < id.length(); index++) {
            char c = id.charAt(index);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }
}
