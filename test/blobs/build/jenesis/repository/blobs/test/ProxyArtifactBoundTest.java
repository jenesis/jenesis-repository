package build.jenesis.repository.blobs.test;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.ProxyArtifactSettingsContributor;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxied artifact is bounded: an upstream answering with more bytes than the bound is refused as a digest mismatch
 * is, nothing linked and nothing served, so an endless body cannot fill the store, while one within it is cached.
 */
class ProxyArtifactBoundTest {

    private static final URI ARTIFACT = URI.create("https://upstream.example/lib-1.0.jar");

    @TempDir
    Path root;

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    void a_body_past_the_bound_is_refused_and_one_within_it_cached() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        Blobs blobs = new Blobs(store);
        Features.configure(Map.of("jenrepo." + ProxyArtifactSettingsContributor.LIMIT_KEY, "1024")::get);

        assertThat(ProxyRelay.fill(blobs, "publish/lib-2.0.jar", ARTIFACT, new ByteArrayInputStream(new byte[1025]),
                ProxyRelay.Declared.NONE)).as("one byte past the bound").isFalse();
        assertThat(store.exists("publish/lib-2.0.jar")).as("nothing linked").isFalse();

        assertThat(ProxyRelay.fill(blobs, "publish/lib-1.0.jar", ARTIFACT, new ByteArrayInputStream(new byte[1024]),
                ProxyRelay.Declared.NONE)).as("at the bound").isTrue();
        assertThat(store.exists("publish/lib-1.0.jar")).isTrue();
    }
}
