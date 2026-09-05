package build.jenesis.repository.store.gcs.test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import build.jenesis.repository.store.gcs.GcsArtifactStoreProvider;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

/**
 * The slice of the Cloud Storage JSON API the backend touches, as a stateful WireMock transformer the real API
 * client is driven against: a direct media upload under {@code ifGenerationMatch}, the object document and the media
 * download with its {@code x-goog-generation} header and a {@code Range}, the listing with prefix, delimiter,
 * start offset, page size and page token, the delete, and the bucket insert. Generations come from one counter and
 * are never re-issued. Three arrangements make the protocol's failure paths reachable: a bucket named {@code gone}
 * does not exist, an object whose name ends in {@code no-generation-header} is served without its generation, one
 * whose name ends in {@code faulted} answers 403, and the first upload under a {@code throttled/} name is refused
 * with a 429 so the client's backoff is what makes it land (the name is matched under any scope prefix).
 */
final class JsonGcs implements ResponseDefinitionTransformerV2 {

    record Stored(byte[] content, long generation, Instant updated) {
    }

    final Map<String, Stored> objects = new ConcurrentHashMap<>();
    final AtomicLong generations = new AtomicLong();
    final AtomicInteger uploads = new AtomicInteger();
    final AtomicInteger throttled = new AtomicInteger();

    /** The settings that point the provider at a stub on {@code port}: plaintext, opted in, and no credential. */
    static Map<String, String> settings(int port, String bucket) {
        return Map.of(
                "jenreg.gcs.bucket", bucket,
                "jenreg.gcs.endpoint", "http://localhost:" + port,
                "jenreg.gcs.allow-insecure-endpoint", "true",
                "jenreg.gcs.credentials", GcsArtifactStoreProvider.ANONYMOUS);
    }

    @Override
    public String getName() {
        return "json-gcs";
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public synchronized ResponseDefinition transform(ServeEvent event) {
        Request request = event.getRequest();
        URI url = URI.create("http://stub" + request.getUrl());
        String[] segments = Arrays.stream(url.getRawPath().split("/")).skip(1).map(JsonGcs::decode).toArray(String[]::new);
        if (segments.length > 0 && "download".equals(segments[0])) {
            // The client fetches media under its own path prefix; the object routes below are the same from there on.
            segments = Arrays.copyOfRange(segments, 1, segments.length);
        }
        RequestMethod method = request.getMethod();
        if (segments.length >= 6 && "upload".equals(segments[0]) && "b".equals(segments[3]) && "o".equals(segments[5])
                && RequestMethod.POST.equals(method)) {
            return upload(request, segments[4]);
        }
        if (segments.length == 2 && "storage".equals(segments[0]) && RequestMethod.POST.equals(method)) {
            return json(200, Map.of("kind", "storage#bucket", "name", "created"));
        }
        if (segments.length >= 4 && "storage".equals(segments[0]) && "b".equals(segments[2])) {
            String bucket = segments[3];
            if ("gone".equals(bucket)) {
                return error(404, "notFound", "The specified bucket does not exist.");
            }
            if (segments.length == 5 && "o".equals(segments[4]) && RequestMethod.GET.equals(method)) {
                return list(request);
            }
            if (segments.length == 6 && "o".equals(segments[4])) {
                String name = segments[5];
                if (RequestMethod.GET.equals(method)) {
                    return "media".equals(query(request, "alt")) ? media(request, name) : document(name);
                }
                if (RequestMethod.DELETE.equals(method)) {
                    return delete(request, name);
                }
            }
        }
        return aResponse().withStatus(501).withBody("unhandled " + method + " " + request.getUrl()).build();
    }

    private ResponseDefinition upload(Request request, String bucket) {
        if ("gone".equals(bucket)) {
            return error(404, "notFound", "The specified bucket does not exist.");
        }
        String name = query(request, "name");
        uploads.incrementAndGet();
        if (name.contains("throttled/") && throttled.getAndIncrement() == 0) {
            return error(429, "rateLimitExceeded", "The object exceeded the rate limit for object mutation operations.");
        }
        String precondition = query(request, "ifGenerationMatch");
        Stored existing = objects.get(name);
        if (precondition != null && (precondition.equals("0")
                ? existing != null
                : existing == null || !precondition.equals(Long.toString(existing.generation())))) {
            return error(412, "conditionNotMet", "At least one of the pre-conditions you specified did not hold.");
        }
        Stored stored = new Stored(request.getBody(), generations.incrementAndGet(), Instant.now());
        objects.put(name, stored);
        return json(200, document(name, stored, true));
    }

    private ResponseDefinition document(String name) {
        Stored stored = objects.get(name);
        if (name.endsWith("faulted")) {
            return error(403, "forbidden", "the stub was told to refuse this object");
        }
        if (stored == null) {
            return error(404, "notFound", "No such object: " + name);
        }
        return json(200, document(name, stored, !name.endsWith("no-generation-header")));
    }

    private ResponseDefinition media(Request request, String name) {
        Stored stored = objects.get(name);
        if (stored == null) {
            return error(404, "notFound", "No such object: " + name);
        }
        var response = aResponse().withHeader("Content-Type", "application/octet-stream");
        if (!name.endsWith("no-generation-header")) {
            response.withHeader("x-goog-generation", Long.toString(stored.generation()));
        }
        String range = request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring("bytes=".length()).split("-");
            int from = Integer.parseInt(bounds[0]);
            int to = Math.min(Integer.parseInt(bounds[1]), stored.content().length - 1);
            return response.withStatus(206)
                    .withHeader("Content-Range", "bytes " + from + "-" + to + "/" + stored.content().length)
                    .withBody(Arrays.copyOfRange(stored.content(), from, to + 1)).build();
        }
        return response.withStatus(200).withBody(stored.content()).build();
    }

    private ResponseDefinition delete(Request request, String name) {
        Stored existing = objects.get(name);
        if (existing == null) {
            return error(404, "notFound", "No such object: " + name);
        }
        String precondition = query(request, "ifGenerationMatch");
        if (precondition != null && !precondition.equals(Long.toString(existing.generation()))) {
            return error(412, "conditionNotMet", "At least one of the pre-conditions you specified did not hold.");
        }
        objects.remove(name);
        return aResponse().withStatus(204).build();
    }

    /** The listing: every entry - an object, or under a delimiter the grouped prefix of a deeper one - in name order
     *  from the start offset, cut into pages of {@code maxResults} whose token is the position of the next. */
    private ResponseDefinition list(Request request) {
        String prefix = query(request, "prefix") == null ? "" : query(request, "prefix");
        String delimiter = query(request, "delimiter");
        String startOffset = query(request, "startOffset");
        int maxResults = query(request, "maxResults") == null ? 1000 : Integer.parseInt(query(request, "maxResults"));
        int from = query(request, "pageToken") == null ? 0 : Integer.parseInt(query(request, "pageToken"));
        TreeMap<String, Stored> entries = new TreeMap<>();
        TreeSet<String> prefixes = new TreeSet<>();
        for (Map.Entry<String, Stored> entry : new TreeMap<>(objects).entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(prefix) || (startOffset != null && name.compareTo(startOffset) < 0)) {
                continue;
            }
            int slash = delimiter == null ? -1 : name.indexOf(delimiter, prefix.length());
            if (slash >= 0) {
                prefixes.add(name.substring(0, slash + delimiter.length()));
            } else {
                entries.put(name, entry.getValue());
            }
        }
        TreeSet<String> ordered = new TreeSet<>(entries.keySet());
        ordered.addAll(prefixes);
        List<String> page = new ArrayList<>(ordered).subList(Math.min(from, ordered.size()), Math.min(from + maxResults, ordered.size()));
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> grouped = new ArrayList<>();
        for (String name : page) {
            if (prefixes.contains(name)) {
                grouped.add(name);
            } else {
                items.add(document(name, entries.get(name), true));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "storage#objects");
        if (!items.isEmpty()) {
            body.put("items", items);
        }
        if (!grouped.isEmpty()) {
            body.put("prefixes", grouped);
        }
        if (from + maxResults < ordered.size()) {
            body.put("nextPageToken", Integer.toString(from + maxResults));
        }
        return json(200, body);
    }

    private static Map<String, Object> document(String name, Stored stored, boolean withGeneration) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", "storage#object");
        document.put("name", name);
        document.put("bucket", "repo");
        document.put("size", Long.toString(stored.content().length));
        document.put("updated", stored.updated().toString());
        if (withGeneration) {
            document.put("generation", Long.toString(stored.generation()));
        }
        return document;
    }

    private static String query(Request request, String name) {
        var parameter = request.queryParameter(name);
        return parameter == null || !parameter.isPresent() ? null : parameter.firstValue();
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static ResponseDefinition json(int status, Object body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json; charset=UTF-8")
                .withBody(Json.write(body)).build();
    }

    private static ResponseDefinition error(int status, String reason, String message) {
        return json(status, Map.of("error", Map.of("code", status, "message", message,
                "errors", List.of(Map.of("domain", "global", "reason", reason, "message", message)))));
    }
}
