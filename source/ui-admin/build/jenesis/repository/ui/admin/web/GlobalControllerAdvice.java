package build.jenesis.repository.ui.admin.web;

import build.jenesis.repository.server.spi.Authorization;
import module java.base;
import module org.slf4j;

import build.jenesis.repository.ui.SuperadminRole;
import build.jenesis.repository.failure.Failures;
import build.jenesis.repository.store.ReadOnlyException;
import build.jenesis.repository.ui.PrincipalNameResolver;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.VersionDocument;
import build.jenesis.repository.ui.NavEntry.Access;
import build.jenesis.repository.ui.NavEntry.Group;
import build.jenesis.repository.ui.ConsoleScreen;
import build.jenesis.repository.ui.DurationWords;
import build.jenesis.repository.ui.Instants;
import build.jenesis.repository.ui.Navigation;
import build.jenesis.repository.ui.RepositoryHeader;
import build.jenesis.repository.ui.RepositoryPage;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.ui.RepositoryPage.Topic;
import build.jenesis.repository.ui.admin.security.Memberships;
import build.jenesis.repository.ui.identity.UserDirectory.Role;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.PostureBadge;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.SettingsAdmin;
import jakarta.servlet.http.HttpServletRequest;
import build.jenesis.repository.ui.admin.config.DomainConfig;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Cross-cutting web concerns of the {@link ConsoleScreen}s alone, so the API and the data plane never get the console's
 * error page: the signed-in user, the selected tenant and that tenant's role flags (a super-admin is admin everywhere)
 * for every view, a friendly error page for validation and storage failures, and a bounce back through the router for
 * a missing tenant selection.
 */
@ControllerAdvice(annotations = ConsoleScreen.class)
public class GlobalControllerAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalControllerAdvice.class);

    private final Memberships memberships;
    private final CurrentTenant current;
    private final CapabilityService capabilities;
    private final List<PrincipalNameResolver> principalNames;
    private final Environment environment;
    private final SettingsAdmin settings;
    private final RepositoryAdmin repositories;
    private final DomainConfig.Tenancy tenancy;

    /** The sections whose pages are about one tenant. */
    private static final Set<Group> TENANT_GROUPS = EnumSet.of(Group.REPOSITORIES, Group.BUILD_CACHE, Group.ACCESS);

    public GlobalControllerAdvice(Memberships memberships, CurrentTenant current, CapabilityService capabilities,
                                  List<PrincipalNameResolver> principalNames, Environment environment,
                                  SettingsAdmin settings, RepositoryAdmin repositories,
                                  DomainConfig.Tenancy tenancy) {
        this.memberships = memberships;
        this.current = current;
        this.capabilities = capabilities;
        this.principalNames = principalNames;
        this.environment = environment;
        this.settings = settings;
        this.repositories = repositories;
        this.tenancy = tenancy;
    }

    /** The product this console is: the brand on the shared sign-in page. */
    @ModelAttribute("product")
    public String product() {
        return "Jenesis Repository";
    }

    /** How every screen shows an instant - see {@link Instants}. */
    @ModelAttribute("instants")
    public Instants instants() {
        return Instants.DISPLAY;
    }

    /** The names of the parameters this request carried a value for, so a screen's pager offers the way back to its
     *  first page exactly when it was asked past it: a template may not read request parameters in the arguments it
     *  hands a fragment. */
    @ModelAttribute("asked")
    public Set<String> asked(HttpServletRequest request) {
        Set<String> names = new TreeSet<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values.length > 0 && !values[0].isBlank()) {
                names.add(name);
            }
        });
        return names;
    }

    /** How every screen shows a duration - see {@link DurationWords}. */
    @ModelAttribute("durations")
    public DurationWords durations() {
        return DurationWords.DISPLAY;
    }

    /** One line under the sign-in heading, saying what a visitor is signing in to. */
    @ModelAttribute("tagline")
    public String tagline() {
        return "The console of the repository server.";
    }

    /** Whether the deployment runs read-only ({@code jenrepo.read-only}), so a view shows a banner and hides mutating
     *  affordances. The console observes the mode; the store enforces it. */
    @ModelAttribute("readOnly")
    public boolean readOnly() {
        return environment.getProperty("jenrepo.read-only", Boolean.class, false);
    }

    /** The opt-in anonymous grant ({@code jenrepo.anonymous-rights}), so every view shows an "Anonymous access" banner
     *  while it is set; blank by default. Observed here as {@link #readOnly()} is; {@code Authorization} enforces
     *  it. */
    @ModelAttribute("anonymousRights")
    public String anonymousRights() {
        return environment.getProperty("jenrepo.anonymous-rights", "").trim();
    }

    /** The header's security-posture badge for a super-admin ({@code null} for anyone else): the advisory count of the
     *  same collected report the posture screen renders for the same tenant, memoised by {@link SettingsAdmin} and
     *  dropped on a settings change. A failed collection is {@link PostureBadge#unknown()}, never zero or a 500. */
    @ModelAttribute("postureBadge")
    public PostureBadge postureBadge(Authentication authentication) {
        if (!SuperadminRole.held(authentication)) {
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
        return SuperadminRole.held(authentication);
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin(Authentication authentication) {
        return roleAtLeast(authentication, Role.ADMIN);
    }

    /** The paths the installed console modules add to every repository, so a page links to one only where it is. */
    @ModelAttribute("repositoryPagePaths")
    public Set<String> repositoryPagePaths() {
        Set<String> paths = new HashSet<>();
        for (RepositoryPage page : capabilities.moduleRepositoryPages()) {
            paths.add(page.path());
        }
        return paths;
    }

    @ModelAttribute("isEditor")
    public boolean isEditor(Authentication authentication) {
        return roleAtLeast(authentication, Role.EDITOR);
    }

    @ModelAttribute("showTenants")
    public boolean showTenants(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return SuperadminRole.held(authentication) || memberships.accessibleTo(authentication.getName(), false).size() >= 2;
    }

    /** Whether the header shows the tenant beside the brand: where the deployment serves several tenants. */
    @ModelAttribute("multiTenant")
    public boolean multiTenant() {
        return tenancy.multi();
    }

    /** The word a screen names what its settings and members belong to: "tenant" where the deployment serves several,
     *  "deployment" where it serves one, since a tenant is no concept to a reader who has only ever had one. */
    @ModelAttribute("scopeWord")
    public String scopeWord() {
        return tenancy.multi() ? "tenant" : "deployment";
    }

    /** The documents installed console modules serve about a version, which a version's page links. */
    @ModelAttribute("versionDocuments")
    public List<VersionDocument> versionDocuments() {
        return capabilities.versionDocuments();
    }

    /**
     * The console's two navigation levels for this request, resolved once to what this reader may open with the current
     * page marked. The core screens are listed here; imported modules add theirs through
     * {@link build.jenesis.repository.ui.ConsoleModuleProvider#navEntries()} and
     * {@link build.jenesis.repository.ui.ConsoleModuleProvider#repositoryPages()}. A page is visible when the reader's
     * role clears its floor and its required capability is present.
     */
    @ModelAttribute("navigation")
    public Navigation navigation(Authentication authentication, HttpServletRequest request) throws IOException {
        if (authentication == null) {
            return Navigation.NONE;
        }
        boolean editor = roleAtLeast(authentication, Role.EDITOR);
        boolean admin = roleAtLeast(authentication, Role.ADMIN);
        boolean superadmin = SuperadminRole.held(authentication);
        List<NavEntry> entries = new ArrayList<>();
        entries.add(new NavEntry("Current repositories", "/ui/repositories", Group.REPOSITORIES));
        entries.add(new NavEntry("New repository", "/ui/new/repository", Access.EDITOR, Group.REPOSITORIES));
        entries.add(new NavEntry("Limits", "/ui/limits", Group.REPOSITORIES));
        entries.add(new NavEntry("Current projects", "/ui/projects", Group.BUILD_CACHE));
        entries.add(new NavEntry("New project", "/ui/new/project", Access.EDITOR, Group.BUILD_CACHE));
        entries.add(new NavEntry("Build tools", "/ui/projects/build-tools", Group.BUILD_CACHE));
        entries.add(new NavEntry("Cache volume", "/ui/projects/cache-volume", Access.SUPERADMIN, Group.BUILD_CACHE));
        entries.add(new NavEntry("Current credentials", "/ui/credentials", Access.ADMIN, Group.ACCESS));
        entries.add(new NavEntry("New credential", "/ui/credentials/new", Access.ADMIN, Group.ACCESS));
        entries.add(new NavEntry("Credential policies", "/ui/credentials/policies", Access.ADMIN, Group.ACCESS));
        entries.add(new NavEntry("Keyless CI", "/ui/credentials/keyless", Access.ADMIN, Group.ACCESS));
        entries.add(new NavEntry("Members", "/ui/admin", Access.ADMIN, Group.ACCESS));
        entries.add(new NavEntry("Audit trail", "/ui/admin/audit", Access.ADMIN, Group.ACCESS, "audit"));
        entries.add(new NavEntry("Metrics", "/ui/metrics", Access.SUPERADMIN, Group.OPERATIONS));
        entries.add(new NavEntry("Security posture", "/ui/posture", Access.SUPERADMIN, Group.OPERATIONS));
        entries.add(new NavEntry("Caches", "/ui/caches", Access.SUPERADMIN, Group.OPERATIONS));
        // The group's header link opens its first entry, so the first-run guide comes last.
        entries.add(new NavEntry("Settings", "/ui/settings", Access.SUPERADMIN, Group.SETTINGS));
        entries.add(new NavEntry("Upstreams", "/ui/settings/upstreams", Access.SUPERADMIN, Group.SETTINGS));
        // A tenant's own layer of settings is a concept only a deployment serving several tenants has.
        if (tenancy.multi()) {
            entries.add(new NavEntry("Tenant settings", "/ui/settings/tenant", Access.SUPERADMIN, Group.SETTINGS));
        }
        entries.add(new NavEntry("Modules", "/ui/settings/modules", Access.SUPERADMIN, Group.SETTINGS));
        // A group of its own in a deployment serving several tenants, for a reader with more than one to pick; the
        // header's tenant name links here too.
        if (tenancy.multi() && showTenants(authentication)) {
            entries.add(new NavEntry("Current tenants", "/ui/tenants", Group.TENANTS));
        }
        entries.add(new NavEntry("Backup & restore", "/ui/settings/backup", Access.SUPERADMIN, Group.SETTINGS));
        entries.add(new NavEntry("First-run setup", "/ui/setup", Access.SUPERADMIN, Group.SETTINGS));
        entries.addAll(capabilities.moduleNav());
        List<RepositoryPage> pages = new ArrayList<>();
        pages.add(new RepositoryPage("Overview", "", Topic.CONTENTS));
        pages.add(new RepositoryPage("Browse & search", "/browse", Topic.CONTENTS));
        pages.add(new RepositoryPage("Staging", "/staging", Topic.CONTENTS, "staging"));
        pages.add(new RepositoryPage("Retention & cleanup", "/retention", Topic.LIFECYCLE, "retention"));
        pages.add(new RepositoryPage("Pins", "/pins", Topic.LIFECYCLE));
        // Import ends the core pages so it sits beside Export, the other end of moving a repository's content.
        pages.add(new RepositoryPage("Import", "/import", Topic.LIFECYCLE, "import"));
        RepositoryHeader open = repositoryHeader(request);
        pages.addAll(capabilities.moduleRepositoryPages(open == null ? null : open.format()));
        // A repository's own settings close its sidebar, after every page a module adds.
        pages.add(new RepositoryPage("Settings", "/settings", Topic.LIFECYCLE));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Until a tenant is chosen, only the deployment's own pages are offered.
        if (current.name() == null) {
            entries.removeIf(entry -> TENANT_GROUPS.contains(entry.group()) || entry.path().equals("/ui/settings/tenant"));
        }
        return ConsoleNavigation.resolve(
                entries.stream()
                        .filter(entry -> visibleTo(entry.access(), editor, admin, superadmin) && capabilities.has(entry.requires()))
                        .toList(),
                pages.stream()
                        .filter(page -> visibleTo(page.access(), editor, admin, superadmin) && capabilities.has(page.requires()))
                        .toList(),
                path);
    }

    /**
     * The repository a page under {@code /ui/repositories/<name>} is about, with its identity, for the header every
     * such page opens with - resolved here from the path so a page any module contributes opens the same way.
     * {@code null} on a page about no repository, or before a tenant is chosen.
     */
    @ModelAttribute("repositoryHeader")
    public RepositoryHeader repositoryHeader(HttpServletRequest request) throws IOException {
        // Resolved once per request: the sidebar reads the repository's type from it too.
        if (request.getAttribute(HEADER) instanceof RepositoryHeader resolved) {
            return resolved;
        }
        String repository = ConsoleNavigation.repository(
                request.getRequestURI().substring(request.getContextPath().length()));
        if (repository == null || !Scopes.valid(repository) || current.name() == null) {
            return null;
        }
        RepositoryHeader header = repositories.identity(repository)
                .map(identity -> new RepositoryHeader(repository, identity.format(), identity.url()))
                .orElseGet(() -> new RepositoryHeader(repository, null, null));
        request.setAttribute(HEADER, header);
        return header;
    }

    /** The request attribute {@link #repositoryHeader} keeps its answer under. */
    private static final String HEADER = GlobalControllerAdvice.class.getName() + ".repositoryHeader";

    /** Whether a page's access floor is cleared by the current user's role. */
    private static boolean visibleTo(NavEntry.Access access, boolean editor, boolean admin, boolean superadmin) {
        return switch (access) {
            case USER -> true;
            case EDITOR -> editor;
            case ADMIN -> admin;
            case SUPERADMIN -> superadmin;
        };
    }

    private boolean roleAtLeast(Authentication authentication, Role min) {
        if (authentication == null) {
            return false;
        }
        if (SuperadminRole.held(authentication)) {
            return true;
        }
        String tenant = current.name();
        return tenant != null && memberships.roleIn(tenant, authentication.getName())
                .map(role -> role.atLeast(min))
                .orElse(false);
    }

    /** A credential change on a deployment with authorization off, which keeps none: said, rather than taken for the
     *  missing tenant every other {@link IllegalStateException} here means. */
    @ExceptionHandler(Authorization.Open.class)
    public String open(Authorization.Open e, Model model) {
        model.addAttribute("error", e.getMessage());
        return "error";
    }

    @ExceptionHandler(IllegalStateException.class)
    public String noTenant(IllegalStateException e, HttpServletRequest request, Model model) {
        // With a tenant selected the state is not a missing tenant - a module not installed, a key not configured - and
        // is said on the error page; bouncing to the router would land the operator elsewhere with the reason lost.
        if (current.name() != null) {
            model.addAttribute("error", e.getMessage());
            return "error";
        }
        // A missing tenant bounces back through the router at /ui/, except from the router itself, which would loop.
        String path = request == null ? null : request.getRequestURI();
        if (path != null && (path.equals("/ui") || path.equals("/ui/"))) {
            model.addAttribute("error", "No tenant could be selected for your account. Contact an administrator to be "
                    + "granted access to a tenant.");
            return "error";
        }
        return "redirect:/ui/";
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
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String storageError(IOException e, HttpServletRequest request, Model model) {
        String reference = Failures.record(request.getMethod() + " " + request.getRequestURI(), e);
        model.addAttribute("error", "A storage error occurred. Please retry, and contact your administrator if it "
                + "persists.");
        model.addAttribute("reference", reference);
        return "error";
    }
}
