package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the demo-mode flag on the settings screens, API and CLI. When on, a post-boot background pass seeds a
 * completely empty repository with real artifacts through the formats' own pull-through paths - an evaluator's
 * browse, vulnerability and quarantine surfaces fill with data, and turning it on layers in a small demo gate config
 * (one deny-listed coordinate and a version floor) so the QUARANTINE/REJECT surfaces carry examples too. Restart-bound
 * ({@code live=false}, the honest {@code restart} badge): the seed runs once at boot and only against an empty space,
 * so a live toggle would not re-seed a repository already populated - and switching it on in a used deployment is a
 * harmless no-op the guard refuses.
 */
public final class DemoSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("demo", "Operations", "Demo seeding",
                        "Seed a fresh, completely empty repository with real artifacts (including old, "
                                + "benign-but-vulnerable coordinates like log4j-core 2.14.1 and lodash 4.17.11) so an "
                                + "evaluator has data to look at - pulled through the formats' own upstreams, screened "
                                + "by the compliance gate, with a small demo gate config applied. Off by default; a "
                                + "non-empty repository is never seeded, so this is a no-op in production. Applies on "
                                + "the next restart.",
                        Setting.Kind.BOOLEAN, "false", false));
    }
}
