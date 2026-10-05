package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;

/**
 * Discovers the OSV feed: enabled by {@code osv}, pointed at {@code osv-endpoint} (default
 * {@code https://api.osv.dev}). Off unless an operator turns it on with {@code jenrepo.osv=true}: a lookup is an
 * outbound call to a public API, which a deployment that configured nothing has not agreed to - a deliberate exception
 * to the "unset means on" gate.
 */
public final class OsvAdvisorySourceProvider implements SignalSourceProvider {

    @Override
    public String name() {
        return "osv";
    }

    @Override
    public Set<Class<? extends SignalSource>> signals() {
        return Set.of(AdvisorySource.class);
    }

    @Override
    public Optional<SignalSource> create(SignalContext context) {
        if (!context.enabled("osv", false)) {
            return Optional.empty();
        }
        String endpoint = context.setting("osv-endpoint");
        String export = context.setting("osv-export");
        // The signal space is asked for when the change log is drawn or read, not here: a source built where no
        // deployment bound a root still screens.
        return Optional.of(OsvAdvisorySource.over(
                URI.create(endpoint == null || endpoint.isBlank() ? "https://api.osv.dev" : endpoint),
                export == null || export.isBlank() ? OsvAdvisorySource.DEFAULT_EXPORT : URI.create(export),
                context::snapshots, context.clock()));
    }
}
