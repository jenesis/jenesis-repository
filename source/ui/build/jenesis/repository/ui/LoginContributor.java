package build.jenesis.repository.ui;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * The seam a login mechanism plugs into, in every console. The console's chain owns the authorization rules, the entry
 * point and logout, and applies every {@code LoginContributor} bean to the shared {@link HttpSecurity}; several may
 * coexist.
 *
 * <p>No contributor means nobody can sign in, not that nobody has to: the chain still requires authentication and
 * disables form and basic login, so protected screens redirect to a "not configured" sign-in page. Authentication is
 * relaxed only by an operator's explicit choice, never by a mechanism being absent or failing.
 */
@FunctionalInterface
public interface LoginContributor {

    void configure(HttpSecurity http) throws Exception;
}
