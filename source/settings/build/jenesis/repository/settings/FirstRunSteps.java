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
 * <p>Three steps have no setting at all, and {@link #KEYLESS} names them. The starter credentials are environment
 * secrets a deployment is provisioned with, not dials in the store; a repository and an upstream are objects a
 * deployment creates rather than values it sets. Each step says what to do about its subject, and a surface may add a
 * way to do it - the console links the screen that creates each.
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

    /** The step that is about creating the first repository. */
    public static final String REPOSITORY = "repository";

    /** The step that is about naming the first upstream. */
    public static final String UPSTREAM = "upstream";

    /** The steps that are about something other than a setting, so they name no keys. */
    public static final Set<String> KEYLESS = Set.of(STARTER_CREDENTIAL, REPOSITORY, UPSTREAM);

    public static final List<Step> ALL = List.of(
            new Step(STARTER_CREDENTIAL, "Stop using the starter credential",
                    "A new deployment is first signed in to with the one-time key its start printed, which stops "
                            + "working after an hour or as soon as an administrator exists. The console's starter key "
                            + "(jenreg.ui.admin-key) and the API's bootstrap key (jenreg.bootstrap-key) are secrets a "
                            + "deployment is provisioned with, re-provisioned on every boot for as long as they are "
                            + "set. Grant a real administrator and issue a real credential, then unset both; removing "
                            + "an id from jenreg.ui.admins does not revoke the grant it seeded.",
                    List.of()),
            new Step(REPOSITORY, "Create a repository",
                    "A deployment serves nothing until it has a repository to publish into or to proxy through. "
                            + "A repository is created with a name and the format it holds, and every URL names it.",
                    List.of()),
            new Step(UPSTREAM, "Name an upstream",
                    "Nothing is fetched from a public registry until an upstream is named for its format, so a "
                            + "deployment that proxies names one before its first client asks: the "
                            + "format-upstream.<format> setting, which every surface edits.",
                    List.of()),
            new Step("feeds", "Advisory feeds",
                    "Which public advisory sources this deployment consults. Every one is off until it is switched "
                            + "on, because a lookup is an outbound call; with all of them off the gate is armed and "
                            + "asks nobody, so a package with a published vulnerability is admitted without a word.",
                    List.of("osv", "github", "openssf", "kev", "epss", "scorecard")),
            new Step("vulnerabilities", "Vulnerability handling",
                    "What happens to an artifact whose advisories reach the threshold - decided before the first "
                            + "publish, because the default refuses and stores nothing.",
                    List.of("vulnerability-threshold", "vulnerability-action")),
            new Step("malware", "Malware handling",
                    "What happens to a package a curated malicious-package record names.",
                    List.of("malware-action")),
            new Step("notifications", "Being told",
                    "Where this deployment reports what the gate decided. Without an endpoint an operator learns of "
                            + "a hold only by opening the review queue, or from the publisher whose build failed.",
                    List.of("webhook", "webhook-endpoints", "webhook-secrets")),
            new Step("licences", "Licence handling",
                    "Which licences this deployment admits, and what an undeclared or unlisted one means.",
                    List.of("license-allowed", "license-denied", "license-unknown", "license-disallowed")),
            new Step("immaturity", "Withholding new releases",
                    "How long a version an upstream has only just published is held before this deployment serves "
                            + "it on.",
                    List.of("immaturity-hold-days")),
            new Step("retention", "Retention and collection cadence",
                    "What is kept, what is reclaimed, and how often the store is walked to find out.",
                    List.of("gc", "collect", "keep-last", "max-age", "walks")),
            new Step(THE_WIZARD, "This guide",
                    "Whether a super-admin signing in with the starter key is sent here first. Switch it off once for "
                            + "a deployment provisioned from configuration; the screen stays reachable as First-run "
                            + "setup, under Settings.",
                    List.of("setup-wizard")));

    private FirstRunSteps() {
    }

    /** The step with this id, or empty. */
    public static Optional<Step> step(String id) {
        return ALL.stream().filter(step -> step.id().equals(id)).findFirst();
    }
}
