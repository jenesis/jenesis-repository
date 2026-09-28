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
 * Turns "you hold nothing here" into the {@code /no-access} screen, and answers every other refusal as the
 * deployment's {@link AccessDenial} says - {@code 404} unless an operator chose {@code 403} - as every other surface
 * answers a caller without access.
 *
 * <p>The distinction is the whole of this class, and collapsing it would be worse than not having the screen. A
 * viewer who reaches an administrator's screen is being refused one screen on a console they otherwise use. A
 * principal that holds nothing anywhere is being told something else entirely: that sign-in worked and access has not
 * been granted yet. Sending the first to the second would tell a working user they have no access at all; sending the
 * second to a refusal would tell a new colleague that the deployment is broken.
 *
 * <p>So the question asked here is {@link ConsoleAccess}, the same one the chain's own rule asks, rather than
 * anything about the request that was refused.
 *
 * <p>An unauthenticated request never reaches an access-denied handler - it is an authentication entry point's
 * business, and that one redirects to the sign-in page.
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
        // Through ConsoleAccessRule.holds, not ConsoleAccess directly: the floor lets an established administrator
        // past on their authority, and a handler that asked only the grant store would answer "you hold nothing" to
        // somebody the floor had already admitted - which is what an administrator refused ONE screen above their
        // grade would then be told.
        if (!ConsoleAccessRule.holds(access, authentication)) {
            response.sendRedirect(request.getContextPath() + "/ui/no-access");
            return;
        }
        if (!response.isCommitted()) {
            // A write without its CSRF token is refused before anything about access is decided, and it is not a
            // question about a name, so it keeps the 403 that says a form must be resubmitted.
            response.sendError(denied instanceof CsrfException ? 403 : AccessDenial.configured().status());
        }
    }
}
