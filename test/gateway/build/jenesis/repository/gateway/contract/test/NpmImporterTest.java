package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.npm.NpmImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The npm importer's target descriptor is derived from the registry's own {@code <name>/-/<file>.tgz} shape, so a
 * scoped tarball keeps its {@code @scope/name} coordinate whole rather than being split at the first path slash, and
 * the version peeled between the short-name prefix and {@code .tgz} never carries a slash. npm's native registry layout
 * lays the tarball at exactly that {@code /-/} path, so the incumbent asset path resolves directly; the slash guard in
 * {@code NpmFormat.describe} is defence in depth (parity with the PyPI import fix and GoFormat's own version guard)
 * against a stray multi-segment file leaking the source path's slashes into a version segment the inventory would then
 * reject as {@code Not a traversal-free scope segment}. Container-free - the live-Nexus legs need Docker.
 */
class NpmImporterTest {

    @Test
    void keeps_the_scope_whole_on_a_scoped_tarball_path() {
        // A shape-assuming parse that split the coordinate at the first '/' would mis-read the scope as the whole name
        // and derive a wrong version; the /-/ split keeps @scope/babel-core intact.
        ArtifactDescriptor descriptor = new NpmImporter()
                .importTarget("@babel/core/-/core-7.21.0.tgz")
                .orElseThrow();
        assertThat(descriptor.ecosystem()).isEqualTo("npm");
        assertThat(descriptor.coordinate()).isEqualTo("@babel/core");
        assertThat(descriptor.version()).isEqualTo("7.21.0");
    }

    @Test
    void resolves_an_unscoped_tarball_path() {
        ArtifactDescriptor descriptor = new NpmImporter()
                .importTarget("lodash/-/lodash-4.17.11.tgz")
                .orElseThrow();
        assertThat(descriptor.coordinate()).isEqualTo("lodash");
        assertThat(descriptor.version()).isEqualTo("4.17.11");
    }

    @Test
    void never_yields_a_slash_bearing_version() {
        // The defended shape: a filename that starts with the short-name prefix and ends .tgz yet carries a slash would,
        // without the guard, peel a version like "1.0/x". A version is a single filename segment, so any '/' means the
        // path leaked in - the guard describes coordinate-less rather than emitting a slash the segment check rejects.
        new NpmImporter().importTarget("pkg/-/pkg-1.0/extra.tgz")
                .ifPresent(descriptor -> {
                    if (descriptor.version() != null) {
                        assertThat(descriptor.version()).doesNotContain("/");
                    }
                    if (descriptor.coordinate() != null) {
                        assertThat(descriptor.coordinate()).doesNotContain("/-/");
                    }
                });
        // The well-formed scoped path is the one that must still carry a clean coordinate and version.
        ArtifactDescriptor descriptor = new NpmImporter()
                .importTarget("@babel/core/-/core-7.21.0.tgz")
                .orElseThrow();
        assertThat(descriptor.version()).doesNotContain("/");
    }

    @Test
    void a_packument_or_non_tarball_path_is_ignored() {
        assertThat(new NpmImporter().importTarget("@babel/core")).isEmpty();
        assertThat(new NpmImporter().importTarget("lodash")).isEmpty();
    }
}
