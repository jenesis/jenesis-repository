package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * A {@link WalkConsumer} that repairs a feature which is off unless configured - the shape of the forwarding repair
 * consumer, whose parent feature defaults off - registered as a {@link java.util.ServiceLoader} service in this test
 * module so {@link WalkConsumer#discovered()} can be exercised against {@link WalkConsumer#enabled()}: it is skipped
 * while {@code jenreg.gated-test-feature} is unset, because its feature's default is off, and enumerated once that
 * key is {@code true}. It does no work; the discovery is what is under test.
 */
public final class FeatureGatedWalkConsumer implements WalkConsumer {

    /** A distinctive name so its own {@code jenreg.<name>} toggle collides with nothing real. */
    public static final String NAME = "feature-gated-test-consumer";

    /** The feature this consumer rides on, off by default - the key a test switches. */
    public static final String FEATURE = "gated-test-feature";

    public FeatureGatedWalkConsumer() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled() {
        return Features.enabled(FEATURE, false);
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
    }
}
