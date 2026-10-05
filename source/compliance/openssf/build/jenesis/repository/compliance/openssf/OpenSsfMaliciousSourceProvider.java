package build.jenesis.repository.compliance.openssf;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;

/**
 * Discovers the curated OpenSSF malicious-packages feed: enabled by the {@code openssf} setting, pointed at
 * {@code openssf-endpoint} (default {@code https://api.osv.dev}, the API serving the dataset - a mirror or a proxy
 * overrides it). Off unless an operator sets {@code jenrepo.openssf=true}: a lookup is an outbound call to a public
 * API, and a deployment that configured nothing has not agreed to make one.
 */
public final class OpenSsfMaliciousSourceProvider implements SignalSourceProvider {

    @Override
    public String name() {
        return "openssf";
    }

    @Override
    public Set<Class<? extends SignalSource>> signals() {
        return Set.of(AdvisorySource.class);
    }

    @Override
    public Optional<SignalSource> create(SignalContext context) {
        if (!context.enabled("openssf", false)) {
            return Optional.empty();
        }
        String endpoint = context.setting("openssf-endpoint");
        // Its change lists are OSV's export, the one the OSV feed reads; the signal space is asked for when the log is
        // drawn or read, not here.
        String export = context.setting("osv-export");
        return Optional.of(OpenSsfMaliciousSource.over(URI.create(
                endpoint == null || endpoint.isBlank() ? "https://api.osv.dev" : endpoint),
                export == null || export.isBlank() ? OsvAdvisorySource.DEFAULT_EXPORT : URI.create(export),
                context::snapshots, context.clock()));
    }
}
