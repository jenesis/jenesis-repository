package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes the origins trusted to write from another site, read live by the {@link CrossSiteWriteFilter}. */
public final class CrossSiteSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(CrossSiteWriteFilter.KEY, "Network", "Sites trusted to write",
                        "Origins a browser may send a write from although they are not this deployment's own - "
                                + "comma-separated, each as a browser sends it: scheme, host and any port. A browser "
                                + "attaches credentials it holds for this host to a request any page makes, so a write "
                                + "marked as coming from another site is refused - a sibling subdomain included, since "
                                + "under a shared parent domain it may be another application or a user-content host. "
                                + "Name here only the origins whose pages you control. Build tools send no such "
                                + "marking and are never affected. Empty trusts none. Applies live.",
                        Setting.Kind.STRING, "", true).advanced());
    }
}
