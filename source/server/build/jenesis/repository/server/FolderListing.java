package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * A folder of a repository whose format is a folder tree, answered as the plain index a browsing client reads: a
 * link per servable child, a folder's ending in {@code /}, and a link to the next page when the folder holds more.
 *
 * <p>Maven publishes {@code maven-metadata.xml} so that no client needs to list a folder, but some do - Coursier and
 * sbt where the metadata is missing, Gradle's fallback, a mirroring script - and people browse one. The answer is a
 * page and never the folder: one bounded, screened page of the store's own child listing, resumed by the
 * {@value #AFTER} parameter, so a folder of a million versions costs what a folder of ten does. A client that does not
 * follow the next link sees the first {@value #PAGE} names, which is the bound stated rather than a silent cut.
 *
 * <p>What is listed is what a {@code GET} would serve: the page is screened by {@link ScreenedNames} under the serve
 * path's own policy, so a withheld version or a file whose bytes are gone is absent, and a folder with nothing
 * servable in it answers {@code 404} like one that does not exist. A folder costs one listing call per child besides
 * the screen's reads of a file's pointer, because a store's listing does not reliably say which children are folders;
 * that is why a deployment turns this on rather than having it.
 */
final class FolderListing {

    /** The setting that answers a folder with a listing, per repository, inheriting the tenant's and the deployment's
     *  value. */
    static final String SETTING = "folder-listing";

    /** Off: a folder answers {@code 404}, as it does with no listing at all. */
    static final String DEFAULT = "false";

    /** The query parameter a next page resumes after: the last name the previous page listed. */
    static final String AFTER = "after";

    /** How many names one page lists. */
    static final int PAGE = 1_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FolderListing() {
    }

    /** Whether {@code exchange} asks for a folder and the repository answers one with a listing. */
    static boolean asked(FormatExchange exchange) {
        return exchange.path().endsWith("/")
                && (exchange.method().equals("GET") || exchange.method().equals("HEAD"))
                && Boolean.parseBoolean(Objects.requireNonNullElse(exchange.setting(SETTING), DEFAULT));
    }

    /** Answer the folder {@code exchange} names from {@code store}: the page, a {@code 404} for a folder with nothing
     *  servable, a {@code 400} for a cursor that is not a name. */
    static void answer(FormatExchange exchange, ArtifactStore store) throws IOException {
        String after = exchange.queryParameter(AFTER);
        if (after != null && (after.isEmpty() || after.contains("/"))) {
            exchange.respond(400);
            return;
        }
        String scope = ServableNames.PUBLISHED + exchange.path().substring(0, exchange.path().length() - 1);
        List<Map<String, Object>> entries = new ArrayList<>();
        Traversal.Result result;
        try {
            result = ScreenedNames.paths(new ServableNames(store, new Publication(store)),
                            ServableNames.Policy.HIDE_WITHHELD_AND_GONE)
                    .containers(child -> container(store, child))
                    .scanning(BoundedChildren.bounded().entries(PAGE + 1).page(PAGE + 1))
                    .take(PAGE)
                    .scan(store, scope, after == null ? null : Traversal.key(scope, after),
                            (name, container) -> entries.add(Map.of("name", name, "folder", container)));
        } catch (TraversalException unaddressable) {
            exchange.respond(400);
            return;
        }
        if (entries.isEmpty() && after == null) {
            exchange.respond(404);
            return;
        }
        String next = result.cursor().map(key -> key.substring(key.lastIndexOf('/') + 1)).orElse(null);
        boolean json = Objects.requireNonNullElse(exchange.requestHeader("Accept"), "").contains("application/json");
        byte[] body = json ? document(entries, next) : page(exchange.external(exchange.path()), entries, next);
        exchange.setResponseHeader("Content-Type", json ? "application/json" : "text/html;charset=UTF-8");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Integer.toString(body.length));
            exchange.respond(200);
        } else {
            exchange.respond(200, body);
        }
    }

    /** Whether a child is a folder: one child answers it, and the probe stops there. */
    private static boolean container(ArtifactStore store, String child) {
        boolean[] any = {false};
        store.page(child, "", 1, _ -> any[0] = true);
        return any[0];
    }

    private static byte[] document(List<Map<String, Object>> entries, String next) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("entries", entries);
        document.put("next", next);
        return JSON.writeValueAsBytes(document);
    }

    /** The index a browsing client parses: the plainest HTML that every such parser reads, one anchor per line, its
     *  links relative so they hold under any routing; {@code folder} is the URL the client reached it at. */
    private static byte[] page(String folder, List<Map<String, Object>> entries, String next) {
        String title = HtmlUtils.htmlEscape(folder);
        StringBuilder page = new StringBuilder("<!DOCTYPE html>\n<html><head><title>").append(title)
                .append("</title></head><body>\n<h1>").append(title).append("</h1>\n<pre>\n")
                .append("<a href=\"../\">../</a>\n");
        for (Map<String, Object> entry : entries) {
            String name = (String) entry.get("name");
            String shown = HtmlUtils.htmlEscape(name) + (Boolean.TRUE.equals(entry.get("folder")) ? "/" : "");
            String href = UriUtils.encodePathSegment(name, StandardCharsets.UTF_8)
                    + (Boolean.TRUE.equals(entry.get("folder")) ? "/" : "");
            page.append("<a href=\"").append(href).append("\">").append(shown).append("</a>\n");
        }
        page.append("</pre>\n");
        if (next != null) {
            page.append("<p><a rel=\"next\" href=\"?").append(AFTER).append('=')
                    .append(UriUtils.encodeQueryParam(next, StandardCharsets.UTF_8)).append("\">More</a></p>\n");
        }
        return page.append("</body></html>\n").toString().getBytes(StandardCharsets.UTF_8);
    }
}
