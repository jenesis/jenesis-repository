package build.jenesis.repository.webhook;

import module java.base;
import module java.net.http;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delivers one queued webhook to one endpoint: it builds the small JSON payload from the outbox entry and the pass
 * context (stamping the authoritative {@code tenant} and {@code repository}, which are not trusted from the
 * producer), signs it with HMAC-SHA256 when the endpoint carries a shared secret, and POSTs it. The wire hop is a
 * pluggable {@link Sender} - the live one is {@code java.net.http}, a test one records without a socket - so the
 * payload, headers and signature are asserted without a live receiver. The body is small metadata, so it is sent as
 * one byte array; no artifact ever flows through a webhook. A non-2xx status or an I/O error is a failure the drain
 * retries with backoff.
 */
public final class WebhookDelivery {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The signature header a signed delivery carries: {@code sha256=<hex HMAC of the body under the endpoint secret>}.
     *  The three headers are a receiver's contract, and they are bare: the {@code X-} prefix was deprecated
     *  (RFC 6648) because a header that graduates from experiment to protocol keeps its name forever, and the old
     *  spelling was cut over rather than sent beside the new one. */
    public static final String SIGNATURE_HEADER = "Jenesis-Webhook-Signature";

    /** The event-type header every delivery carries, so a receiver can route without parsing the body. */
    public static final String EVENT_HEADER = "Jenesis-Webhook-Event";

    /** The delivery-id header (the outbox entry id), so a receiver dedupes an at-least-once re-send. */
    public static final String ID_HEADER = "Jenesis-Webhook-Id";

    /** The wire hop, so the payload/headers/signature are testable without a socket; the live one is HTTP. */
    @FunctionalInterface
    public interface Sender {
        /** POST {@code body} with {@code headers} to {@code url}, returning the HTTP status; throws on an I/O error. */
        int send(URI url, byte[] body, Map<String, String> headers) throws IOException;
    }

    private final Sender sender;

    public WebhookDelivery(Sender sender) {
        this.sender = sender;
    }

    /** A delivery over a real {@code java.net.http} client with a bounded connect/response timeout, so a hung
     *  receiver fails the attempt (to be retried) rather than blocking the drain. */
    public static WebhookDelivery live() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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

    /** Deliver {@code entry} to {@code endpoint}, stamping {@code tenant}/{@code repository} from the pass context.
     *  Throws {@link IOException} on a non-2xx status or an I/O error, which the drain turns into a retry.
     *
     *  <p>Unless {@code allowInternal} is set the endpoint is re-screened immediately before the send, through the
     *  shared {@link WebhookEndpoint#refusalReason(URI, boolean)} outbound-target screen - <b>both</b> halves of it:
     *  the transport must be {@code https} and the host must not resolve internally. This is the single choke point
     *  every delivered byte passes, which is why the screen lives here and not only in the drain's filter: no caller
     *  can assemble a delivery that leaves in cleartext or reaches loopback/metadata/a private host, whatever it
     *  filtered beforehand. The host half must be re-run last regardless, because the underlying
     *  {@code java.net.http} client re-resolves the name at connect time, so a DNS answer that has flipped to an
     *  internal address since (a rebinding / TOCTOU SSRF against a per-tenant dial) would otherwise slip through;
     *  full connection-pinning to the vetted literal is impractical over {@code java.net.http} without dropping SNI,
     *  so re-running the screen last narrows the exposure to the client's own resolve-to-connect gap.
     *
     *  <p>A refusal is an {@link IOException} like any other delivery failure on purpose: the drain records it as the
     *  entry's last error and retries it to the attempt cap, so it lands on the {@code GET /api/webhook} status
     *  surface as a parked entry naming the endpoint, the reason and the dial that permits it. A silent filter would
     *  leave a tenant with an {@code http://} endpoint watching events never arrive with nothing anywhere saying why -
     *  the degradation this refusal replaces. */
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

    /** The wire body: the entry's metadata plus the authoritative tenant/repository, the detail object spliced in
     *  as already-valid JSON, and only the coordinate fields that are present. */
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
