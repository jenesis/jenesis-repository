package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.settings.Setting;

/**
 * The first-run guided-hardening step (audit P4). A fresh deploy already boots with a non-empty secure
 * floor - per-credential authorization on, the CVSS gate at {@code CRITICAL}, the public advisory feeds on, a sane
 * rate ceiling and a short immaturity hold - but the dials that need a <em>deployment-specific</em> answer stay at
 * their open default, because there is no universal secure value for them: which coordinates carry a version floor,
 * which policy-as-code rules apply, which namespaces the tenant reserves privately, what health floor to gate on.
 * These cannot be floored; they must be <em>guided</em>. On a genuinely fresh deploy (no runtime configuration
 * persisted yet) this walks the operator through tightening them.
 *
 * <p><strong>How it fits the existing mechanism (no parallel machinery).</strong> "Fresh deploy" is read the same way
 * the demo seeder reads an empty artifact space: through the store-backed {@link Settings}. A deployment that has never
 * been configured holds no {@code config/settings} document, so {@link Settings#overrides()} is empty; the first stored
 * override (through {@code /api/settings}, the console or the CLI) makes it non-empty and the guidance falls silent -
 * so a configured deploy is never re-nagged. The still-open dials are discovered by fanning out over the settings SPI
 * ({@code SettingsContributor.all()}), never by naming {@code version-floor} / {@code policy-rules} /
 * {@code private-names} / {@code scorecard-floor} by hand: a dial is an <em>inert gate dimension</em>
 * when it is a per-tenant, free-form compliance rule that ships blank and no operator (nor an environment pin) has set
 * it - installed, but defining no rule, so it gates nothing until configured. Reading only, writing nothing: the
 * guidance renders the current posture and never mutates a setting on the operator's behalf (there is no safe universal
 * value to write), so it is idempotent and honours the reads-render/writes-refresh contract (§10) and the
 * prefer-immutability, no-hidden-mutation rule (§11).
 *
 * <p>The advice is surfaced both as a one-time boot log (the proactive nudge an operator sees on a fresh
 * {@code docker run}, mirroring the loud auth-disabled boot warning) and, recomputed live, on {@code /api/config} (so
 * the console, CLI or a script can render and act on it) - each computed from the same {@link #assess} so both agree
 * and both stop the moment the deployment is configured.
 */
public final class FirstRunHardening {

    private FirstRunHardening() {
    }

    /** General hardening next-steps that are not a single discovered setting: enabling the credentialed advisory feeds
     *  (whose credential requirement is not modelled on the settings SPI, so they are pointed at rather than
     *  enumerated as inert dimensions) and setting a deployment-wide deny list (a global dial, not a per-tenant gate
     *  dimension). Held as guidance content, not a plugin fan-out. */
    static final List<String> NEXT_STEPS = List.of(
            "Enable the additional credentialed advisory feeds you hold tokens for (GitHub, Snyk, VulnCheck, VulnDB, "
                    + "Mend, Socket) on the settings screen or over /api/settings - and decide the public no-credential "
                    + "feeds (OSV, CISA KEV, OpenSSF, EPSS, deps.dev), which are opt-in too: the first-run setup guide "
                    + "(/setup on the console, jenesis-repo setup on the CLI) walks them with the rest.",
            "Set a deployment-wide deny-list for any coordinates you never want served, however they arrive.");

    /** One recommended tightening step: a discovered setting's key, its human label and its description (the "how"). */
    public record Step(String key, String label, String detail) {
    }

    /**
     * The guided-hardening advice for a deployment. {@code firstRun} is whether this is a genuinely fresh deploy (no
     * runtime configuration persisted); when it is not, {@code dimensions} and {@code nextSteps} are empty, so an
     * already-configured deployment is never re-nagged. {@code dimensions} are the installed-but-inert per-tenant gate
     * dimensions discovered on the settings catalogue; {@code nextSteps} are the general hardening pointers.
     */
    public record Advice(boolean firstRun, List<Step> dimensions, List<String> nextSteps) {
        public Advice {
            dimensions = List.copyOf(dimensions);
            nextSteps = List.copyOf(nextSteps);
        }

        /** Whether there is any guidance to surface: a fresh deploy that still has an open dial or a general step. */
        public boolean hasGuidance() {
            return firstRun && !(dimensions.isEmpty() && nextSteps.isEmpty());
        }
    }

    /** Whether this is a genuinely fresh deploy: no deployment-wide runtime configuration persisted yet. The first
     *  stored override flips this false, so the guidance fires only on first run and never re-nags a configured
     *  deployment - read through the same store-backed {@link Settings} the rest of the runtime config uses. */
    public static boolean firstRun(Settings settings) {
        return settings.overrides().isEmpty();
    }

    /**
     * The guided-hardening advice: empty when {@code firstRun} is false, otherwise every inert gate dimension on the
     * catalogue plus the general next steps. {@code catalogue} is the discovered settings ({@code SettingsContributor
     * .all()}); {@code effective} answers a key's effective value (a stored override or an environment pin, blank/null
     * when neither is set) so a dial an operator has already pinned from the environment is not recommended.
     */
    public static Advice assess(boolean firstRun, List<Setting> catalogue, UnaryOperator<String> effective) {
        if (!firstRun) {
            return new Advice(false, List.of(), List.of());
        }
        List<Step> dimensions = new ArrayList<>();
        for (Setting setting : catalogue) {
            if (inertGateDimension(setting, effective)) {
                dimensions.add(new Step(setting.key(), setting.label(), setting.description()));
            }
        }
        return new Advice(true, dimensions, NEXT_STEPS);
    }

    /**
     * Whether a discovered setting is an inert gate dimension worth guiding an operator to configure: a per-tenant
     * ({@link Setting.Scope#TENANT}), free-form ({@link Setting.Kind#STRING}) compliance rule that ships blank and that
     * neither a stored override nor an environment pin has set. Such a setting is installed (it is on the catalogue
     * only because its module is on the module path) but defines no rule, so it screens nothing until configured -
     * exactly the {@code version-floor} / {@code policy-rules} / {@code private-names} / {@code scorecard-floor}
     * dimensions, discovered structurally rather than named by hand. The paired {@code *-action} settings are
     * {@link Setting.Kind#CHOICE} with a non-blank default and so are correctly passed over, as are the deployment-wide
     * feed toggles and thresholds.
     */
    private static boolean inertGateDimension(Setting setting, UnaryOperator<String> effective) {
        if (!setting.tenantOverridable()
                || setting.kind() != Setting.Kind.STRING
                || !setting.defaultValue().isBlank()) {
            return false;
        }
        String value = effective.apply(setting.key());
        return value == null || value.isBlank();
    }
}
