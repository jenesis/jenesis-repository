package build.jenesis.repository.format.contract.test;

import build.jenesis.repository.format.testkit.ContractHold;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * The screen the withhold leg holds a {@code publish/}-namespace version with.
 *
 * <p>A {@code blobs/}-namespace format (OCI here) is retracted by the content-addressed {@code withheld/<hash>} marker,
 * which any test can write. A {@code publish/}-namespace format (Maven, the Jenesis module layout, raw) is retracted by
 * the interceptor chain answering {@code withheld} - the seam a downstream {@code ComplianceScreen} implements and the
 * core ships deliberately empty - so with no discovered screen there is no way to hold a Maven, Jenesis or raw
 * version at all, and the withhold-on-enumeration clause would be untestable for three of the four free formats.
 *
 * <p>It is a two-line delegation: the convention (the key shape, the per-store scoping, the idempotency) lives once in
 * the testkit's {@code ContractHold}, so a downstream fixture holding a {@code publish/} path uses the same one. It
 * overrides only {@code withheld}, leaving {@code assess} at its {@code ACCEPT} default, so it is completely inert for
 * every path no check has explicitly held - including every path in every other check in this module.
 */
public final class ContractHoldInterceptor implements PublishInterceptor {

    @Override
    public boolean withheld(String path, ArtifactStore store) {
        return ContractHold.is(store, path);
    }
}
