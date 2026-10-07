package build.jenesis.repository.discovery;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Surfaces discovery's one dial: how long a domain's file is remembered ({@value #TTL}). */
public final class DiscoverySettingsContributor implements SettingsContributor {

    /** How long a domain's discovery file, or its absence, is remembered on a node. */
    public static final String TTL = "discovery-ttl";

    /** {@link #TTL}'s default, {@link RepositoryDiscovery#DEFAULT_TTL} as the catalogue spells it. */
    public static final String TTL_DEFAULT = "PT1H";

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(TTL, "Discovery", "Discovery file memory",
                "How long a node remembers what a domain's /.well-known/java-repository.properties said - or that it "
                        + "has none - before a discovered leg asks the domain again. Each domain is asked once per "
                        + "period however many requests name it, so a longer period costs the domains less and "
                        + "notices a changed file later.",
                Setting.Kind.DURATION, TTL_DEFAULT, true, Setting.Scope.GLOBAL).advanced());
    }
}
