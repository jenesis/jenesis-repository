package build.jenesis.repository.auth.keylogin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the key-entry form at {@code GET /login/key} (the link the {@link build.jenesis.repository.ui.LoginOptions} adds to
 * the console login page). The form POSTs the key back to {@code /login/key}, where Spring Security's form-login filter
 * - configured by {@link KeyLoginConfig} against {@link KeyLoginAuthenticationProvider} - authenticates it; the GET
 * therefore reaches this controller while the POST is intercepted before it. The page labels the mechanism a demo /
 * simple-deployment path, not the recommended production sign-in.
 */
@Controller
public class KeyLoginPageController {

    @GetMapping("/login/key")
    public String form() {
        return "keylogin/form";
    }
}
