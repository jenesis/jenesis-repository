package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.admin.config.DomainConfig;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.admin.security.SessionCurrentTenant;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The landing routes. After sign-in a reader is routed by the tenants they can reach: a fixed deployment's one tenant,
 * or a member's only tenant, is chosen for them; a deployment administrator or a member of several picks one from the
 * tenants list.
 */
@Controller
@ConsoleScreen
public class HomeController {

    private final Memberships memberships;
    private final SessionCurrentTenant current;
    private final SetupWizard setup;
    private final DomainConfig.Tenancy tenancy;

    public HomeController(Memberships memberships, SessionCurrentTenant current, SetupWizard setup,
                          DomainConfig.Tenancy tenancy) {
        this.memberships = memberships;
        this.current = current;
        this.setup = setup;
        this.tenancy = tenancy;
    }

    /** The root forwards to the console, whose screens are all under {@code /ui}. */
    @GetMapping("/")
    public String root() {
        return "redirect:/ui/";
    }

    /**
     * The landing, which only redirects, handing on any flash message that arrived, since a flash lives for one request
     * and a screen finishing here (the setup guide) said what it did in one.
     */
    @GetMapping({"/ui", "/ui/"})
    public String home(Authentication authentication, HttpSession session, Model model,
                       RedirectAttributes redirect) throws IOException {
        for (String said : List.of("message", "error")) {
            if (model.containsAttribute(said)) {
                redirect.addFlashAttribute(said, model.getAttribute(said));
            }
        }
        if (!authenticated(authentication)) {
            return "redirect:/ui/login";
        }
        // One reachable tenant is chosen at once; a multi-tenant deployment's administrator starts in none and
        // chooses.
        boolean superadmin = hasSuperadmin(authentication);
        List<String> accessible = memberships.accessibleTo(authentication.getName(), superadmin);
        if (current.name() == null && accessible.size() == 1 && !(superadmin && tenancy.multi())) {
            current.select(accessible.get(0));
        }
        // A super-admin on the starter credential is guided first (SetupWizard); decided only here, on the landing.
        if (setup.redirects(authentication, session)) {
            return "redirect:/ui/setup";
        }
        return current.name() != null ? "redirect:/ui/repositories" : "redirect:/ui/tenants";
    }


    private static boolean authenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static boolean hasSuperadmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }
}
