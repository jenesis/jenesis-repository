package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.hooks.testkit.ServedOnly;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * {@code OciHoldRecorder}: the commit-time audit that captures a held OCI manifest's replay context.
 *
 * <p><b>What the {@code reads()} leg reveals here: it consults nothing either, but for the opposite reason to the
 * staging screen.</b> That one has no verdict side; this one has no <em>read</em> side because everything its
 * {@code committed} leg needs is already on the descriptor - the stored manifest hash and the pushed media type - so
 * it writes its {@code holds/dispatch<path>} descriptor without a single store read. The kit's pairing rule then binds
 * it to {@code ACCEPT}-only verdicts, which is exactly true: {@code assess} stays the SPI default and {@code withheld}
 * stays {@code false}; it has no say in any disposition, it only records one.
 *
 * <p>And it records <em>nothing</em> for the artifacts the kit publishes, by four AND-ed preconditions - the
 * disposition must be {@code QUARANTINE}, the ecosystem must be {@code oci}, and the path must look like
 * {@code /v2/.../manifests/...}. That inertness is the point of the hook (every non-OCI publish already records its
 * own dispatch through its own edge), and it is what the empty projection states.
 */
final class OciHoldRecorderFixture implements PublicationHookFixture.Interceptor, ServedOnly {

    /** {@code QuarantineDispatch.ROOT} - the one space this hook writes, inside the gate's declared {@code holds}. */
    static final String DISPATCH = "holds/dispatch";

    @Override
    public String hook() {
        return "oci-hold-recorder";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.gate.store.OciHoldRecorder";
    }

    @Override
    public PublishInterceptor create() {
        return (PublishInterceptor) Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(DISPATCH);
    }

    @Override
    public Set<PublishInterceptor.Disposition> verdicts() {
        return Set.of(PublishInterceptor.Disposition.ACCEPT);
    }

    @Override
    public void arrange(ArtifactStore store, ArtifactDescriptor artifact, PublishInterceptor.Disposition verdict) {
        if (verdict != PublishInterceptor.Disposition.ACCEPT) {
            throw new IllegalArgumentException(hook() + " has no verdict side; it can never vote " + verdict);
        }
    }

    @Override
    public boolean arrangeWithhold(ArtifactStore store, String path) {
        return false;   // withheld() is the SPI default: this hook holds nothing back
    }

    @Override
    public List<String> reads() {
        return List.of();
    }

    /** This hook does hold state - {@link #DISPATCH} - but none of it applies to what the kit publishes: the four
     *  AND-ed preconditions in the class javadoc want a {@code QUARANTINE} disposition, the {@code oci} ecosystem and
     *  a {@code /v2/.../manifests/...} path, and the kit's generic accepted publish meets none of them. That
     *  inertness is the hook's point, so the leg holds it to the stricter claim that it wrote nowhere at all. */
    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Hooks.rows(store, DISPATCH);
    }

    @Override
    public String whyServedOnly() {
        return "the interceptor checks arrange a verdict for one subject only, and this screen records nothing for "
                + "an artifact the kit publishes unless its preconditions hold - so an unarranged variant leaves no row";
    }
}
