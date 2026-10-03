package build.jenesis.repository.ui;

import module java.base;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Where the first-run guide's {@link GithubOffer} posts: the app pasted is stored where none is configured, and the
 * session signs in with GitHub claiming administration for the identity GitHub returns ({@link AdministratorClaim}).
 * The claim is this super-admin session's alone, good once and for minutes, so only the operator who started the
 * guide can make someone administrator through it.
 */
@Controller
@ConsoleScreen
public class GithubSetupController {

    private final GithubOffer offer;

    public GithubSetupController(GithubOffer offer) {
        this.offer = offer;
    }

    /** Accept the offer and sign in, or return to the guide saying why not. */
    @PostMapping(GithubOffer.ROUTE)
    public String accept(@RequestParam(name = "clientId", defaultValue = "") String clientId,
                         @RequestParam(name = "clientSecret", defaultValue = "") String clientSecret,
                         HttpSession session, RedirectAttributes redirect) throws IOException {
        Optional<String> refused = offer.accept(clientId, clientSecret);
        if (refused.isPresent()) {
            redirect.addFlashAttribute("error", refused.get());
            return "redirect:/ui/setup";
        }
        AdministratorClaim.offer(session, Instant.now());
        return "redirect:" + GithubOffer.SIGN_IN;
    }
}
