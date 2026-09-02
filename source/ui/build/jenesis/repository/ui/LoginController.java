package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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
        List<Choice> options = new ArrayList<>();
        for (LoginOptions mechanism : mechanisms) {
            for (LoginOptions.LoginOption option : mechanism.options()) {
                options.add(choice(option));
            }
        }
        model.addAttribute("loginConfigured", !options.isEmpty());
        model.addAttribute("loginOptions", options);
        return "console/login";
    }

    /** One button, with its mark resolved: the mechanism's own drawing where it ships one, and otherwise the figure
     *  computed from its id - the same resolution every other console surface makes, so a sign-in button is marked
     *  the way the rest of the console is rather than being the one bare list. */
    private static Choice choice(LoginOptions.LoginOption option) {
        Mark mark = Marks.of(option);
        return new Choice(option.label(), option.href(), mark.svg(), mark.title(), ConsoleMarks.tint(mark));
    }

    /** What the template renders per button. The mark is resolved here rather than in the template for the same
     *  reason it is everywhere else: a template cannot call {@code Marks} and must not learn how a mark is chosen. */
    public record Choice(String label, String href, String markSvg, String markTitle, String markTint) {
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
