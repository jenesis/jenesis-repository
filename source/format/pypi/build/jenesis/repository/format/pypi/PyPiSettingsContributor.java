package build.jenesis.repository.format.pypi;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * The PyPI format's own dials, so they appear in the settings screen and the generated reference exactly when an
 * image ships the format. One today: where a proxied distribution's PEP 740 provenance is fetched from, read off the
 * exchange by {@link PyPiFormat#companions} on every fill.
 */
public final class PyPiSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(PyPiFormat.PROVENANCE_URL, "PyPI", "Provenance API base",
                        "Where the provenance document (PEP 740) of a proxied distribution is fetched from, the "
                                + "base of an integrity API answering <base>/<project>/<version>/<file>/provenance. "
                                + "Empty by default, which reads the proxied index's own origin - pypi.org serves "
                                + "it at https://pypi.org/integrity/. A mirror without an integrity API answers "
                                + "404, which is absence rather than a failure. Applies on the next restart.",
                        Setting.Kind.STRING, "", false));
    }
}
