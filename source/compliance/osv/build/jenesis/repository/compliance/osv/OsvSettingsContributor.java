package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes the OSV feed's settings, so they appear exactly when this module is installed. */
public final class OsvSettingsContributor implements SettingsContributor {

    /** How often the mirror draws an ecosystem's export whole again. */
    static final String MIRROR_REBUILD = "osv-mirror-rebuild";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("osv", "Compliance", "OSV feed",
                        "The OSV vulnerability database (osv.dev), gathering each ecosystem's own advisory databases: "
                                + "the advisories affecting each package version, with severity and fixed versions, "
                                + "and malicious-package records. The gate checks each copy a proxy caches against "
                                + "them, the scheduled scan re-checks what is cached, and the packages whose records "
                                + "OSV changed are checked again soon after. A lookup is an outbound call to a public "
                                + "API. While it is on and unreachable, a copy it would have screened is decided by "
                                + "the repository's screening mode.",
                        Setting.Kind.BOOLEAN, "false", true).essential(),
                new Setting("osv-endpoint", "Compliance", "OSV endpoint",
                        "The OSV API base URL, for a mirror or a proxy.",
                        Setting.Kind.URI, "https://api.osv.dev", true).advanced(),
                new Setting("osv-export", "Compliance", "OSV export",
                        "Where OSV publishes its export, whose per-ecosystem change lists say which records changed "
                                + "and when, for a mirror or a proxy.",
                        Setting.Kind.URI, "https://osv-vulnerabilities.storage.googleapis.com/", true).advanced(),
                new Setting(OsvMirrorSource.FEED, "Compliance", "OSV mirror",
                        "A local copy of the OSV database, drawn from its export for the ecosystems the repositories "
                                + "selecting it hold, and read instead of asking OSV about each version: screening "
                                + "needs no outbound call, and a scan costs no request per version. On, it keeps "
                                + "nothing until a repository - or the deployment, for every repository - names "
                                + "osv-mirror in its advisory feeds, which a repository naming none never does. Each "
                                + "ecosystem's first copy is its whole export, hundreds of megabytes for the largest; "
                                + "until it lands, a copy it would screen is decided by the repository's screening "
                                + "mode.",
                        Setting.Kind.BOOLEAN, "false", true).essential(),
                new Setting(MIRROR_REBUILD, "Compliance", "OSV mirror rebuild",
                        "How often the mirror draws each ecosystem's export whole again; between, it follows OSV's "
                                + "change lists record by record. 0 or off draws one again only when its change "
                                + "list cannot be followed.",
                        Setting.Kind.DURATION, "P7D", true).advanced());
    }
}
