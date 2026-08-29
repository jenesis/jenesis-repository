package build.jenesis.repository.ui;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import module java.base;

/**
 * The sign-in page: the choices the installed mechanism modules offer, each linking into the URL space that mechanism
 * owns, and with none installed a notice saying so rather than an empty page. An already-authenticated visitor is
 * sent to the console.
 *
 * <p>It reads {@link LoginOptions}, which is what every console does. This one used to enumerate Spring Security's
 * {@code ClientRegistrationRepository} itself, so it could only show OAuth2 and OIDC: a mechanism that is not an
 * OAuth2 client appeared on one console's sign-in page and not on the other's. Whether a mechanism can be signed in
 * with is the mechanism's statement, not a console's guess at what kind it is.
 */
@Controller
public class LoginController {

    private final List<LoginOptions> mechanisms;

    public LoginController(List<LoginOptions> mechanisms) {
        this.mechanisms = mechanisms;
    }

    @GetMapping("/login")
    public String login(Authentication authentication, Model model) {
        if (authenticated(authentication)) {
            return "redirect:/console";
        }
        List<LoginOptions.LoginOption> options = new ArrayList<>();
        for (LoginOptions mechanism : mechanisms) {
            options.addAll(mechanism.options());
        }
        model.addAttribute("loginConfigured", !options.isEmpty());
        model.addAttribute("loginOptions", options);
        return "console/login";
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
