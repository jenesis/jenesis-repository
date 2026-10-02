package build.jenesis.repository.compliance.admission;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the admission dimension's settings, so they surface exactly when this module is installed. All are
 * {@link Setting.Scope#TENANT} gate-policy dials: each tenant configures its own trust anchor and expected builder and
 * source over the deployment default.
 */
public final class AttestationSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("provenance-admission-key", "Compliance", "Provenance trust anchor",
                        "The PEM public keys, RSA or EC, an inbound attestation's DSSE signature must verify against - "
                                + "the trusted builder keys. Empty disables inbound attestation admission: there is no "
                                + "anchor to verify a signature against, so an artifact's builder / source claims "
                                + "would be self-asserted.",
                        Setting.Kind.SECRET, "", true, Setting.Scope.TENANT).standard(),
                new Setting("provenance-admission-builder", "Compliance", "Expected builder",
                        "Comma-separated builder identities an inbound attestation must name - the workflow that built "
                                + "it, at its ref. A trailing * matches a prefix. Empty accepts any builder whose "
                                + "signature verifies.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).standard(),
                new Setting("provenance-admission-source", "Compliance", "Expected source",
                        "Comma-separated source repository URIs an inbound attestation's provenance must have built "
                                + "from. A trailing * matches a prefix. Empty accepts any source.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).standard(),
                new Setting("provenance-admission-action", "Compliance", "Provenance-admission action",
                        "Verdict for an artifact whose inbound attestation fails verification - unsigned by a trusted "
                                + "key, signed for a different artifact, or an unexpected builder or source: holding "
                                + "it for review, refusing admission outright, or admitting it, which still runs the "
                                + "dimension and simply raises nothing. To stop checking, clear the trust anchor or "
                                + "switch the module off.",
                        Setting.Kind.CHOICE, List.of("ALLOW", "QUARANTINE", "REJECT"), "QUARANTINE", true,
                        Setting.Scope.TENANT).standard().named(Setting.Choice.VERDICTS));
    }
}
