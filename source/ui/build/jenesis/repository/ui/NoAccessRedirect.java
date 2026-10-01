package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.server.spi.AccessDenial;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Sends a principal that holds nothing to the {@code /no-access} screen, and answers every other refusal as the
 * deployment's {@link AccessDenial} says ({@code 404} unless an operator chose {@code 403}). The two differ: one is a
 * user refused one screen, the other a new user whose sign-in worked and who has not been granted access yet. The
 * question is the chain's own ({@link ConsoleAccessRule#holds}), not anything about the refused request. An
 * unauthenticated request is the entry point's, which redirects to sign-in.
 */
public class NoAccessRedirect implements AccessDeniedHandler {

    private final ConsoleAccess access;

    public NoAccessRedirect(ConsoleAccess access) {
        this.access = access;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException denied) throws IOException, jakarta.servlet.ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // The floor's own predicate, so an administrator refused one screen is not told they hold nothing.
        if (!ConsoleAccessRule.holds(access, authentication)) {
            response.sendRedirect(request.getContextPath() + "/ui/no-access");
            return;
        }
        if (!response.isCommitted()) {
            // A missing CSRF token keeps the 403 that says a form must be resubmitted.
            response.sendError(denied instanceof CsrfException ? 403 : AccessDenial.configured().status());
        }
    }
}
