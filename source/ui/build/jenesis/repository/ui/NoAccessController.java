package build.jenesis.repository.ui;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The screen a signed-in principal that holds nothing sees (see {@link ConsoleAccess}): it says sign-in worked and
 * access is not granted yet, and shows the provider-qualified id an administrator must grant to, which a person cannot
 * otherwise read. The id, never a changeable display name. Reachable by any authenticated principal, since it is where
 * the access check leads.
 */
@Controller
@ConsoleScreen
public class NoAccessController {

    @GetMapping("/ui/no-access")
    public String noAccess(Authentication authentication, Model model) {
        model.addAttribute("qualifiedId", authentication == null ? "" : authentication.getName());
        return "console/no-access";
    }
}
