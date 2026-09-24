package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;

/**
 * Discovers the OSV (osv.dev) feed: enabled by the {@code osv} setting, pointed at {@code osv-endpoint} (default
 * {@code https://api.osv.dev}). Off unless an operator turns it on: a lookup is an outbound call to a public API, and a
 * deployment that configured nothing has not agreed to make one. That deviation from the usual "unset means on"
 * gate is deliberate and is stated once, on {@code FeatureConventionTest}.
 *
 * <p>It was briefly documented as on by default, in this javadoc, in its {@code Setting} and in the generated
 * settings reference, because a defaults map on the plain server's launcher switched it on. The shipped image never
 * ran that launcher, so no deployment ever had it on by default and the documentation described a posture that did
 * not exist. Both now say the same thing: {@code jenreg.osv=true} turns it on.
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
        return Optional.of(OsvAdvisorySource.over(URI.create(
                endpoint == null || endpoint.isBlank() ? "https://api.osv.dev" : endpoint), context.clock()));
    }
}
