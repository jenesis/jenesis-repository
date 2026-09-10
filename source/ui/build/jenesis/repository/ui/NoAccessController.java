package build.jenesis.repository.ui;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The screen a signed-in principal that holds nothing sees.
 *
 * <p>It exists because sign-in and access are separate decisions ({@link ConsoleAccess} says why), and the gap
 * between them has to be a designed state. A {@code 403} tells an operator that something is broken; an empty
 * dashboard tells them nothing at all. This says which of the two it is, and shows the provider-qualified id an
 * administrator has to grant to - the one piece of information that is otherwise unobtainable, since a provider
 * subject is opaque and a person cannot read their own out of a session.
 *
 * <p>It shows the id and deliberately not a display name, which is the same rule every authority decision here
 * follows: a name a person can change is not how this deployment names them, and putting it on the screen an
 * administrator is asked to act on would invite granting to the wrong thing.
 *
 * <p>Reachable by any authenticated principal and gated no further: it is the destination of the access check, so
 * a check in front of it would send a principal that holds nothing to the screen for principals that hold nothing,
 * forever.
 */
@Controller
public class NoAccessController {

    @GetMapping("/no-access")
    public String noAccess(Authentication authentication, Model model) {
        model.addAttribute("qualifiedId", authentication == null ? "" : authentication.getName());
        return "console/no-access";
    }
}
