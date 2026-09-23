package build.jenesis.repository.application;

import module java.base;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Fails a boot fast, before Spring binds {@code jenreg.*}, if the environment still carries one of the
 * superseded config keys - so an operator sees the key it must rename rather than the cryptic binding error
 * (or, worse, a silent rebind to the schema's semantics that would flip pull-through or drop repository
 * definitions without a sound). This distribution now <em>extends</em> the {@code jenreg.repository} schema
 * instead of redefining it, so three keys moved:
 *
 * <ul>
 *   <li>{@code jenreg.proxy} (the boolean pull-through switch) is now {@code jenreg.proxy-enabled}
 *       - the plain {@code proxy} key belongs to the core's {@code jenreg.proxy.<format>} per-format
 *       upstream map, so a scalar {@code proxy} value can only be the old switch.</li>
 *   <li>{@code jenreg.repository.<name>} (the map of repository definitions) is now
 *       {@code jenreg.repositories.<name>}. A scalar {@code jenreg.repository} used to hold the fixed-space name,
 *       which is why the map was the one that had to be renamed; that scalar has since gone, and a
 *       {@code repository.<name>} sub-key still binds to nothing, so it is still worth refusing by name.</li>
 *   <li>{@code jenreg.format-proxy-upstream.<format>} (the deploy-time per-format upstream) folded into the
 *       free core's {@code jenreg.proxy.<format>} map (the boot default; {@code format-upstream.<format>}
 *       stays the live setting override).</li>
 * </ul>
 *
 * <p>Runs once the environment is prepared, the same lifecycle point as
 * {@link build.jenesis.repository.server.kernel.SettingsEnvironmentLayer}, so a stored
 * runtime setting layered under a legacy key is caught too. Only this composition's keys are probed - the
 * {@code proxy} map itself stands unchanged and is never rejected.
 */
public final class LegacyPropertyProbe implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        check(event.getEnvironment());
    }

    /** Throws {@link IllegalStateException} naming every legacy key present and its replacement; a no-op when the
     *  environment is clean. Public so the schema guard can exercise it without a full boot. */
    public static void check(ConfigurableEnvironment environment) {
        List<String> problems = new ArrayList<>();
        // The scalar switch: a plain jenreg.proxy value (a map jenreg.proxy.<format> leaves the
        // bare key unresolved, so getProperty here is non-null only for the old boolean form). getProperty is
        // relaxed-binding aware for the OS environment, so JENREG_PROXY is caught as well.
        if (environment.getProperty("jenreg.proxy") != null) {
            problems.add("'jenreg.proxy' (the pull-through switch) is now 'jenreg.proxy-enabled'"
                    + " - the plain 'jenreg.proxy.<format>' map now names the core's per-format upstreams");
        }
        // The sub-keyed forms: scan the enumerable sources for any key under the old map prefixes, matching both the
        // dotted form (system properties, application.properties, -D) and the relaxed UPPER_UNDERSCORE form (env vars).
        Set<String> repositoryMapKeys = new TreeSet<>();
        Set<String> formatUpstreamKeys = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (matchesPrefix(name, "jenreg.repository.", "JENREG_REPOSITORY_")) {
                    repositoryMapKeys.add(name);
                } else if (matchesPrefix(name, "jenreg.format-proxy-upstream.",
                        "JENREG_FORMAT_PROXY_UPSTREAM_")) {
                    formatUpstreamKeys.add(name);
                }
            }
        }
        if (!repositoryMapKeys.isEmpty()) {
            problems.add("'jenreg.repository.<name>' (the map of repository definitions) is now"
                    + " 'jenreg.repositories.<name>' - found " + repositoryMapKeys);
        }
        if (!formatUpstreamKeys.isEmpty()) {
            problems.add("'jenreg.format-proxy-upstream.<format>' folded into the core's"
                    + " 'jenreg.proxy.<format>' map (a 'format-upstream.<format>' setting stays the live"
                    + " override) - found " + formatUpstreamKeys);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Legacy jenreg.* configuration keys (renamed in); update "
                    + "each and restart:\n  - " + String.join("\n  - ", problems));
        }
    }

    private static boolean matchesPrefix(String name, String dotted, String relaxed) {
        // A trailing segment must follow the prefix (a sub-key), so the scalar keys jenreg.repository
        // and the exact prefix stem are never matched.
        return (name.startsWith(dotted) && name.length() > dotted.length())
                || (name.startsWith(relaxed) && name.length() > relaxed.length());
    }
}
