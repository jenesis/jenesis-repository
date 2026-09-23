package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server side: every surface that derives tenants from store namespaces sees the product's own data and must not
 * offer it as a tenant - and, since the product's data moved into a space of its own, must not refuse a tenant
 * merely for being named like one of the product's concerns.
 *
 * <p>The store root holds the tenant scopes alongside {@link Scopes#SYSTEM}, so an enumeration that lists the root
 * and keeps what merely <em>looks</em> like a name would offer a key space as a tenant. It used to keep them apart
 * with a list of forbidden words consulted in both directions, and splitting those two directions is what once let
 * the console render {@code audit} as a tenant with working Open and Delete controls. The list is gone: one space
 * outside the scope-name grammar holds everything the product owns, so the shape rule that already gates creation
 * excludes it from every listing for free.
 *
 * <p>The claim therefore has two halves, and the second is the one a denylist could never make: nothing under the
 * product's space is offered as a tenant, <em>and</em> a tenant may be called {@code audit} or {@code cache}.
 */
class ProductSpaceTenantEnumerationTest {

    @TempDir
    private Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** Put a real object in each of the product's spaces, the way the product's own writes do. */
    private static void seedProductSpaces(ArtifactStore store) throws IOException {
        for (String space : Scopes.SPACES) {
            store.scope(Scopes.SYSTEM).scope(space)
                    .write("marker", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void the_routing_gate_refuses_the_product_space_and_no_longer_refuses_ordinary_words() {
        assertThat(Repositories.valid(Scopes.SYSTEM))
                .as("the one name the product keeps for itself is outside the grammar, so nobody can take it")
                .isFalse();
        for (String space : Scopes.SPACES) {
            assertThat(Repositories.valid(space))
                    .as("'%s' is only a name the product uses INSIDE its own space, so it is free out here", space)
                    .isTrue();
        }
        assertThat(Repositories.valid("acme")).isTrue();
        assertThat(Repositories.valid("acme-corp")).as("a hyphen is a legal tenant segment").isTrue();
        assertThat(Repositories.valid("a.b")).as("a traversal-shaped segment is not").isFalse();
    }

    @Test
    void the_tenant_listing_offers_the_product_space_to_nobody_and_an_audit_tenant_to_everybody() throws IOException {
        ArtifactStore store = store();
        store.scope("acme").scope("releases")
                .write("blobs/a", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        // A tenant named exactly like one of the product's spaces - legal now, and the case a forbidden-word list
        // got wrong in the other direction by refusing it.
        store.scope("audit").scope("releases")
                .write("blobs/a", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        seedProductSpaces(store);

        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        Repositories repositories = new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));

        assertThat(store.list("")).as("the product's space really is in the store beside the tenants")
                .contains(Scopes.SYSTEM);
        assertThat(repositories.tenants()).containsExactly("acme", "audit");
    }

    @Test
    void the_settings_export_enumerates_no_product_space_as_a_tenant() throws IOException {
        ArtifactStore store = store();
        Settings settings = new Settings(store);
        settings.set("deny-list", "global:coord");
        settings.set("acme", "deny-list", "acme:coord");
        // Every one of the product's spaces now holds a settings document of its own - the shape that made audit,
        // locks and quota reachable as export "tenants" back when only auth and config were excluded by name.
        for (String space : Scopes.SPACES) {
            store.scope(Scopes.SYSTEM).scope(space)
                    .write("settings/build.jenesis.repository.compliance.osv.json",
                            new ByteArrayInputStream("{\"deny-list\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
        }

        assertThat(settings.configuredTenants()).containsExactly("acme");
    }
}
