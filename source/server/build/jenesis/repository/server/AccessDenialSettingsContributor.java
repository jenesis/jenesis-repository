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
                        "What a request for a tenant, a repository or an artifact the caller may not reach is "
                                + "answered, on every surface - the repository and registry paths, the build cache, "
                                + "the API and the console. not-found answers 404, exactly as a name that does not "
                                + "exist, so nobody can learn which tenants, repositories or artifacts exist by "
                                + "probing names and reading the status. forbidden answers 403, which tells a caller "
                                + "that their credential does not reach the name rather than that nothing is there, "
                                + "whether or not the name exists. A request with no credential is answered 401 with "
                                + "the challenge its client needs either way. Applies live.",
                        Setting.Kind.CHOICE, List.of(AccessDenial.NOT_FOUND_VALUE, AccessDenial.FORBIDDEN_VALUE),
                        AccessDenial.DEFAULT, true).standard());
    }
}
