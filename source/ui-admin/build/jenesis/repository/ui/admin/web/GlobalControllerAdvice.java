package build.jenesis.repository.ui.admin.web;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.ReadOnlyException;
import build.jenesis.repository.ui.PrincipalNameResolver;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.NavEntry.Access;
import build.jenesis.repository.ui.NavEntry.Section;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.identity.UserDirectory.Role;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.PostureBadge;
import build.jenesis.repository.ui.store.SettingsAdmin;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Cross-cutting web concerns: expose the signed-in user, the selected tenant, and the role flags for
 * that tenant to every view (so the layout can greet them, show the instances switcher only when it
 * is meaningful, let editors mutate and reserve admin-only controls). The role flags are resolved
 * against the current tenant, and a super-admin is admin everywhere. Validation/storage failures
 * become a friendly error page, and a missing tenant selection bounces back through the router.
 */
@ControllerAdvice
public class GlobalControllerAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalControllerAdvice.class);

    private final Memberships memberships;
    private final CurrentTenant current;
    private final CapabilityService capabilities;
    private final List<PrincipalNameResolver> principalNames;
    private final Environment environment;
    private final SettingsAdmin settings;

    /**
     * The resolved licence state, injected rather than read from {@code Licenses.state()} statically.
     *
     * <p>The static is marked in {@code Licenses} as a boot-time wiring seam, and reading it directly here made the
     * licensed rendering untestable: a booted console can only reach LICENSED with a token signed by the vendor's
     * private key, which no test may hold, and the alternative - letting configuration add a trust anchor - would
     * make a stock deployment accept a licence anyone could mint. Injection sidesteps both: a test composition
     * supplies a state, and nothing a customer can configure reaches it.
     */
    public GlobalControllerAdvice(Memberships memberships, CurrentTenant current, CapabilityService capabilities,
                                  List<PrincipalNameResolver> principalNames, Environment environment,
                                  SettingsAdmin settings) {
        this.memberships = memberships;
        this.current = current;
        this.capabilities = capabilities;
        this.principalNames = principalNames;
        this.environment = environment;
        this.settings = settings;
    }

    /** Whether the deployment runs read-only ({@code jenreg.read-only}), so every view can show a banner
     *  and a mutating affordance can hide itself. Read straight off the environment - the console observes the mode,
     *  the store choke point enforces it. */
    /**
     * The product this console is, and what it is for - the brand on the sign-in page.
     *
     * <p>It is a model attribute rather than a literal in the template because the sign-in page is shared: there were
     * two, each hardcoding its own name, which is why one of them still called itself by a module name.
     */
    @ModelAttribute("product")
    public String product() {
        return "Genesis Depot";
    }

    /** One line under the product name, saying what a visitor is signing in to. */
    @ModelAttribute("tagline")
    public String tagline() {
        return "Admin console for the Genesis Depot server";
    }

    @ModelAttribute("readOnly")
    public boolean readOnly() {
        return environment.getProperty("jenreg.read-only", Boolean.class, false);
    }

    /** The strictly-opt-in anonymous-role grant (WANON.1, {@code jenreg.anonymous-rights}, env
     *  {@code JENREG_ANONYMOUS_RIGHTS}), so every view shows an explicit "Anonymous access" banner when it
     *  is set - visible, never hidden. This console's mirror of the base console's {@code ConsoleAdvice} banner (the free
     *  {@code build.jenesis.repository.ui} advice does not ride this richer console). Read straight off the environment,
     *  like {@link #readOnly()} - the console observes the posture, the {@code Authorization} choke point enforces it.
     *  Blank (the default) ⇒ no anonymous access and no banner. */
    @ModelAttribute("anonymousRights")
    public String anonymousRights() {
        return environment.getProperty("jenreg.anonymous-rights", "").trim();
    }

    /** The header's security-posture badge - the advisory count a super-admin sees on every view,
     *  linking to the Security-posture screen; a clean deployment renders no badge. It is derived from the very same
     *  collected report {@code /posture} renders, for the same session-selected tenant, so the number the
     *  badge names is by construction the number of rows its own destination lists. Before it collected a
     *  second report over the <em>raw environment</em> while the screen read stored-settings-over-environment, so an
     *  advisory raised by a stored dial was listed on the screen and counted as zero here; see {@link PostureBadge}
     *  for why the tenant rows came with the fix rather than being kept out of it.
     *
     *  <p>Collected only for a super-admin, because nobody else's view renders the badge - the model attribute is
     *  {@code null} for everyone else and the shell's condition already guards on it, so an ordinary user's page view
     *  performs no settings read at all (&sect;7). The collection is memoised by {@link SettingsAdmin}, which is what
     *  keeps a per-view read off the store; a settings change made through this console drops that memo at once.
     *
     *  <p>A collection that fails is reported as {@link PostureBadge#unknown()} rather than as zero and rather than
     *  as a 500 on every console page: an unreadable posture is not a clean one, and the screen the badge links to
     *  fails visibly with the actual reason. The badge names the risk count only, never a value. */
    @ModelAttribute("postureBadge")
    public PostureBadge postureBadge(Authentication authentication) {
        if (!hasSuperadmin(authentication)) {
            return null;
        }
        try {
            return PostureBadge.of(settings.posture(current.name(), environment::getProperty).posture().count());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("The security-posture report could not be collected for the console header badge; it renders as"
                    + " unknown rather than as a clean zero", e);
            return PostureBadge.unknown();
        }
    }

    /** The installed feature modules, so every view gates its surface on what this deployment actually carries. */
    @ModelAttribute("capabilities")
    public CapabilityService.Capabilities capabilities() {
        return capabilities.capabilities();
    }

    @ModelAttribute("currentUser")
    public String currentUser(Authentication authentication) {
        return PrincipalNameResolver.resolve(principalNames, authentication);
    }

    @ModelAttribute("tenant")
    public String tenant() {
        return current.name();
    }

    @ModelAttribute("isSuperadmin")
    public boolean isSuperadmin(Authentication authentication) {
        return hasSuperadmin(authentication);
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin(Authentication authentication) {
        return roleAtLeast(authentication, Role.ADMIN);
    }

    @ModelAttribute("isEditor")
    public boolean isEditor(Authentication authentication) {
        return roleAtLeast(authentication, Role.EDITOR);
    }

    @ModelAttribute("showInstances")
    public boolean showInstances(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return hasSuperadmin(authentication) || memberships.accessibleTo(authentication.getName(), false).size() >= 2;
    }

    /** Whether the passive "Tenant X" nav indicator renders: only when the tenant is an explicit choice, i.e. the
     *  user can actually reach two or more tenants. A user with a single accessible tenant - and a super-admin of a
     *  single-tenant deployment - has the selection made implicitly, so they see no tenancy chrome at all (the
     *  console is still always a tenant-scoped view underneath; only the label is suppressed). */
    @ModelAttribute("showTenant")
    public boolean showTenant(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return memberships.accessibleTo(authentication.getName(), hasSuperadmin(authentication)).size() >= 2;
    }

    /** The console shell's navigation links, resolved to just the ones this user may see, so the template renders them
     *  with a {@code th:each} and carries no per-screen condition. The core screens (projects, repositories,
     *  credentials, the role-gated admin/settings/modules screens, and the audit screen when the audit module is
     *  installed) are listed here; every installed console module then contributes its own links through
     *  {@link build.jenesis.repository.ui.ConsoleModuleProvider#navEntries()} (discovered once by {@link
     *  CapabilityService}), gated by the module's declared access floor - so a module's link appears exactly when the
     *  module is on the path and the user's role clears it, and the shell names no module's screens. */
    @ModelAttribute("navEntries")
    public List<NavEntry> navEntries(Authentication authentication) {
        boolean admin = roleAtLeast(authentication, Role.ADMIN);
        boolean superadmin = hasSuperadmin(authentication);
        CapabilityService.Capabilities caps = capabilities.capabilities();
        List<NavEntry> entries = new ArrayList<>();
        if (showInstances(authentication)) {
            entries.add(new NavEntry("Instances", "/instances"));
        }
        entries.add(new NavEntry("Projects", "/projects"));
        entries.add(new NavEntry("Repositories", "/repositories"));
        entries.add(new NavEntry("Credentials", "/credentials"));
        // Everything below is administration: reached rarely, and grouped so the daily objects above are not
        // buried among them. Two labels are renamed here as well - "Admin" said nothing about the screen (it is
        // console members and SCIM) and "SPI catalog" is a word for whoever built the thing, not whoever runs it.
        if (admin && caps.audit()) {
            entries.add(new NavEntry("Audit trail", "/admin/audit", Access.ADMIN, Section.ADMINISTRATION));
        }
        if (admin) {
            entries.add(new NavEntry("Members", "/admin", Access.ADMIN, Section.ADMINISTRATION));
        }
        if (superadmin) {
            entries.add(new NavEntry("Setup", "/setup", Access.SUPERADMIN, Section.ADMINISTRATION));
            entries.add(new NavEntry("Settings", "/settings", Access.SUPERADMIN, Section.ADMINISTRATION));
            entries.add(new NavEntry("Tenant settings", "/settings/tenant",
                    Access.SUPERADMIN, Section.ADMINISTRATION));
            entries.add(new NavEntry("Modules", "/settings/modules", Access.SUPERADMIN, Section.ADMINISTRATION));
            entries.add(new NavEntry("Installed providers", "/catalog",
                    Access.SUPERADMIN, Section.ADMINISTRATION));
            entries.add(new NavEntry("Metrics", "/observability",
                    Access.SUPERADMIN, Section.ADMINISTRATION));
            entries.add(new NavEntry("Security posture", "/posture",
                    Access.SUPERADMIN, Section.ADMINISTRATION));
        }
        for (NavEntry entry : capabilities.moduleNav()) {
            if (visibleTo(entry.access(), admin, superadmin)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** The administration entries, split out so the shell renders one grouped disclosure rather than a flat bar. */
    @ModelAttribute("adminNav")
    public List<NavEntry> adminNav(Authentication authentication) {
        return navEntries(authentication).stream()
                .filter(entry -> entry.section() == Section.ADMINISTRATION)
                .toList();
    }

    /** Whether a module-contributed nav entry's access floor is cleared by the current user's role. */
    private static boolean visibleTo(NavEntry.Access access, boolean admin, boolean superadmin) {
        return switch (access) {
            case USER -> true;
            case ADMIN -> admin;
            case SUPERADMIN -> superadmin;
        };
    }

    private boolean roleAtLeast(Authentication authentication, Role min) {
        if (authentication == null) {
            return false;
        }
        if (hasSuperadmin(authentication)) {
            return true;
        }
        String tenant = current.name();
        return tenant != null && memberships.roleIn(tenant, authentication.getName())
                .map(role -> role.atLeast(min))
                .orElse(false);
    }

    private static boolean hasSuperadmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPERADMIN"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public String noTenant(HttpServletRequest request, Model model) {
        // A missing tenant selection normally bounces back through the router at /console. But when the request that
        // failed IS the console router (or something under it), redirecting there would loop - the router re-throws the
        // same IllegalStateException and we redirect again. On the console path, render the error page instead of
        // redirecting into that loop.
        String path = request == null ? null : request.getRequestURI();
        if (path != null && (path.equals("/console") || path.startsWith("/console/"))) {
            model.addAttribute("error", "No tenant could be selected for your account. Contact an administrator to be "
                    + "granted access to a tenant.");
            return "error";
        }
        return "redirect:/console";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public String badInput(IllegalArgumentException e, Model model) {
        model.addAttribute("error", e.getMessage());
        return "error";
    }

    @ExceptionHandler(DateTimeException.class)
    public String badDateTime(Model model) {
        model.addAttribute("error", "Enter a valid ISO-8601 duration (for example P30D) or timestamp.");
        return "error";
    }

    @ExceptionHandler(ReadOnlyException.class)
    public String readOnly(Model model) {
        model.addAttribute("error", "This instance is in read-only mode. Writes - including this admin action - are "
                + "refused; browse, download and search remain available.");
        return "error";
    }

    @ExceptionHandler(IOException.class)
    public String storageError(IOException e, Model model) {
        LOGGER.warn("Storage error handling a console request", e);
        model.addAttribute("error", "A storage error occurred. Please retry, and contact your administrator if it "
                + "persists.");
        return "error";
    }
}
