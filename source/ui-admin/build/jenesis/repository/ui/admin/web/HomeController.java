package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;
import build.jenesis.repository.ui.admin.config.DomainConfig;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.admin.security.SessionCurrentTenant;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * tenants list. With a tenant chosen, the landing is the dashboard: the panels every installed
 * {@link DashboardContributor} draws for it.
 */
@Controller
@ConsoleScreen
public class HomeController {

    private final Memberships memberships;
    private final SessionCurrentTenant current;
    private final SetupWizard setup;
    private static final Logger LOGGER = LoggerFactory.getLogger(HomeController.class);

    private final DomainConfig.Tenancy tenancy;
    private final ObjectProvider<DashboardContributor> contributors;

    public HomeController(Memberships memberships, SessionCurrentTenant current, SetupWizard setup,
                          DomainConfig.Tenancy tenancy, ObjectProvider<DashboardContributor> contributors) {
        this.memberships = memberships;
        this.current = current;
        this.setup = setup;
        this.tenancy = tenancy;
        this.contributors = contributors;
    }

    /** The root forwards to the console, whose screens are all under {@code /ui}. */
    @GetMapping("/")
    public String root() {
        return "redirect:/ui/";
    }

    /**
     * The landing: the dashboard of the tenant chosen, or - with none - a redirect to where one is chosen, handing on
     * any flash message that arrived, since a flash lives for one request and a screen finishing here (the setup guide)
     * said what it did in one.
     */
    @GetMapping({"/ui", "/ui/"})
    public String home(Authentication authentication, HttpSession session, Model model,
                       RedirectAttributes redirect) throws IOException {
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
        boolean guided = setup.redirects(authentication, session);
        if (guided || current.name() == null) {
            for (String said : List.of("message", "error", "issuedKey", "issuedFor")) {
                if (model.containsAttribute(said)) {
                    redirect.addFlashAttribute(said, model.getAttribute(said));
                }
            }
            return guided ? "redirect:/ui/setup" : "redirect:/ui/tenants";
        }
        List<DashboardPanel> panels = panels(new DashboardContributor.Viewer(current.name(), superadmin));
        // A panel with nothing to say - no figure, no line, no verdict - is left out rather than drawn empty; one still
        // counting keeps the page asking again until it has.
        model.addAttribute("panels", panels.stream().filter(DashboardPanel::says).toList());
        model.addAttribute("refreshing", panels.stream().anyMatch(DashboardPanel::refreshing));
        return "dashboard";
    }

    /** Every contributor's panels in its order; one that fails is drawn as unreadable, naming it, and the rest as
     *  usual. */
    private List<DashboardPanel> panels(DashboardContributor.Viewer viewer) {
        List<DashboardContributor> ordered = contributors.stream()
                .sorted(Comparator.comparingInt(DashboardContributor::order)
                        .thenComparing(contributor -> contributor.getClass().getName()))
                .toList();
        List<DashboardPanel> panels = new ArrayList<>();
        for (DashboardContributor contributor : ordered) {
            try {
                panels.addAll(contributor.panels(viewer));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("The dashboard contributor {} could not be read", contributor.getClass().getName(),
                        failure);
                panels.add(new DashboardPanel(contributor.getClass().getSimpleName(), "/ui/", "",
                        "could not be read", DashboardPanel.Tone.ATTENTION, List.of(),
                        Optional.empty(), String.valueOf(failure.getMessage()), Optional.empty(), false));
            }
        }
        return panels;
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
