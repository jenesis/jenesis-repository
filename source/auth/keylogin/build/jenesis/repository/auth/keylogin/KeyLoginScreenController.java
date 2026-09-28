package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.ui.ConsoleScreen;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The console's login keys screen: the issued keys, a form that issues one - its key shown once, on the page the form
 * lands on - and a revoke beside each. It calls {@link KeyLogins}, the implementation behind {@code /api/keylogin}, in
 * process, so the console, the API and the CLI issue and revoke through one piece of code. The screen sits under
 * {@code /ui/settings/}, which the console holds to its super-admins: a login key can bind a principal into any
 * tenant, so issuing one is the deployment's business rather than a tenant administrator's.
 */
@Controller
@ConsoleScreen
public class KeyLoginScreenController {

    /** The screen's route; the forms post beneath it. */
    public static final String ROUTE = "/ui/settings/login-keys";

    private final KeyLogins keyLogins;

    public KeyLoginScreenController(KeyLogins keyLogins) {
        this.keyLogins = keyLogins;
    }

    @GetMapping(ROUTE)
    public String keys(Model model) {
        model.addAttribute("keys", keyLogins.list());
        return "keylogin/keys";
    }

    /** Issue a key and land back on the screen, which shows it once. A refused request says why and issues nothing. */
    @PostMapping(ROUTE)
    public String issue(@RequestParam("principal") String principal,
                        @RequestParam(name = "login", required = false) String login,
                        @RequestParam("tenant") String tenant,
                        @RequestParam(name = "role", required = false) String role,
                        Principal operator, RedirectAttributes redirect) throws IOException {
        try {
            KeyLogins.Issued issued = keyLogins.issue(actor(operator), principal, login, tenant, role);
            redirect.addFlashAttribute("issued", issued);
            redirect.addFlashAttribute("message", "Issued a login key for " + issued.principal() + ", "
                    + issued.role() + " of " + issued.tenant() + ". It is shown only once, below.");
        } catch (IllegalArgumentException refused) {
            redirect.addFlashAttribute("error", "No key issued: " + refused.getMessage());
        }
        return "redirect:" + ROUTE;
    }

    /** Revoke one key: it stops signing in at once, and the membership its issue wrote is removed. */
    @PostMapping(ROUTE + "/revoke")
    public String revoke(@RequestParam("id") String id, Principal operator, RedirectAttributes redirect)
            throws IOException {
        keyLogins.revoke(actor(operator), id);
        redirect.addFlashAttribute("message", "Revoked the login key.");
        return "redirect:" + ROUTE;
    }

    private static String actor(Principal operator) {
        return operator == null ? "anonymous" : operator.getName();
    }
}
