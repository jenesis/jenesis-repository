package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the provenance-attestation sweep's settings.
 */
public final class ProvenanceSweepSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("provenance-attestation-sweep", "Compliance", "Provenance attestation sweep",
                        "Reclaim provenance attestations whose artifact is gone. The event-driven reaper already "
                                + "removes one as its artifact is deleted; this converging pass reaches what a "
                                + "notification could not - a delete that failed transiently, a descriptor with no "
                                + "blob identity, and everything written before the reaper was installed. It walks "
                                + "the attestation space.",
                        Setting.Kind.BOOLEAN, "false", true).advanced(),
                new Setting(ProvenanceAttestationSweepProvider.INTERVAL.key(), "Compliance",
                        "Provenance attestation sweep interval",
                        "How often the attestation sweep runs.",
                        Setting.Kind.DURATION,
                        ProvenanceAttestationSweepProvider.INTERVAL.fallbackText(), true).advanced());
    }
}
