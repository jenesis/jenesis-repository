package build.jenesis.repository.ui;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.store.Features;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import module java.base;

/**
 * Publishes deployment-wide flags every console view reads to the model, so a template shows them without each
 * controller repeating the lookup. It is the read-only flag ({@code jenreg.read-only}, env
 * {@code JENREG_READ_ONLY}): when set, the console renders a read-only banner (and a mutating affordance
 * can hide itself with the same attribute); and the security-posture count: the number of configuration-warning
 * advisories the discovered {@link build.jenesis.repository.posture.SafetyAdvisor}s raise against the effective
 * configuration, which the header shows as a badge linking to the Security-posture panel. Both are read straight off the
 * {@link Environment} - the console does no store write of its own, so it needs no bound configuration bean to observe
 * the deployment's mode.
 */
@ControllerAdvice
public class ConsoleAdvice {

    private final Environment environment;

    public ConsoleAdvice(Environment environment) {
        this.environment = environment;
    }

    @ModelAttribute("readOnly")
    public boolean readOnly() {
        return environment.getProperty("jenreg.read-only", Boolean.class, false);
    }

    /** The strictly-opt-in anonymous role (WANON.1): the rights a keyless caller is granted
     *  ({@code jenreg.anonymous-rights}, env {@code JENREG_ANONYMOUS_RIGHTS}), so the console
     *  renders an explicit "Anonymous access" banner when it is set. Only meaningful under an enforcing deployment; blank
     *  (the default, or under {@code auth=false} where the instance is already fully open) renders no banner. */
    @ModelAttribute("anonymousRights")
    public String anonymousRights() {
        if (!environment.getProperty("jenreg.auth", Boolean.class, true)) {
            return "";
        }
        String rights = environment.getProperty("jenreg.anonymous-rights", "");
        return rights == null ? "" : rights.trim();
    }

    /** The number of security-posture advisories the deployment currently raises - the count the header badge shows;
     *  zero renders no badge. Collected once through {@link PostureReport#discover} over the effective configuration. */
    @ModelAttribute("postureCount")
    public int postureCount() {
        return PostureReport.discover(Configuration.of(environment::getProperty)).count();
    }

    /**
     * The links installed console modules contribute, for the primary bar - resolved to the ones this user may see,
     * so the shell renders them with a loop and names no module's screens.
     *
     * <p>A module contributed to this console has to be reachable <em>from</em> it. Registering its configuration
     * puts its screens on the server; without this they are routes a user can only reach by typing the path, which
     * is not an extension point anybody can use.
     */
    @ModelAttribute("navEntries")
    public List<NavEntry> navEntries(Authentication authentication) {
        return entries(authentication, NavEntry.Section.PRIMARY);
    }

    /** The same, for the administration group the layout renders as a dropdown. */
    @ModelAttribute("adminNav")
    public List<NavEntry> adminNav(Authentication authentication) {
        return entries(authentication, NavEntry.Section.ADMINISTRATION);
    }

    /** This console's own screens, ahead of anything contributed - so the product's own objects lead the bar. */
    private static final List<NavEntry> OWN = List.of(
            new NavEntry("Overview", "/console"),
            new NavEntry("Installed providers", "/catalog", NavEntry.Access.ADMIN,
                    NavEntry.Section.ADMINISTRATION));

    private List<NavEntry> entries(Authentication authentication, NavEntry.Section section) {
        boolean admin = authority(authentication, "ROLE_ADMIN");
        return Stream.concat(
                        OWN.stream(),
                        ConsoleModuleProvider.enabled(Features.namespaced(environment::getProperty)).stream()
                                .flatMap(module -> module.navEntries().stream()))
                .filter(entry -> entry.section() == section)
                .filter(entry -> permitted(entry, authentication != null, admin))
                .toList();
    }

    /**
     * Whether this user clears the entry's access floor. This console is single-tenant and has two tiers, so
     * {@code SUPERADMIN} resolves to the same authority as {@code ADMIN}: with one tenant, "administers this
     * deployment" and "administers the tenant" name the same person, and hiding such a link instead would leave a
     * module's own administration screen unreachable on the very deployment that installed it.
     */
    private static boolean permitted(NavEntry entry, boolean authenticated, boolean admin) {
        return switch (entry.access()) {
            case USER -> authenticated;
            case ADMIN, SUPERADMIN -> admin;
        };
    }

    private static boolean authority(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> role.equals(granted.getAuthority()));
    }
}
