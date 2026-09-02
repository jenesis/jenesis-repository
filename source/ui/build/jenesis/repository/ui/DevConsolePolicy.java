package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * What an edition supplies to the one development sign-in chain: the URL space that chain guards, and the
 * authorization matrix it applies. Nothing else.
 *
 * <p>There used to be two dev chains, one per console, and they had drifted in exactly the way two copies do. The
 * free one pointed form login at {@code /login} - a page that lists mechanisms and carries no credential form - so
 * dev sign-in there simply did not work; the admin one fell back to Spring Security's <em>generated</em> login
 * page, a second sign-in page nobody designed and no console styling reaches; and the loopback guard that exists
 * because a dev profile enables an in-memory backdoor was on one of them and not the other, though both enable one.
 *
 * <p>Every one of those differences was in the mechanism, and the only real differences between the two consoles
 * are the two things named here - which is the test for whether a pair should be merged: they differ in policy, so
 * the mechanism is shared and the policy is the seam.
 */
public interface DevConsolePolicy {

    /** The paths the dev chain guards - this console's own URL space. */
    List<String> space();

    /** The authorization matrix, which must be the same one this console's production chain applies: a dev chain
     *  that has quietly relaxed a rule proves a topology nothing ships. */
    void rules(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth);
}
