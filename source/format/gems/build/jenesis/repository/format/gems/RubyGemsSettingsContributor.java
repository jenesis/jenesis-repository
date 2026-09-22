package build.jenesis.repository.format.gems;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * The RubyGems format's own dials, so they appear in the settings screen and the generated reference exactly when
 * an image ships the format. One today: where a proxied gem's Sigstore attestations are fetched from, read off the
 * exchange by {@link RubyGemsFormat#companions} on every fill.
 */
public final class RubyGemsSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(RubyGemsFormat.ATTESTATIONS_URL, "RubyGems", "Attestations API base",
                        "Where the Sigstore attestations of a proxied gem are fetched from, the base of an API "
                                + "answering <base>/<name>-<version>.json with an array of bundles. Empty by "
                                + "default, which reads /api/v1/attestations/ under the proxied upstream - what "
                                + "rubygems.org serves. A mirror without the API answers 404, which is absence "
                                + "rather than a failure. Applies on the next restart.",
                        Setting.Kind.STRING, "", false));
    }
}
