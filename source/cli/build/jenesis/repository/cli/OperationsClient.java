package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * What the deployment is doing: the node's read caches, the walks of the store, the security posture, the agreement
 * between nodes, the recent logs, the observability report and the DNS redirect records.
 *
 * <p>Reached through {@link RepositoryClient#operations()}.
 */
public final class OperationsClient extends ClientCalls {

    OperationsClient(ClientCalls calls) {
        super(calls);
    }

    /** The read caches of the node this client is pointed at. */
    public String caches() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/caches", null, null);
        require(response, 200, "read the node's caches");
        return response.body();
    }

    /** Drop every entry of every read cache on the node this client is pointed at, and every node's authorization
     *  cache; answers what went where. The listings are node-local because dropping them everywhere would be a
     *  fan-out; the grants are not, because the reason to call this is usually a credential a peer is still
     *  honouring - and that half travels as one bumped document each node reads on its own schedule. */
    public String cachesClear() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/admin/caches/clear", null, null);
        require(response, 200, "clear the node's caches");
        return response.body();
    }

    /** Ask every write the node this client is pointed at holds in memory - download counts, credential use,
     *  deferred counters - to land now rather than on its cadence; answers what each held. Node-local: every other
     *  node writes its own on its cadence. */
    public String cachesFlush() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/admin/caches/flush", null, null);
        require(response, 200, "write the node's held counts");
        return response.body();
    }

    /** The standing requests for work - a walk of the store among them. */
    public String walks() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/walks", null, null);
        require(response, 200, "read the standing requests");
        return response.body();
    }

    /** Ask for a walk of the store now; answers the standing requests. */
    public String walksRun() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/admin/walks/run", null, null);
        require(response, 200, "request a walk of the store");
        return response.body();
    }

    /** The security-posture report: every advisor's verdict on this deployment, deployment-wide or for one tenant. */
    public String posture(String tenant) throws IOException, InterruptedException {
        String path = "/api/admin/posture" + (tenant == null ? "" : "?tenant=" + enc(tenant));
        HttpResponse<String> response = send("GET", path, null, null);
        require(response, 200, "read the security posture");
        return response.body();
    }

    /** Per-node fingerprints and any divergence between the nodes of a cluster. */
    public String consistency() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/consistency", null, null);
        require(response, 200, "read the node consistency report");
        return response.body();
    }

    /** The tail of the instance's in-memory log buffer, filtered by level, text and tenant; {@code since} is the
     *  {@code cursor} a previous tail answered, so a reader resumes after what it saw. */
    public String logs(String level, String text, Long since, String tenant, Integer limit)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/logs");
        appendQuery(path, "level", level);
        appendQuery(path, "q", text);
        appendQuery(path, "since", since == null ? null : since.toString());
        appendQuery(path, "tenant", tenant);
        appendQuery(path, "limit", limit == null ? null : limit.toString());
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "read the recent logs");
        return response.body();
    }

    private static void appendQuery(StringBuilder path, String name, String value) {
        if (value != null && !value.isBlank()) {
            path.append(path.indexOf("?") < 0 ? '?' : '&').append(name).append('=').append(enc(value));
        }
    }

    /** The generated observability reference: the meters and traces this build exposes. */
    public String observability() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/observability", null, null);
        require(response, 200, "read the observability reference");
        return response.body();
    }

    /** The TXT record to publish so a coordinate redirects to {@code url}. */
    public String redirectRecord(String coordinate, String url, String formats, String scope, Long ttl)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/admin/redirect-dns/record?coordinate=").append(enc(coordinate))
                .append("&url=").append(enc(url));
        if (formats != null) {
            path.append("&formats=").append(enc(formats));
        }
        if (scope != null) {
            path.append("&scope=").append(enc(scope));
        }
        if (ttl != null) {
            path.append("&ttl=").append(ttl);
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "compose the redirect record for " + coordinate);
        return response.body();
    }

    /** Resolve a coordinate's published redirect record and report what it says. */
    public String redirectCheck(String coordinate, String expect) throws IOException, InterruptedException {
        String path = "/api/admin/redirect-dns/check?coordinate=" + enc(coordinate)
                + (expect == null ? "" : "&expect=" + enc(expect));
        HttpResponse<String> response = send("GET", path, null, null);
        require(response, 200, "check the redirect record for " + coordinate);
        return response.body();
    }
}
