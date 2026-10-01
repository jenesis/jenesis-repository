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
 * The sign-in page: the {@link LoginOptions} the installed mechanisms offer, each linking into its own URL space, or a
 * notice when none is installed. An authenticated visitor is sent to the console.
 */
@Controller
@ConsoleScreen
public class LoginController {

    private final List<LoginOptions> mechanisms;

    public LoginController(List<LoginOptions> mechanisms) {
        this.mechanisms = mechanisms;
    }

    @GetMapping("/ui/login")
    public String login(Authentication authentication, Model model) {
        if (authenticated(authentication)) {
            return "redirect:/ui/";
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

    /** One button, with its mark resolved as everywhere else in the console. */
    private static Choice choice(LoginOptions.LoginOption option) {
        Mark mark = Marks.of(option);
        return new Choice(option.label(), option.href(), mark.svg(), mark.title(), ConsoleMarks.tint(mark));
    }

    /** What the template renders per button, the mark already resolved. */
    public record Choice(String label, String href, String markSvg, String markTitle, String markTint) {
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
