package build.jenesis.repository.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the development credential form at {@code GET /login/dev} - the page the {@link LoginOptions} entry
 * {@link DevConsoleSecurity} contributes links to. The form POSTs back to {@code /login/dev}, where Spring Security's
 * form-login filter authenticates it, so the GET reaches this controller and the POST is intercepted before it.
 *
 * <p>It exists at all because the shared sign-in page lists <em>mechanisms</em> and a mechanism owns its own URL
 * space - which is exactly how key login works, and the shape this follows rather than inventing a second one. The
 * alternative, a credential form on the shared page itself, would put one mechanism's input on a page whose whole
 * job is to be neutral between them.
 */
@Controller
public class DevLoginController {

    @GetMapping("/login/dev")
    public String form() {
        return "console/dev-login";
    }
}
