package build.jenesis.repository.auth.keylogin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * Serves the key-entry form at {@code GET /login/key}, the link {@link build.jenesis.repository.ui.LoginOptions} adds
 * to the login page. The form POSTs back to {@code /login/key}, where Spring Security's form-login filter configured by
 * {@link KeyLoginConfig} authenticates it, so only the GET reaches this controller. The page presents the mechanism as
 * a simple-deployment path, not the recommended production sign-in.
 */
@Controller
@ConsoleScreen
public class KeyLoginPageController {

    @GetMapping("/ui/login/key")
    public String form() {
        return "keylogin/form";
    }
}
