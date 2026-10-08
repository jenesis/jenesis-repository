package build.jenesis.repository.store.azure.test;

import module java.base;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

/**
 * The slice of the Blob service the backend touches, as a stateful WireMock transformer the real
 * {@code azure-storage-blob} client is driven against: the container create, a block blob put whole or staged as
 * blocks and committed by a block list - each under {@code If-None-Match: *} or {@code If-Match} - its properties, its
 * download over an {@code x-ms-range}, the delete, and List Blobs with prefix, delimiter, marker and page size. Every
 * commit takes a fresh entity tag from one counter, so a tag is never re-issued to a later incarnation of a blob, and
 * staged blocks that no block list commits are never readable.
 */
final class RestAzure implements ResponseDefinitionTransformerV2 {

    static final String ACCOUNT = "devstoreaccount1";
    static final String KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    record Stored(byte[] content, String etag, Instant modified) {
    }

    /** The committed blobs by name, in name order - the order a listing answers in. */
    final NavigableMap<String, Stored> blobs = new ConcurrentSkipListMap<>();
    /** Staged blocks by blob name and block id, until a block list commits them. */
    private final Map<String, Map<String, byte[]>> staged = new ConcurrentHashMap<>();
    private final AtomicLong tags = new AtomicLong();

    /** The settings that point the provider at a stub on {@code port}: the emulator account, plaintext opted in. */
    static Map<String, String> settings(int port, String container) {
        return Map.of(
                "jenrepo.azure-blob.connection-string", "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT
                        + ";AccountKey=" + KEY + ";BlobEndpoint=http://localhost:" + port + "/" + ACCOUNT + ";",
                "jenrepo.azure-blob.container", container,
                "jenrepo.azure-blob.allow-insecure-endpoint", "true");
    }

    @Override
    public String getName() {
        return "rest-azure";
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public synchronized ResponseDefinition transform(ServeEvent event) {
        Request request = event.getRequest();
        URI url = URI.create("http://stub" + request.getUrl());
        String[] segments = url.getRawPath().substring(1).split("/", 3);   // account, container, blob name
        String name = segments.length < 3 ? "" : URLDecoder.decode(segments[2].replace("+", "%2B"),
                StandardCharsets.UTF_8);
        RequestMethod method = request.getMethod();
        String comp = query(request, "comp");
        if (name.isEmpty()) {
            if (RequestMethod.PUT.equals(method)) {
                return aResponse().withStatus(201).withHeader("ETag", "\"0x0\"").build();   // the container create
            }
            if (RequestMethod.GET.equals(method) && "list".equals(comp)) {
                return list(request);
            }
        } else if (RequestMethod.PUT.equals(method) && "block".equals(comp)) {
            staged.computeIfAbsent(name, _ -> new ConcurrentHashMap<>()).put(query(request, "blockid"),
                    request.getBody());
            return aResponse().withStatus(201).build();
        } else if (RequestMethod.PUT.equals(method) && "blocklist".equals(comp)) {
            return commit(request, name, blocks(request, name));
        } else if (RequestMethod.PUT.equals(method)) {
            return commit(request, name, request.getBody());
        } else if (RequestMethod.HEAD.equals(method)) {
            Stored stored = blobs.get(name);
            return stored == null ? aResponse().withStatus(404).withHeader("x-ms-error-code", "BlobNotFound").build()
                    : properties(aResponse().withStatus(200), stored)
                            .withHeader("Content-Length", Integer.toString(stored.content().length)).build();
        } else if (RequestMethod.GET.equals(method)) {
            return download(request, name);
        } else if (RequestMethod.DELETE.equals(method)) {
            return blobs.remove(name) == null ? error(404, "BlobNotFound") : aResponse().withStatus(202).build();
        }
        return error(501, "NotImplemented");
    }

    /** Commit {@code content} as {@code name}, if the request's condition holds. */
    private ResponseDefinition commit(Request request, String name, byte[] content) {
        if (content == null) {
            return error(400, "InvalidBlockList");
        }
        Stored existing = blobs.get(name);
        String ifNoneMatch = request.getHeader("If-None-Match");
        String ifMatch = request.getHeader("If-Match");
        if ("*".equals(ifNoneMatch) && existing != null) {
            return error(409, "BlobAlreadyExists");
        }
        if (ifMatch != null && (existing == null || !unquoted(ifMatch).equals(unquoted(existing.etag())))) {
            return error(412, "ConditionNotMet");
        }
        staged.remove(name);
        Stored stored = new Stored(content, "\"0x" + Long.toHexString(tags.incrementAndGet()) + "\"",
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        blobs.put(name, stored);
        return aResponse().withStatus(201).withHeader("ETag", stored.etag())
                .withHeader("Last-Modified", HTTP_DATE.format(stored.modified().atOffset(ZoneOffset.UTC))).build();
    }

    /** The block list's blocks concatenated in its order, or {@code null} where one was never staged. */
    private byte[] blocks(Request request, String name) {
        Map<String, byte[]> mine = staged.getOrDefault(name, Map.of());
        Matcher listed = Pattern.compile("<(?:Latest|Uncommitted|Committed)>([^<]*)</").matcher(request.getBodyAsString());
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        while (listed.find()) {
            byte[] block = mine.get(listed.group(1));
            if (block == null) {
                return null;
            }
            content.writeBytes(block);
        }
        return content.toByteArray();
    }

    private ResponseDefinition download(Request request, String name) {
        Stored stored = blobs.get(name);
        if (stored == null) {
            return error(404, "BlobNotFound");
        }
        String range = request.getHeader("x-ms-range") != null ? request.getHeader("x-ms-range")
                : request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring("bytes=".length()).split("-", -1);
            int from = Integer.parseInt(bounds[0]);
            int last = stored.content().length - 1;
            if (from > last) {
                return error(416, "InvalidRange");
            }
            int to = bounds[1].isEmpty() ? last : Math.min(Integer.parseInt(bounds[1]), last);
            byte[] window = Arrays.copyOfRange(stored.content(), from, to + 1);
            return properties(aResponse().withStatus(206), stored)
                    .withHeader("Content-Range", "bytes " + from + "-" + to + "/" + stored.content().length)
                    .withHeader("Content-Length", Integer.toString(window.length)).withBody(window).build();
        }
        return properties(aResponse().withStatus(200), stored)
                .withHeader("Content-Length", Integer.toString(stored.content().length))
                .withBody(stored.content()).build();
    }

    /** List Blobs: the blobs under the prefix in name order, those past a delimiter rolled into one prefix each, from
     *  the marker on, at most {@code maxresults} entries. */
    private ResponseDefinition list(Request request) {
        String prefix = Objects.requireNonNullElse(query(request, "prefix"), "");
        String delimiter = query(request, "delimiter");
        int max = query(request, "maxresults") == null ? 5000 : Integer.parseInt(query(request, "maxresults"));
        String marker = query(request, "marker");
        StringBuilder entries = new StringBuilder();
        Set<String> prefixes = new HashSet<>();
        int count = 0;
        String next = null;
        for (Map.Entry<String, Stored> entry : blobs.tailMap(prefix, true).entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(prefix)) {
                break;
            }
            int cut = delimiter == null ? -1 : name.indexOf(delimiter, prefix.length());
            String item = cut < 0 ? name : name.substring(0, cut + delimiter.length());
            if (marker != null && item.compareTo(marker) < 0) {
                continue;
            }
            if (cut >= 0 && !prefixes.add(item)) {
                continue;
            }
            if (count == max) {
                next = item;
                break;
            }
            count++;
            if (cut >= 0) {
                entries.append("<BlobPrefix><Name>").append(xml(item)).append("</Name></BlobPrefix>");
            } else {
                Stored stored = entry.getValue();
                entries.append("<Blob><Name>").append(xml(name)).append("</Name><Properties><Last-Modified>")
                        .append(HTTP_DATE.format(stored.modified().atOffset(ZoneOffset.UTC)))
                        .append("</Last-Modified><Etag>").append(stored.etag().replace("\"", ""))
                        .append("</Etag><Content-Length>").append(stored.content().length)
                        .append("</Content-Length><BlobType>BlockBlob</BlobType></Properties></Blob>");
            }
        }
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><EnumerationResults ServiceEndpoint=\"http://localhost/"
                + ACCOUNT + "\" ContainerName=\"repo\"><Blobs>" + entries + "</Blobs><NextMarker>"
                + (next == null ? "" : xml(next)) + "</NextMarker></EnumerationResults>";
        return aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody(body).build();
    }

    private static ResponseDefinitionBuilder properties(ResponseDefinitionBuilder response, Stored stored) {
        return response.withHeader("ETag", stored.etag())
                .withHeader("Last-Modified", HTTP_DATE.format(stored.modified().atOffset(ZoneOffset.UTC)))
                .withHeader("x-ms-blob-type", "BlockBlob")
                .withHeader("Accept-Ranges", "bytes")
                .withHeader("Content-Type", "application/octet-stream");
    }

    private static ResponseDefinition error(int status, String code) {
        return aResponse().withStatus(status).withHeader("x-ms-error-code", code)
                .withHeader("Content-Type", "application/xml")
                .withBody("<?xml version=\"1.0\" encoding=\"utf-8\"?><Error><Code>" + code + "</Code><Message>" + code
                        + "</Message></Error>").build();
    }

    /** An entity tag without the quotes a client may or may not send it in. */
    private static String unquoted(String etag) {
        return etag.replace("\"", "");
    }

    private static String query(Request request, String name) {
        var parameter = request.queryParameter(name);
        return parameter.isPresent() ? parameter.firstValue() : null;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
