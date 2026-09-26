package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.events.EventType;
import build.jenesis.repository.settings.PrivateHostGuard;

/**
 * One configured webhook endpoint: the absolute {@code url} a POST is delivered to, the set of {@link EventType}s it
 * subscribes to (empty means all), and an optional shared {@code secret} the delivery signs the body with
 * (HMAC-SHA256). The secret is <em>not</em> carried on the {@code webhook-endpoints} spec line - it lives in the
 * separate {@code webhook-secrets} {@link build.jenesis.repository.settings.Setting.Kind#SECRET SECRET} setting, keyed
 * by URL, so it is redacted on read-back and kept out of exports; {@link #parse} builds an endpoint with a
 * {@code null} secret and the delivery pass attaches it from that map through {@link #withSecret}. An endpoint's
 * {@link #key()} is a short, stable digest of its URL - the token the outbox records in an entry's delivered-set, so a
 * URL that carries a comma or changes length never corrupts the bookkeeping, and a retry re-sends only to the
 * endpoints that never took an event.
 */
public record WebhookEndpoint(URI url, Set<EventType> events, String secret) {

    public WebhookEndpoint {
        events = Set.copyOf(events);
    }

    /** A copy of this endpoint carrying {@code secret} as its HMAC signing key - the delivery pass calls this to
     *  attach the value looked up from the {@code webhook-secrets} map by URL. A {@code null}/blank secret leaves the
     *  endpoint {@link #signed() unsigned}. */
    public WebhookEndpoint withSecret(String secret) {
        return new WebhookEndpoint(url, events, secret);
    }

    /** Whether this endpoint subscribes to {@code type} - an empty subscription set means every type. */
    public boolean subscribes(EventType type) {
        return events.isEmpty() || events.contains(type);
    }

    /**
     * Whether the delivery signs its body for this endpoint - true exactly when a shared secret is configured.
     *
     * <p><strong>An unsigned endpoint is delivered, not refused, and that is deliberate.</strong> Signing is an
     * absence here, not an unhonourable selection: nothing was asked for and denied, so &sect;9's refusal rule does not
     * reach it. More decisively, the product cannot <em>positively judge</em> an unsigned endpoint unsafe - a great
     * many real receivers (chat and CI incoming-webhook URLs above all) authenticate by possession of a secret-bearing
     * {@code https} URL, and this side cannot tell such a URL from a public one. A refusal must rest on something the
     * refusing side can see; refusing every secretless endpoint would be refusing on a guess and would break the
     * commonest receivers there are. So the condition is <em>reported</em> instead - the {@code jenreg.webhook.unsigned}
     * gauge {@link WebhookDeliveryTask} publishes each pass, and the {@code webhook-endpoints} / {@code webhook-secrets}
     * setting text at the point an operator configures one. Contrast the endpoint's <em>transport</em>, which the URL
     * states outright and which is therefore refused - {@link #refusalReason(URI, boolean)}.
     */
    public boolean signed() {
        return secret != null && !secret.isBlank();
    }

    /**
     * Whether this endpoint's host is an internal / non-public target a webhook must not reach by default - a
     * loopback, any-local, link-local (which covers the {@code 169.254.169.254} cloud-metadata address),
     * private/site-local, IPv6 unique-local, carrier-grade-NAT ({@code 100.64.0.0/10}) or multicast address, or a
     * host that cannot be resolved at all (so an unverifiable target is refused rather than risked). Endpoints are a
     * per-tenant dial, so without this a tenant could point a signed callback at the deployment's own metadata service
     * or an internal host (SSRF); a deployment that intends internal callbacks sets {@code webhook-allow-internal} to
     * bypass the block. Resolution is by name, so a DNS answer of an internal address is caught too.
     */
    public boolean internal() {
        return internal(url);
    }

    /**
     * Whether the host of {@code url} resolves (right now) to an internal / non-public target - the same screen {@link
     * #internal()} runs over a configured endpoint, exposed static so the live delivery can re-run it immediately
     * before it connects, which the product's HTTP client then holds: a host this admits is connected to only at a
     * public address, so a name that rebinds between the check and the connect is refused. Delegates to the shared {@link
     * PrivateHostGuard#internal(URI)} predicate - the one home the import and forwarding legs screen against too, so
     * the blocked ranges cannot drift; a host that cannot be resolved is treated as internal.
     */
    public static boolean internal(URI url) {
        return PrivateHostGuard.internal(url);
    }

    /**
     * The reason {@code url} must not be delivered to with the current settings, or {@code null} when it may be: the
     * shared {@link PrivateHostGuard#refusalReason(URI, boolean)} outbound-target screen, which is both halves of the
     * question - the transport must be {@code https} and the host must not resolve internally - and which the
     * forwarding leg screens its own operator-supplied targets against. Reusing the one home is the point: this module
     * previously ran only the host half, so a per-tenant {@code http://} endpoint was accepted and delivered to
     * unchanged while its forwarding peer refused the identical URL.
     *
     * <p>{@code allowInternal} is the {@code webhook-allow-internal} opt-out and bypasses both halves. It is
     * deployment-global on purpose: endpoints are a per-tenant dial, so a tenant-scoped opt-out would let a tenant
     * admin alone put that tenant's event metadata on the wire in cleartext, and the deployment's network posture is
     * the operator's to own. {@link WebhookDelivery#deliver} re-runs this immediately before it connects, so it is the
     * single choke point every delivered byte passes rather than a filter a caller could route around.
     */
    public static String refusalReason(URI url, boolean allowInternal) {
        return PrivateHostGuard.refusalReason(url, allowInternal);
    }

    /** A short, stable, comma-free key derived from the URL - the token the outbox records as delivered. */
    public String key() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                 // SHA-256 is a required JDK algorithm
        }
    }

    /**
     * Parse an endpoints spec - one endpoint per line or semicolon, each {@code <url> [events]} - into the endpoints
     * for a delivery pass. {@code events} is a comma-list of type tokens ({@code publish,quarantine}) or {@code *} for
     * all; a malformed URL, an unknown-scheme URL or a blank line is skipped rather than failing the pass, so one bad
     * line never stops the rest delivering. Only {@code http}/{@code https} URLs are accepted. The endpoint's HMAC
     * secret is not on this line - each endpoint parses with a {@code null} secret and the delivery pass attaches it
     * from the {@code webhook-secrets} map ({@link #secrets}) through {@link #withSecret}.
     *
     * <p>Parsing an {@code http} URL is not admitting one: a plaintext endpoint is <em>refused at delivery</em>
     * ({@link #refusalReason(URI, boolean)}) unless {@code webhook-allow-internal} permits it, and the refusal is recorded
     * against the outbox entry so an operator reads it on the webhook status surface. It parses so the refusal can
     * name the endpoint it refused; dropping the line here instead would make a configured endpoint vanish silently,
     * which is the shape this change exists to remove.
     */
    public static List<WebhookEndpoint> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        List<WebhookEndpoint> endpoints = new ArrayList<>();
        for (String raw : spec.split("[\\n;]")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            URI url;
            try {
                url = URI.create(parts[0]);
            } catch (IllegalArgumentException _) {
                continue;                                       // a malformed URL is skipped, not fatal
            }
            String scheme = url.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || url.getHost() == null) {
                continue;                                       // only real HTTP(S) callbacks
            }
            Set<EventType> events = parts.length > 1 ? types(parts[1]) : Set.of();
            endpoints.add(new WebhookEndpoint(url, events, null));   // the secret is attached from webhook-secrets
        }
        return List.copyOf(endpoints);
    }

    /**
     * Parse the {@code webhook-secrets} spec - one {@code <https-url>=<secret>} per line - into a URL - secret map the
     * delivery pass attaches by URL. The key is the endpoint URL exactly as it appears in {@code webhook-endpoints}
     * (what {@link #url()}{@code .toString()} yields); everything after the first {@code =} is the secret verbatim
     * (a secret may itself contain {@code =}). A blank line, a line with no {@code =}, or an empty URL or secret is
     * skipped rather than failing the pass. This value is a {@link build.jenesis.repository.settings.Setting.Kind#SECRET
     * SECRET} setting, so it is redacted on read-back and kept out of exports; it never rides the endpoints line.
     */
    public static Map<String, String> secrets(String spec) {
        if (spec == null || spec.isBlank()) {
            return Map.of();
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        for (String raw : spec.split("\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            int split = line.indexOf('=');
            if (split <= 0) {
                continue;                                           // no URL key before an '=' - skip, do not fail
            }
            String url = line.substring(0, split).strip();
            String secret = line.substring(split + 1).strip();
            if (url.isEmpty() || secret.isEmpty()) {
                continue;
            }
            secrets.put(url, secret);
        }
        return Map.copyOf(secrets);
    }

    private static Set<EventType> types(String token) {
        if (token.equals("*")) {
            return Set.of();
        }
        Set<EventType> types = EnumSet.noneOf(EventType.class);
        for (String name : token.split(",")) {
            String trimmed = name.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (EventType type : EventType.values()) {
                if (type.wire().equalsIgnoreCase(trimmed) || type.name().equalsIgnoreCase(trimmed)) {
                    types.add(type);
                }
            }
        }
        return types;
    }
}
