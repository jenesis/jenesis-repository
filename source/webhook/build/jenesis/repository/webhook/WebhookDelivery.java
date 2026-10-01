package build.jenesis.repository.webhook;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delivers one queued webhook to one endpoint: builds the small JSON payload from the outbox entry and the pass
 * context, stamping the authoritative {@code tenant} and {@code repository} rather than trusting the producer's, signs
 * it with HMAC-SHA256 when the endpoint has a secret, and POSTs it through a {@link Sender} - the product's HTTP client
 * live, a recorder in a test. The body is small metadata, sent as one array. A non-2xx status or an I/O error is a
 * failure the drain retries with backoff.
 */
public final class WebhookDelivery {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The signature header: {@code sha256=<hex HMAC of the body under the endpoint secret>}. The three headers are a
     *  receiver's contract and carry no {@code X-} prefix (RFC 6648). */
    public static final String SIGNATURE_HEADER = "Jenesis-Webhook-Signature";

    /** The event-type header every delivery carries, so a receiver can route without parsing the body. */
    public static final String EVENT_HEADER = "Jenesis-Webhook-Event";

    /** The delivery-id header (the outbox entry id), so a receiver dedupes an at-least-once re-send. */
    public static final String ID_HEADER = "Jenesis-Webhook-Id";

    /** The wire hop, so payload, headers and signature are testable without a socket. */
    @FunctionalInterface
    public interface Sender {
        /** POST {@code body} with {@code headers} to {@code url}, returning the HTTP status; throws on an I/O error. */
        int send(URI url, byte[] body, Map<String, String> headers) throws IOException;
    }

    private final Sender sender;

    public WebhookDelivery(Sender sender) {
        this.sender = sender;
    }

    /** A delivery over the product's HTTP client with bounded connect and response timeouts, so a hung receiver fails
     *  the attempt rather than blocking the drain. */
    public static WebhookDelivery live() {
        HttpClient client = ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new WebhookDelivery((url, body, headers) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(request::header);
            try {
                return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted delivering webhook to " + url, exception);
            }
        });
    }

    /**
     * Deliver {@code entry} to {@code endpoint}, stamping {@code tenant} and {@code repository} from the pass context;
     * {@link IOException} on a non-2xx status or an I/O error, which the drain retries.
     *
     * <p>Unless {@code allowInternal} is set, the endpoint is re-screened just before the send by both halves of
     * {@link WebhookEndpoint#refusalReason(URI, boolean)}: {@code https}, and a host not resolving internally. This is
     * the one choke point every delivered byte passes, whatever a caller filtered, and the client holds the connect to
     * the address it admitted, so a DNS rebinding since is refused at the connect. A refusal is an {@link IOException}
     * like any delivery failure, so it is retried to the cap and parked on {@code GET /api/webhook} naming the
     * endpoint, the reason and the dial that permits it, rather than leaving a tenant's events silently undelivered.
     */
    public void deliver(WebhookEndpoint endpoint, WebhookOutbox.Entry entry, String tenant, String repository,
                        boolean allowInternal) throws IOException {
        String refusal = WebhookEndpoint.refusalReason(endpoint.url(), allowInternal);
        if (refusal != null) {
            throw new IOException("webhook endpoint " + endpoint.url() + " refused: " + refusal
                    + " - deliver to an https:// endpoint, or set webhook-allow-internal=true to permit a plaintext"
                    + " or internal receiver");
        }
        byte[] body = payload(entry, tenant, repository).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put(EVENT_HEADER, entry.type());
        headers.put(ID_HEADER, entry.id());
        if (endpoint.signed()) {
            headers.put(SIGNATURE_HEADER, "sha256=" + sign(endpoint.secret(), body));
        }
        int status = sender.send(endpoint.url(), body, headers);
        if (status < 200 || status >= 300) {
            throw new IOException("webhook endpoint answered " + status);
        }
    }

    /** The wire body: the entry's metadata, the authoritative tenant and repository, the detail spliced in as JSON, and
     *  the coordinate fields that are present. */
    public static String payload(WebhookOutbox.Entry entry, String tenant, String repository) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", entry.id());
        body.put("type", entry.type());
        body.put("tenant", tenant == null ? "" : tenant);
        body.put("repository", repository == null ? "" : repository);
        putIfPresent(body, "ecosystem", entry.ecosystem());
        putIfPresent(body, "coordinate", entry.coordinate());
        putIfPresent(body, "version", entry.version());
        putIfPresent(body, "path", entry.path());
        String detail = entry.detailJson();
        body.set("detail", detail == null || detail.isBlank() ? JSON.createObjectNode() : JSON.readTree(detail));
        body.put("occurredAt", entry.occurredAt() == null ? "" : entry.occurredAt());
        return body.toString();
    }

    private static void putIfPresent(ObjectNode body, String key, String value) {
        if (value != null && !value.isEmpty()) {
            body.put(key, value);
        }
    }

    /** The lower-case hex HMAC-SHA256 of {@code body} under {@code secret}. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);                 // HmacSHA256 is a required JDK algorithm
        }
    }
}
