package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * The build cache's projects, and what the builds that use it report: the ingested build scans and the test-
 * selection service.
 *
 * <p>Reached through {@link RepositoryClient#buildCache()}.
 */
public final class BuildCacheClient extends ClientCalls {

    BuildCacheClient(ClientCalls calls) {
        super(calls);
    }

    /**
     * The build-cache projects on this deployment's volume.
     *
     * <p>These reach the same {@code CacheService} the console's project screens do, which is what makes them one
     * capability rather than two implementations - the surface-parity rule measures exactly that, and reported the
     * console's project and eviction routes as sharing no implementation with any API route until the API twin
     * existed for them to share one with.
     */
    public String cacheProjects() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/cache/projects", null, null);
        require(response, 200, "list the build-cache projects");
        return response.body();
    }

    public String cacheProject(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/cache/projects/" + enc(name), null, null);
        require(response, 200, "read the build-cache project");
        return response.body();
    }

    public String createCacheProject(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/cache/projects?name=" + enc(name), HttpRequest.BodyPublishers.noBody(), null);
        require(response, 201, "create the build-cache project");
        return response.body();
    }

    /** The well-known cache values; an omitted one is cleared rather than left at its previous value. */
    public String saveCacheConfig(String name, String size, String lru, String ttl)
            throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder();
        if (size != null) {
            query.append(query.isEmpty() ? "?" : "&").append("size=").append(enc(size));
        }
        if (lru != null) {
            query.append(query.isEmpty() ? "?" : "&").append("lru=").append(enc(lru));
        }
        if (ttl != null) {
            query.append(query.isEmpty() ? "?" : "&").append("ttl=").append(enc(ttl));
        }
        HttpResponse<String> response = send("POST",
                "/api/cache/projects/" + enc(name) + "/cache" + query,
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "save the build-cache settings");
        return response.body();
    }

    /**
     * Start one of the project's passes - {@code size}, {@code ttl} or {@code clear} - and answer what the server
     * said. The pass sweeps off the request path, so this returns as soon as it has started, and {@code started}
     * being false means one was already running rather than that anything failed.
     */
    public String evictCache(String name, String pass) throws IOException, InterruptedException {
        // Each pass's path written whole rather than assembled from the pass's name, so the route it reaches is a
        // constant this client carries - which is what anything reading the compiled client can see it send.
        String route = switch (pass) {
            case "size" -> "/evict/size";
            case "ttl" -> "/evict/ttl";
            case "clear" -> "/evict/clear";
            default -> throw new IllegalArgumentException("Unknown pass '" + pass + "': size, ttl or clear");
        };
        HttpResponse<String> response = send("POST", "/api/cache/projects/" + enc(name) + route,
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "start the build-cache eviction pass");
        return response.body();
    }

    public String recountCache(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST",
                "/api/cache/projects/" + enc(name) + "/recount",
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "start the build-cache recount");
        return response.body();
    }

    /** Start deleting a build-cache project; the answer says whether this call started it. */
    public String deleteCacheProject(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("DELETE", "/api/cache/projects/" + enc(name), null, null);
        require(response, 200, "start deleting the build-cache project");
        return response.body();
    }

    /** The tenant's ingested build scans: a build reports to the tenant, not to a repository, so there is no
     *  repository to narrow them to. */
    public String scans() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/scans", null, null);
        require(response, 200, "read the scan runs");
        return response.body();
    }

    /** One scan run. */
    public String scan(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/scans/" + enc(id), null, null);
        require(response, 200, "read scan run " + id);
        return response.body();
    }

    /** The findings of one scan run. */
    public String scanReport(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/scans/" + enc(id) + "/report", null, null);
        require(response, 200, "read the report of scan run " + id);
        return response.body();
    }

    /** Ingest a build scan - the per-run record of what a build ran and what the cache saved it. */
    public String ingestScan(String document) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/scans",
                HttpRequest.BodyPublishers.ofString(document), "application/json");
        require(response, 201, "ingest the build scan");
        return response.body();
    }

    /** The aggregate view across scan runs. */
    public String scanAnalytics(boolean asReport) throws IOException, InterruptedException {
        // Both paths written out whole rather than assembled from a common stem: a path built by concatenation
        // exists nowhere in the class file, so nothing that reads the compiled artifact - the surface-parity
        // census included - can see that this endpoint is reached at all.
        HttpResponse<String> response = send("GET",
                asReport ? "/api/scans/analytics/report" : "/api/scans/analytics", null, null);
        require(response, 200, "read the scan analytics");
        return response.body();
    }

    /** Ingest a test run into the tenant's history. */
    public String ingestTestRun(String document) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/tests",
                HttpRequest.BodyPublishers.ofString(document), "application/json");
        require(response, 201, "ingest the test run");
        return response.body();
    }

    /** One ingested test run. */
    public String testRun(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/tests/" + enc(id), null, null);
        require(response, 200, "read test run " + id);
        return response.body();
    }

    /** The tests seen to flake across the tenant's history. */
    public String flakyTests() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/tests/flaky", null, null);
        require(response, 200, "read the flaky tests");
        return response.body();
    }

    /** The tests worth running for a change touching {@code changed} (comma-separated paths), or the ones failing
     *  on the tip when it is {@code null}. */
    public String selectTests(String changed) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/tests/select" + (changed == null ? "" : "?changed=" + enc(changed)), null, null);
        require(response, 200, "select the tests for the change");
        return response.body();
    }
}
