package build.jenesis.repository.webhook;

import module java.base;
import build.jenesis.repository.events.EventType;
import build.jenesis.repository.settings.PrivateHostGuard;

/**
 * One configured webhook endpoint: the absolute {@code url} a POST goes to, the {@link EventType}s it subscribes to
 * (empty means all), and an optional HMAC-SHA256 {@code secret}. The secret is not on the {@code webhook-endpoints}
 * line but in the {@code webhook-secrets} {@link build.jenesis.repository.settings.Setting.Kind#SECRET SECRET} setting,
 * keyed by URL, so it is redacted on read-back and kept out of exports; {@link #parse} leaves it {@code null} and the
 * pass attaches it through {@link #withSecret}. {@link #key()} is a short digest of the URL, the token the outbox
 * records as delivered.
 */
public record WebhookEndpoint(URI url, Set<EventType> events, String secret) {

    public WebhookEndpoint {
        events = Set.copyOf(events);
    }

    /** A copy carrying {@code secret} as its signing key; {@code null} or blank leaves it
     *  {@link #signed() unsigned}. */
    public WebhookEndpoint withSecret(String secret) {
        return new WebhookEndpoint(url, events, secret);
    }

    /** Whether this endpoint subscribes to {@code type} - an empty subscription set means every type. */
    public boolean subscribes(EventType type) {
        return events.isEmpty() || events.contains(type);
    }

    /**
     * Whether the delivery signs its body for this endpoint: exactly when a secret is configured.
     *
     * <p><strong>An unsigned endpoint is delivered, not refused.</strong> Many real receivers (chat and CI
     * incoming-webhook URLs) authenticate by possession of a secret-bearing {@code https} URL, which this side cannot
     * tell from a public one, and a refusal must rest on something the refusing side can see. So it is reported: the
     * {@code jenrepo.webhook.unsigned} gauge and the setting text where an operator configures one. The transport,
     * which the URL states, is refused ({@link #refusalReason(URI, boolean)}).
     */
    public boolean signed() {
        return secret != null && !secret.isBlank();
    }

    /** Whether this endpoint's host is internal or non-public - loopback, any-local, link-local (the
     *  {@code 169.254.169.254} metadata address included), private, IPv6 unique-local, carrier-grade NAT, multicast -
     *  or cannot be resolved, so a per-tenant dial cannot point a callback at the deployment's own services.
     *  {@code webhook-allow-internal} bypasses it. Resolution is by name, so a DNS answer of an internal address is
     *  caught. */
    public boolean internal() {
        return internal(url);
    }

    /** Whether the host of {@code url} resolves now to an internal target, by the shared
     *  {@link PrivateHostGuard#internal(URI)}, re-run before the live delivery connects; the client then connects only
     *  to the public address admitted, so a rebinding is refused. An unresolvable host is internal. */
    public static boolean internal(URI url) {
        return PrivateHostGuard.internal(url);
    }

    /**
     * Why {@code url} must not be delivered to with these settings, or {@code null}: the shared
     * {@link PrivateHostGuard#refusalReason(URI, boolean)}, both halves - {@code https}, and no internal host - as the
     * forwarding leg screens its targets, so the two cannot disagree about one URL.
     *
     * <p>{@code allowInternal}, the {@code webhook-allow-internal} opt-out, bypasses both and is deployment-global,
     * since a per-tenant opt-out would let a tenant admin alone send event metadata in cleartext.
     * {@link WebhookDelivery#deliver} re-runs this before connecting, the one choke point every delivery passes.
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

    /** Parse an endpoints spec - one {@code <url> [events]} per line or semicolon, {@code events} a comma-list of type
     *  tokens or {@code *} - into a pass's endpoints. A malformed URL, another scheme or a blank line is skipped, so
     *  one bad line never stops the rest; only {@code http} and {@code https} parse, each with a {@code null} secret
     *  attached later from {@link #secrets}. A parsed {@code http} URL is not admitted: it is refused at delivery and
     *  the refusal recorded, naming the endpoint, rather than the line vanishing here. */
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

    /** Parse the {@code webhook-secrets} spec, one {@code <https-url>=<secret>} per line, into a URL-to-secret map, the
     *  key the URL exactly as {@code webhook-endpoints} has it and the secret everything after the first {@code =}. A
     *  blank line, one without {@code =}, or an empty URL or secret is skipped. */
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
