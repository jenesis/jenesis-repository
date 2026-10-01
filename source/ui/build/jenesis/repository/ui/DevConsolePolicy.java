package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * What a console supplies to the one development sign-in chain ({@link DevConsoleSecurity}): the URL space it guards
 * and the authorization matrix it applies, the only things in which the consoles differ.
 */
public interface DevConsolePolicy {

    /** The paths the dev chain guards - this console's own URL space. */
    List<String> space();

    /** The authorization matrix, the same one this console's production chain applies. */
    void rules(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth);
}
