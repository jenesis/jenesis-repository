package build.jenesis.repository.server;

import module java.base;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a state-changing request a browser sent from another site, with a {@code 403} before anything is decided.
 *
 * <p>The chain disables CSRF because its clients are build tools presenting a key, not browser sessions - but a key
 * may arrive as the password of HTTP Basic, and a browser that holds Basic credentials for this host attaches them
 * to any request it makes here, including a form another site submits. What a build tool never sends is what a
 * browser always does: {@code Sec-Fetch-Site}, and {@code Origin} on a cross-origin write. So where
 * {@code Sec-Fetch-Site} is present it decides: {@code same-origin} and {@code none} (a request the user made
 * directly) pass, and anything else - {@code cross-site}, and {@code same-site}, a sibling host under the same
 * registrable domain - is refused unless its {@code Origin} is one the operator trusts. Where it is absent, as from an
 * older browser, an {@code Origin} naming another host than the one the request addressed is refused on the same
 * terms, so a sibling origin cannot pass by leaving the header off. The host the request addressed is its
 * {@code Host}, or the {@code X-Forwarded-Host} a proxy in front set - a header a cross-site form cannot add. A
 * request carrying neither header is a client's, and passes, which is why nothing a build tool does changes.
 *
 * <p><b>Why a sibling is refused.</b> A subdomain is not this deployment: under a shared parent domain it may be
 * another team's application, a user-content host or a preview deployment, and any page served there can submit a
 * form here that the browser marks only {@code same-site}. An operator whose own tools write from a sibling - a
 * console on another host of the same domain - names those origins in {@value #KEY}, read live.
 *
 * <p>Safe methods pass whatever their headers say: a read changes nothing, and a browser reading a public artifact
 * cross-site - a link on another page - is the ordinary case.
 */
public class CrossSiteWriteFilter extends OncePerRequestFilter {

    /** The setting naming the origins trusted to write from another site. */
    public static final String KEY = "trusted-sites";

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Supplier<Set<String>> trusted;

    /** A filter trusting the origins {@code trusted} names, read for each request so a live setting is honoured. */
    public CrossSiteWriteFilter(Supplier<Set<String>> trusted) {
        this.trusted = Objects.requireNonNull(trusted, "trusted");
    }

    /** The trusted origins as {@code lookup} reads them now: {@value #KEY}, a comma-separated list of origins
     *  ({@code https://console.example.com}), each compared as the scheme, host and port a browser sends in
     *  {@code Origin}; unset trusts none. */
    public static Supplier<Set<String>> live(UnaryOperator<String> lookup) {
        return () -> origins(lookup.apply("jenreg." + KEY));
    }

    /** {@code value} as the set of origins it lists, each in the form {@link #origin} compares. */
    private static Set<String> origins(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> origins = new HashSet<>();
        for (String listed : value.split(",")) {
            String origin = origin(listed.trim());
            if (origin != null) {
                origins.add(origin);
            }
        }
        return Set.copyOf(origins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE.contains(request.getMethod()) && crossSite(request, trusted.get())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "A write from another site is refused");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether a browser marked the request as sent from a site other than this one and the operator's trusted
     *  {@code sites}. */
    private static boolean crossSite(HttpServletRequest request, Set<String> sites) {
        String origin = request.getHeader("Origin");
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            String marked = site.trim().toLowerCase(Locale.ROOT);
            return !(marked.equals("same-origin") || marked.equals("none") || trusted(origin, sites));
        }
        if (origin == null) {
            return false;
        }
        String authority = authority(origin.trim());
        return !(authority != null && (authority.equalsIgnoreCase(host(request.getHeader("Host")))
                || authority.equalsIgnoreCase(host(request.getHeader("X-Forwarded-Host"))))
                || trusted(origin, sites));
    }

    /** Whether {@code origin} is one of the trusted {@code sites}. */
    private static boolean trusted(String origin, Set<String> sites) {
        if (origin == null || sites.isEmpty()) {
            return false;
        }
        String normalised = origin(origin.trim());
        return normalised != null && sites.contains(normalised);
    }

    /** An origin as scheme, host and port, lower-cased and with the scheme's default port spelled out, or
     *  {@code null} for an opaque one ({@code null}) or one that does not parse. */
    private static String origin(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? 443 : scheme.equals("http") ? 80
                    : -1;
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (URISyntaxException unparsable) {
            return null;
        }
    }

    /** The host and port an {@code Origin} names, or {@code null} for an opaque one ({@code null}) or one that does
     *  not parse, which are refused as not this host. */
    private static String authority(String origin) {
        try {
            URI uri = new URI(origin);
            return uri.getRawAuthority();
        } catch (URISyntaxException unparsable) {
            return null;
        }
    }

    /** The first host a header names, as a proxy chain lists several. */
    private static String host(String header) {
        if (header == null) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
    }
}
