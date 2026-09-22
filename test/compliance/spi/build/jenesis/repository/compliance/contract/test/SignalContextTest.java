package build.jenesis.repository.compliance.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signal-source creation context: the ONE thing a {@code SignalSourceProvider} is handed. These pin the
 * properties the seam exists for, over a real filesystem store rather than a fake, so the space a mirrored catalogue
 * would actually write into is the space asserted here.
 *
 * <p>The load-bearing property is <em>where</em> the durable space is. A signal source is a deployment singleton -
 * the CISA catalogue is the same public data for every tenant - so its snapshots must be deployment-global and must
 * never land inside a tenant's or a repository's subspace. The context makes that structural rather than advisory: a
 * provider is never handed a store by its caller (an {@code ArtifactStore} does not say at the type level whether it
 * is the deployment root or a tenant view of it, and most resolution sites hold only the latter), so the only store a
 * provider can reach is the deployment root the composition bound, already narrowed to that one signal's own space.
 */
class SignalContextTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_signals_snapshot_space_is_deployment_global_and_its_own() throws IOException {
        try (SignalContext.Deployment _ = SignalContext.deployment(store(), Clock.systemUTC())) {
            SignalContext kev = SignalContext.of("kev", _ -> null);
            SignalContext epss = SignalContext.of("epss", _ -> null);
            kev.snapshots().write("current", new ByteArrayInputStream("catalogue".getBytes(StandardCharsets.UTF_8)));

            // Deployment-global: beside the tenant scopes at the store root, under the one reserved shared root, and
            // named after the signal - never under <tenant>/ and never under <tenant>/<repository>/.
            assertThat(root.resolve(SignalContext.SNAPSHOT_ROOT).resolve("kev").resolve("current"))
                    .as("a mirrored catalogue lands at %s/<signal>/, beside the tenant scopes rather than inside one",
                            SignalContext.SNAPSHOT_ROOT)
                    .exists();
            // Its own: one signal cannot read or overwrite another's snapshot, because the narrowing is applied for
            // it rather than chosen by it.
            assertThat(epss.snapshots().exists("current"))
                    .as("one signal's space is not another's").isFalse();
            assertThat(kev.snapshots().exists("current")).isTrue();
        }
    }

    @Test
    void an_unbound_deployment_fails_loudly_rather_than_mirroring_into_nowhere() {
        SignalContext.Deployment bound = SignalContext.deployment(store(), Clock.systemUTC());
        bound.close();
        // A snapshot written nowhere would read back as a clean, empty catalogue - indistinguishable from "this CVE
        // is not exploited". So a wiring error throws, naming the signal that asked (PRINCIPLES §9).
        assertThatThrownBy(() -> SignalContext.of("kev", _ -> null).snapshots())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kev");
    }

    @Test
    void the_context_carries_the_callers_settings_and_the_deployments_clock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);
        assertThat(SignalContext.of("kev", _ -> null).clock().instant())
                .as("an unbound deployment still hands a usable clock - only durable storage needs wiring")
                .isNotNull();
        try (SignalContext.Deployment _ = SignalContext.deployment(store(), fixed)) {
            SignalContext context = SignalContext.of("kev", Map.of("kev", "true")::get);
            assertThat(context.signal()).isEqualTo("kev");
            assertThat(context.setting("kev")).isEqualTo("true");
            assertThat(context.setting("kev-endpoint")).as("an unset key reads null, as it always did").isNull();
            assertThat(context.clock().instant()).isEqualTo(fixed.instant());
        }
    }
}
