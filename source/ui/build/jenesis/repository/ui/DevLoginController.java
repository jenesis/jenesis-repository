package build.jenesis.repository.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the development credential form at {@link DevConsoleSecurity#PATH}, the page its {@link LoginOptions} entry
 * links to. The form posts to the same path, where Spring Security's form-login filter intercepts it.
 */
@Controller
@ConsoleScreen
public class DevLoginController {

    @GetMapping("/ui/login/dev")
    public String form() {
        return "console/dev-login";
    }
}
