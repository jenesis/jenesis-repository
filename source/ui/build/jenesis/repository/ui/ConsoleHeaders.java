package build.jenesis.repository.ui;

import module java.base;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * The response headers every console chain sends beside the ones Spring Security sends by default.
 *
 * <p>A {@code Content-Security-Policy} that admits only the console's own assets: its scripts, its stylesheets, its
 * images and the {@code data:} images its stylesheet draws its icons with. Every screen renders through templates, its
 * script is a served asset and its styling a class in the shared stylesheet, so nothing on a page is inline - and an
 * injected {@code <script>}, an inline handler or a style attribute a stored value smuggled into a page is refused by
 * the browser rather than run. Forms post only to the console, and no other site may frame it.
 */
public final class ConsoleHeaders {

    /** The policy, in the header's own syntax. */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    private ConsoleHeaders() {
    }

    /** Send the console's headers on every response of {@code http}'s chain. */
    public static HttpSecurity apply(HttpSecurity http) throws Exception {
        return http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                CONTENT_SECURITY_POLICY)));
    }
}
