package build.jenesis.repository.ui;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * The seam a login mechanism plugs into. The console's security chain owns the authorization rules, the entry point
 * and logout, and applies every {@code LoginContributor} bean to the shared {@link HttpSecurity}, so a mechanism
 * supplies its own login rather than being wired into the chain, and a deployment runs only the mechanisms it puts
 * on its module path. Several may coexist. This is the only seam for it: a mechanism written against it plugs into
 * any console this product ships.
 *
 * <p><b>No contributor means nobody can sign in - it does not mean nobody has to.</b> With none present the chain
 * still requires authentication for every request it matches and additionally disables form and basic login, so
 * there is no credential path left at all: protected screens redirect to {@code /login}, which renders a
 * "not configured" notice, and no request reaches anything behind it. That is deliberate and is the safe direction.
 * Authentication is only ever relaxed by an explicit choice - a profile or a setting an operator sets on purpose -
 * and never as a side effect of a mechanism being absent, misconfigured, or failing to start.
 */
@FunctionalInterface
public interface LoginContributor {

    void configure(HttpSecurity http) throws Exception;
}
