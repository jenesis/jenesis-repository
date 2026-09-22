package build.jenesis.repository.settings;

import module java.base;

/**
 * The decisions a new deployment is guided through on its first run - the one definition the console's setup
 * screen ({@code /setup}), the API ({@code GET /api/setup}) and the CLI ({@code jenesis-repo setup}) render, so
 * the three surfaces walk the same list in the same order and a step added here appears on all three.
 *
 * <p>A step names the settings it is about and says in a sentence why they are worth deciding now; it never
 * restates what a setting does or what a value implies. That prose belongs to the setting's own
 * {@link Setting#description() description}, which the catalogue carries once and every surface renders - the
 * settings screen, the generated reference, the CLI's listing and this guide alike - so an implication written
 * into a step would be a second copy that drifts. A step whose key the deployment's catalogue does not carry
 * (its module is not on the image) is rendered without it, never invented.
 *
 * <p>The first step has no setting at all: the starter credentials are environment secrets a deployment is
 * provisioned with, not dials in the store, and the step says what to do about them.
 */
public final class FirstRunSteps {

    /** One guided decision: a stable id, a title, a sentence on why it is asked now, and the keys it is about. */
    public record Step(String id, String title, String why, List<String> keys) {

        public Step {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(why, "why");
            keys = List.copyOf(keys);
        }
    }

    /** The step that is about the starter credentials rather than a setting; surfaces render it from the
     *  deployment's environment rather than the catalogue. */
    public static final String STARTER_CREDENTIAL = "starter-credential";

    /** The step that is about this guide itself - the dial that switches the first-run redirect off. */
    public static final String THE_WIZARD = "wizard";

    public static final List<Step> ALL = List.of(
            new Step(STARTER_CREDENTIAL, "Stop using the starter credential",
                    "The console's starter key (jenreg.ui.admin-key) and the API's bootstrap key (jenreg.bootstrap-key) "
                            + "are secrets a deployment is provisioned with, re-provisioned on every boot for as long "
                            + "as they are set. Grant a real administrator and issue a real credential, then unset "
                            + "both; removing an id from jenreg.ui.admins does not revoke the grant it seeded.",
                    List.of()),
            new Step("vulnerabilities", "Vulnerability handling",
                    "What happens to an artifact whose advisories reach the threshold - decided before the first "
                            + "publish, because the default refuses and stores nothing.",
                    List.of("vulnerability-threshold", "vulnerability-action")),
            new Step("malware", "Malware handling",
                    "What happens to a package a curated malicious-package record names.",
                    List.of("malware-action")),
            new Step("licences", "Licence handling",
                    "Which licences this deployment admits, and what an undeclared or unlisted one means.",
                    List.of("license-allowed", "license-denied", "license-unknown", "license-disallowed")),
            new Step("immaturity", "Withholding new releases",
                    "How long a version an upstream has only just published is held before this deployment serves "
                            + "it on.",
                    List.of("immaturity-hold-days")),
            new Step("feeds", "Advisory feeds",
                    "Which public advisory sources this deployment consults. Every one is off until it is switched "
                            + "on, and the ones that gate fail closed.",
                    List.of("osv", "kev", "openssf", "epss", "scorecard")),
            new Step("retention", "Retention and collection cadence",
                    "What is kept, what is reclaimed, and how often the store is walked to find out.",
                    List.of("gc", "collect", "keep-last", "max-age", "walks")),
            new Step(THE_WIZARD, "This guide",
                    "Whether a super-admin signing in with the starter key is sent here first. Switch it off once for "
                            + "a deployment provisioned from configuration; the screen stays reachable from the "
                            + "Administration menu.",
                    List.of("setup-wizard")));

    private FirstRunSteps() {
    }

    /** The step with this id, or empty. */
    public static Optional<Step> step(String id) {
        return ALL.stream().filter(step -> step.id().equals(id)).findFirst();
    }
}
