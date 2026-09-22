package build.jenesis.repository.blobs;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.server.spi.Authorization;

/**
 * The absolute base a generated index builds its download URLs on.
 *
 * <p>Five formats - npm, Composer, Cargo, CocoaPods and NuGet - rewrite the URLs inside a packument, a p2
 * document, a sparse-index entry, a podspec and a service index, so this decides where a client is told to fetch
 * from. Each carried a byte-identical private copy of it. One copy is not a style preference here: a defect in it
 * has to be fixed once rather than five times, and a sixth format inheriting a flaw from a copy-paste is exactly
 * how this spread.
 *
 * <p>The formats that emit <em>relative</em> URLs instead - PyPI's rewritten hrefs, conda, Debian, RPM, gems, Go,
 * Conan, HuggingFace - need no base at all and do not call this. That is the better answer where a format allows
 * it, which makes the base a divergence to contain rather than a rule to spread.
 *
 * <h2>Where the base comes from, in order</h2>
 *
 * <ol>
 *   <li><b>The operator's {@code public-url}</b>, when set: the address clients reach the deployment at, stated
 *   outright. It is the answer for a deployment behind a front door that rewrites paths or sends no forwarded
 *   headers at all - nothing in the request can reconstruct such an address, so the operator names it.</li>
 *   <li><b>A trusted proxy's forwarded headers</b>: {@code X-Forwarded-Proto} and {@code X-Forwarded-Host} are
 *   believed only when the request's peer is within {@code trusted-proxies} - the rule {@code X-Forwarded-For} has
 *   always been held to for the source-IP allowlist. From anyone else they are ignored: a client that sets its own
 *   could otherwise have the registry publish an index - one that may then be cached and served to others - pointing
 *   wherever it likes.</li>
 *   <li><b>The request itself</b>: the scheme the server terminated and the {@code Host} the client asked for,
 *   which is the right answer for a deployment clients reach directly.</li>
 * </ol>
 */
public final class RequestBase {

    /** The setting naming the address clients reach the deployment at ({@code https://repo.example.com}). */
    public static final String PUBLIC_URL = "public-url";

    /** The setting listing the reverse proxies whose forwarded headers are believed, as comma-separated CIDRs. */
    public static final String TRUSTED_PROXIES = "trusted-proxies";

    private RequestBase() {
    }

    /** The {@code scheme://host} an index's absolute URLs hang off, resolved as the class documentation orders. */
    public static String of(FormatExchange exchange) {
        String pinned = exchange.setting(PUBLIC_URL);
        if (pinned != null && !pinned.isBlank()) {
            String url = pinned.trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        String scheme = exchange.scheme();
        String host = exchange.requestHeader("Host");
        if (Authorization.trustedProxy(exchange.remoteAddress(), trustedProxies(exchange))) {
            String forwardedProto = first(exchange.requestHeader("X-Forwarded-Proto"));
            String forwardedHost = first(exchange.requestHeader("X-Forwarded-Host"));
            scheme = forwardedProto == null ? scheme : forwardedProto;
            host = forwardedHost == null ? host : forwardedHost;
        }
        return scheme + "://" + host;
    }

    private static List<String> trustedProxies(FormatExchange exchange) {
        String configured = exchange.setting(TRUSTED_PROXIES);
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Stream.of(configured.split(",")).map(String::trim).filter(cidr -> !cidr.isEmpty()).toList();
    }

    /** The first value of a header a chain of proxies may have appended to ({@code https, http}). */
    private static String first(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
    }
}
