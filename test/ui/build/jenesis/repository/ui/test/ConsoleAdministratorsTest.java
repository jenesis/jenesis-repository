package build.jenesis.repository.ui.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.ConsoleAdministrators;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jenreg.ui.admins} is a <em>seed</em>, not a mirror, and these are the two halves of that word - the two
 * things an operator cannot infer from the setting's name and that the class javadoc therefore has to promise.
 *
 * <p>Both are asserted over one store across two constructions, because the claim is about what survives a boot.
 * The mutation each one bites on is a seed that reconciled - one that deleted the grants it did not just write, or
 * that answered from the parsed list instead of from the store. Either would pass every other suite here:
 * {@code PrincipalsTest} only ever asks about ids the setting names, on a store the seed just wrote.
 */
class ConsoleAdministratorsTest {

    @TempDir
    Path root;

    @Test
    void an_administrator_granted_since_boot_is_an_administrator_the_setting_never_mentioned() throws IOException {
        ArtifactStore store = store();
        ConsoleAdministrators administrators = new ConsoleAdministrators(store, "oidc/alice");
        assertThat(administrators.is("oidc/carol")).isFalse();

        // What the API does when an operator grants administration through the console or over HTTP.
        Authorization.enforcing(store).setGrant(Authorization.DEPLOYMENT,
                Authorization.Subject.principal("oidc/carol"), "*", "*");

        assertThat(administrators.is("oidc/carol"))
                .as("a grant made after boot is read back, which reading the setting could never do")
                .isTrue();
    }

    @Test
    void dropping_an_id_from_the_setting_does_not_revoke_the_administration_it_seeded() {
        ArtifactStore store = store();
        new ConsoleAdministrators(store, "oidc/alice, github/bob");

        // The next boot of the same deployment over the same store, with one id taken out of the list.
        ConsoleAdministrators next = new ConsoleAdministrators(store, "oidc/alice");

        assertThat(next.is("github/bob"))
                .as("the seed writes what it names and removes nothing - revocation goes through the API, so that a "
                        + "seed cannot silently undo a grant made on the surface operators are told to use")
                .isTrue();
        assertThat(next.is("oidc/alice")).isTrue();
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }
}
