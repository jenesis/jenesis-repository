package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * The {@link HoldReleaseObserver} for a copy a hardened proxy held: a reviewer's release turns the digest-pinned
 * verdict recorded over the held bytes ({@link VerdictSection}) into an {@link Verdict#ALLOW} over those same bytes,
 * reached under the policy that held them. The hardened leg serves a local copy only on a recorded {@code ALLOW} that
 * pins it and re-screens anything else, so without this the first request after a release would re-screen the
 * released bytes, reach the verdict that held them and hold them again. A later change of policy still re-screens
 * them, as it re-screens every verdict reached under another policy.
 *
 * <p>A no-op for a path no hardened leg recorded a verdict for, for a verdict over other bytes than the held ones,
 * and for one already an {@code ALLOW}; a discard leaves the record as it is, since nothing serves it afterwards.
 */
public final class HardenedReleaseObserver implements HoldReleaseObserver {

    @Override
    public void onReleased(ArtifactStore store, String path) throws IOException {
        Optional<String> held = new Publication(store).blob(Publication.QUARANTINE_PATH + path);
        if (held.isEmpty()) {
            return;
        }
        HardenedScreen.Coordinate coordinate = HardenedScreen.coordinate(path);
        MetadataStore metadata = MetadataProvider.installed().over(store);
        Optional<VerdictSection.Recorded> recorded = VerdictSection.recorded(metadata.section(coordinate.ecosystem(),
                coordinate.coordinate(), coordinate.version(), VerdictSection.TAG));
        if (recorded.isEmpty() || recorded.get().verdict() == Verdict.ALLOW || !recorded.get().pins(held.get())) {
            return;
        }
        VerdictSection.Recorded prior = recorded.get();
        metadata.mutate(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(), VerdictSection.TAG,
                VerdictSection.record(held.get(), Verdict.ALLOW, null, prior.profile(), prior.source(),
                        prior.validators(), Instant.now(), prior.inspectionLimit(), prior.policy()));
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) {
    }
}
