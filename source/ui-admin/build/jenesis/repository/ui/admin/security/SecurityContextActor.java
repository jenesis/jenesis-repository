package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.store.ConsoleActor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The web console's {@link ConsoleActor}: the signed-in member a privileged mutation is attributed to, read from
 * the Spring Security context. The domain services record through this seam, so they attribute an audit event
 * without depending on the web security machinery. Falls back to {@code "console"} when no authentication is bound.
 */
@Component
public class SecurityContextActor implements ConsoleActor {

    @Override
    public String name() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "console" : authentication.getName();
    }
}
