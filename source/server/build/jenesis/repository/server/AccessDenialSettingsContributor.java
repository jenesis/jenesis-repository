package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes what a caller without access is answered, read live by every surface through {@link AccessDenial}. */
public final class AccessDenialSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(AccessDenial.KEY, "Access", "Answer to a caller without access",
                        "What a request for a tenant, repository or artifact the caller may not reach is answered, on "
                                + "every surface. Not found answers 404, exactly as for a name that does not exist, so "
                                + "nobody can learn what exists by probing names. Forbidden answers 403, telling a "
                                + "caller that their credential does not reach the name, whether or not it exists. A "
                                + "request with no credential is answered 401 with its challenge either way. Applies "
                                + "live.",
                        Setting.Kind.CHOICE, List.of(AccessDenial.NOT_FOUND_VALUE, AccessDenial.FORBIDDEN_VALUE),
                        AccessDenial.DEFAULT, true).advanced());
    }
}
