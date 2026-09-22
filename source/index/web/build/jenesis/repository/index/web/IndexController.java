package build.jenesis.repository.index.web;

import module java.base;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.index.PublishedIndex;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The published-index surface, contributed through the {@code ServerModuleProvider} seam: {@code GET /api/index}
 * returns the chain descriptor as JSON (revalidated, since the chain grows), and {@code GET /api/index/chunks/{id}}
 * streams one immutable, content-addressed chunk with an {@code ETag} equal to its SHA-256 id and
 * {@code Cache-Control: public, max-age=…, immutable}, honouring {@code If-None-Match} with a {@code 304} - so a
 * consumer's sync is fetch-descriptor, diff, fetch-only-unseen-chunks, and every chunk fetch is cached forever. The
 * tenant comes from the key, so two tenants never read each other's index; a traversal-unsafe repository or chunk id
 * is a {@code 400}. Rights are enforced by the security chain before the request reaches the controller.
 */
@RestController
public class IndexController {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final Repositories repositories;

    public IndexController(Repositories repositories) {
        this.repositories = repositories;
    }

    @GetMapping("/api/index")
    public void descriptor(@RequestParam("repo") String repo,
                           @RequestHeader(value = Repositories.KEY, required = false) String key,
                           HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
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
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                      HttpServletResponse response) throws IOException {
        String tenant = access(repo, key, response);
        if (tenant == null) {
            return;
        }
        if (!chunkId(id)) {
            response.setStatus(400);
            return;
        }
        PublishedIndex index = new PublishedIndex(repositories.store(tenant, repo));
        // One metadata probe answers both existence and the content length: chunkSize is -1 for an absent chunk, so a
        // full fetch pays size + read, not the redundant exists + size + read (the 304 re-sync path keeps its one probe).
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
