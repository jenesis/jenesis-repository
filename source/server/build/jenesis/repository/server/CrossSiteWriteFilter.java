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
 * {@code Sec-Fetch-Site} is present it decides, and {@code cross-site} is refused; where it is absent, as from an
 * older browser, an {@code Origin} naming another host than the one the request addressed is refused. The host the
 * request addressed is its {@code Host}, or the {@code X-Forwarded-Host} a proxy in front set - a header a cross-site
 * form cannot add. A request carrying neither header is a client's, and passes, which is why nothing a build tool
 * does changes.
 *
 * <p>Safe methods pass whatever their headers say: a read changes nothing, and a browser reading a public artifact
 * cross-site - a link on another page - is the ordinary case.
 */
public class CrossSiteWriteFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE.contains(request.getMethod()) && crossSite(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "A write from another site is refused");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether a browser marked the request as sent from another site. */
    static boolean crossSite(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return site.trim().equalsIgnoreCase("cross-site");
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return false;
        }
        String authority = authority(origin.trim());
        return authority == null || !(authority.equalsIgnoreCase(host(request.getHeader("Host")))
                || authority.equalsIgnoreCase(host(request.getHeader("X-Forwarded-Host"))));
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
