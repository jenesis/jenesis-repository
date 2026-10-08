package build.jenesis.repository.store.s3.test;

import module java.base;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

/**
 * The slice of the S3 REST API the backend touches, as a stateful WireMock transformer the real SDK client is driven
 * against over path-style addressing: the bucket create, an object put under {@code If-None-Match: *} or
 * {@code If-Match}, the head and the get with its {@code Range}, the delete, and ListObjectsV2 with prefix, delimiter,
 * {@code max-keys}, {@code start-after} and a continuation token. Every write takes a fresh entity tag from one counter,
 * so a tag is never re-issued to a later incarnation of a key. A put body the SDK frames as {@code aws-chunked} - its
 * chunk sizes, signatures and checksum trailer - is unframed before it is stored, so what is stored is what was sent.
 */
final class RestS3 implements ResponseDefinitionTransformerV2 {

    record Stored(byte[] content, String etag, Instant modified) {
    }

    /** One bucket's objects, by key, in key order - the order a listing answers in. */
    final NavigableMap<String, Stored> objects = new ConcurrentSkipListMap<>();
    private final AtomicLong tags = new AtomicLong();

    /** The settings that point the provider at a stub on {@code port}: plaintext, opted in, static keys. */
    static Map<String, String> settings(int port, String bucket) {
        return Map.of(
                "jenrepo.s3.bucket", bucket,
                "jenrepo.s3.endpoint", "http://localhost:" + port,
                "jenrepo.s3.allow-insecure-endpoint", "true",
                "jenrepo.s3.access-key-id", "ak",
                "jenrepo.s3.secret-access-key", "sk");
    }

    @Override
    public String getName() {
        return "rest-s3";
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public synchronized ResponseDefinition transform(ServeEvent event) {
        Request request = event.getRequest();
        URI url = URI.create("http://stub" + request.getUrl());
        String path = url.getRawPath();
        int slash = path.indexOf('/', 1);
        String key = slash < 0 ? "" : decode(path.substring(slash + 1));
        RequestMethod method = request.getMethod();
        if (key.isEmpty()) {
            if (RequestMethod.PUT.equals(method)) {
                return aResponse().withStatus(200).build();          // the bucket create
            }
            if (RequestMethod.GET.equals(method)) {
                return list(request);
            }
        } else if (RequestMethod.PUT.equals(method)) {
            return put(request, key);
        } else if (RequestMethod.HEAD.equals(method)) {
            Stored stored = objects.get(key);
            return stored == null ? aResponse().withStatus(404).build()
                    : headers(aResponse().withStatus(200), stored)
                            .withHeader("Content-Length", Integer.toString(stored.content().length)).build();
        } else if (RequestMethod.GET.equals(method)) {
            return get(request, key);
        } else if (RequestMethod.DELETE.equals(method)) {
            objects.remove(key);
            return aResponse().withStatus(204).build();
        }
        return error(501, "NotImplemented", "unhandled " + method + " " + request.getUrl());
    }

    private ResponseDefinition put(Request request, String key) {
        Stored existing = objects.get(key);
        String ifNoneMatch = request.getHeader("If-None-Match");
        String ifMatch = request.getHeader("If-Match");
        if (("*".equals(ifNoneMatch) && existing != null)
                || (ifMatch != null && (existing == null || !ifMatch.equals(existing.etag())))) {
            return error(412, "PreconditionFailed", "At least one of the pre-conditions you specified did not hold");
        }
        Stored stored = new Stored(body(request), "\"" + tags.incrementAndGet() + "\"",
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        objects.put(key, stored);
        return aResponse().withStatus(200).withHeader("ETag", stored.etag()).build();
    }

    private ResponseDefinition get(Request request, String key) {
        Stored stored = objects.get(key);
        if (stored == null) {
            return error(404, "NoSuchKey", "The specified key does not exist.");
        }
        String range = request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring("bytes=".length()).split("-", -1);
            int from = Integer.parseInt(bounds[0]);
            int last = stored.content().length - 1;
            int to = bounds[1].isEmpty() ? last : Math.min(Integer.parseInt(bounds[1]), last);
            if (from > last) {
                return error(416, "InvalidRange", "The requested range is not satisfiable");
            }
            byte[] window = Arrays.copyOfRange(stored.content(), from, to + 1);
            return headers(aResponse().withStatus(206), stored)
                    .withHeader("Content-Range", "bytes " + from + "-" + to + "/" + stored.content().length)
                    .withBody(window).build();
        }
        return headers(aResponse().withStatus(200), stored).withBody(stored.content()).build();
    }

    /** ListObjectsV2: the keys under the prefix in key order, those past a delimiter rolled into one common prefix
     *  each, from just after {@code start-after} or the continuation token, at most {@code max-keys} entries. */
    private ResponseDefinition list(Request request) {
        String prefix = Objects.requireNonNullElse(query(request, "prefix"), "");
        String delimiter = query(request, "delimiter");
        int max = query(request, "max-keys") == null ? 1000 : Integer.parseInt(query(request, "max-keys"));
        String token = query(request, "continuation-token");
        String after = token != null ? new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8)
                : query(request, "start-after");
        boolean encoded = "url".equals(query(request, "encoding-type"));
        StringBuilder contents = new StringBuilder();
        Set<String> prefixes = new LinkedHashSet<>();
        int count = 0;
        String last = null;
        boolean truncated = false;
        for (Map.Entry<String, Stored> entry : objects.tailMap(prefix, true).entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                break;
            }
            if (after != null && (key.compareTo(after) <= 0 || (after.endsWith(delimiter == null ? "\u0000" : delimiter)
                    && key.startsWith(after)))) {
                continue;
            }
            int cut = delimiter == null ? -1 : key.indexOf(delimiter, prefix.length());
            String item = cut < 0 ? key : key.substring(0, cut + delimiter.length());
            if (cut >= 0 && prefixes.contains(item)) {
                continue;
            }
            if (count == max) {
                truncated = true;
                break;
            }
            count++;
            last = item;
            if (cut >= 0) {
                prefixes.add(item);
            } else {
                Stored stored = entry.getValue();
                contents.append("<Contents><Key>").append(xml(name(key, encoded))).append("</Key><LastModified>")
                        .append(DateTimeFormatter.ISO_INSTANT.format(stored.modified()))
                        .append("</LastModified><ETag>").append(xml(stored.etag())).append("</ETag><Size>")
                        .append(stored.content().length).append("</Size><StorageClass>STANDARD</StorageClass>")
                        .append("</Contents>");
            }
        }
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>bucket</Name><Prefix>")
                .append(xml(name(prefix, encoded))).append("</Prefix><KeyCount>").append(count)
                .append("</KeyCount><MaxKeys>").append(max).append("</MaxKeys>");
        if (delimiter != null) {
            body.append("<Delimiter>").append(xml(name(delimiter, encoded))).append("</Delimiter>");
        }
        if (encoded) {
            body.append("<EncodingType>url</EncodingType>");
        }
        body.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            body.append("<NextContinuationToken>").append(Base64.getEncoder().encodeToString(
                    last.getBytes(StandardCharsets.UTF_8))).append("</NextContinuationToken>");
        }
        body.append(contents);
        for (String common : prefixes) {
            body.append("<CommonPrefixes><Prefix>").append(xml(name(common, encoded))).append("</Prefix></CommonPrefixes>");
        }
        body.append("</ListBucketResult>");
        return aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody(body.toString())
                .build();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder headers(
            com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response, Stored stored) {
        return response.withHeader("ETag", stored.etag())
                .withHeader("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        stored.modified().atOffset(ZoneOffset.UTC)))
                .withHeader("Content-Type", "application/octet-stream")
                .withHeader("Accept-Ranges", "bytes");
    }

    /** The bytes a put carried: unframed when the SDK sent them {@code aws-chunked} - each chunk a hex size, an
     *  optional {@code ;chunk-signature=...}, the bytes, and a final empty chunk before the trailers. */
    private static byte[] body(Request request) {
        byte[] raw = request.getBody();
        String encoding = Objects.requireNonNullElse(request.getHeader("Content-Encoding"), "");
        String sha = Objects.requireNonNullElse(request.getHeader("x-amz-content-sha256"), "");
        if (!encoding.contains("aws-chunked") && !sha.startsWith("STREAMING-")) {
            return raw;
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        int at = 0;
        while (at < raw.length) {
            int end = indexOf(raw, at);
            String header = new String(raw, at, end - at, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(header.split(";", 2)[0].trim(), 16);
            at = end + 2;
            if (size == 0) {
                break;
            }
            content.write(raw, at, size);
            at += size + 2;
        }
        return content.toByteArray();
    }

    private static int indexOf(byte[] bytes, int from) {
        for (int i = from; i + 1 < bytes.length; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                return i;
            }
        }
        return bytes.length;
    }

    private static ResponseDefinition error(int status, String code, String message) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/xml")
                .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>"
                        + xml(message) + "</Message></Error>").build();
    }

    private static String name(String value, boolean encoded) {
        return encoded ? URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20") : value;
    }

    private static String query(Request request, String name) {
        var parameter = request.queryParameter(name);
        return parameter.isPresent() ? parameter.firstValue() : null;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
