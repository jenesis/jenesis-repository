package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;
import build.jenesis.repository.store.Durations;

/**
 * Discovers the OSV mirror: switched on by {@code osv-mirror}, which is off until an operator turns it on, drawing
 * OSV's export at {@code osv-export} and rebuilding each copy after {@code osv-mirror-rebuild}. On, it keeps nothing
 * until a repository - or the deployment, for every repository - names it in {@value AdvisorySource#SELECTION}, since a
 * blank selection never takes a mirror; it then keeps the ecosystems those repositories hold.
 */
public final class OsvMirrorSourceProvider implements SignalSourceProvider {

    @Override
    public String name() {
        return OsvMirrorSource.FEED;
    }

    @Override
    public Set<Class<? extends SignalSource>> signals() {
        return Set.of(AdvisorySource.class);
    }

    @Override
    public Optional<SignalSource> create(SignalContext context) {
        if (!context.enabled(OsvMirrorSource.FEED, false)) {
            return Optional.empty();
        }
        String export = context.setting("osv-export");
        return Optional.of(OsvMirrorSource.over(
                export == null || export.isBlank() ? OsvAdvisorySource.DEFAULT_EXPORT : URI.create(export),
                context::snapshots, context.clock(),
                () -> Durations.dial(context.setting(OsvSettingsContributor.MIRROR_REBUILD),
                        OsvSettingsContributor.MIRROR_REBUILD, OsvMirrorSource.DEFAULT_REBUILD)));
    }
}
