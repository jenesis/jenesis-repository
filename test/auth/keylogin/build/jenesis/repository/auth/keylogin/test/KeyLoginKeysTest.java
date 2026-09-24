package build.jenesis.repository.auth.keylogin.test;

import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.auth.keylogin.KeyLoginKeys;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The issued-key store: a minted key resolves to its principal, only the key's hash is ever written (never the
 * plaintext), the key-space lives under the sanctioned shared {@code auth/} root, listing never leaks key material,
 * the tenant a key was bound to round-trips, and a revoked key stops resolving. A second {@link KeyLoginKeys} over the
 * same store sees the first's writes, so the compare-and-set holds across instances (replicas).
 */
public class KeyLoginKeysTest {

    @TempDir
    Path root;

    private KeyLoginKeys keys() {
        return new KeyLoginKeys(CacheStorages.documents(root));
    }

    @Test
    void issuesResolvesAndRevokes() throws IOException {
        KeyLoginKeys keys = keys();
        KeyLoginKeys.Issued issued = keys.issue("keylogin/octocat", "acme", "octocat");

        assertThat(issued.key()).startsWith("jkl_");
        assertThat(issued.principal()).isEqualTo("keylogin/octocat");
        assertThat(issued.tenant()).isEqualTo("acme");
        assertThat(keys.resolve(issued.key())).hasValueSatisfying(resolved ->
                assertThat(resolved.principal()).isEqualTo("keylogin/octocat"));
        assertThat(keys.find(issued.id())).hasValueSatisfying(entry -> {
            assertThat(entry.principal()).isEqualTo("keylogin/octocat");
            assertThat(entry.tenant()).isEqualTo("acme");
        });

        keys.revoke(issued.id());
        assertThat(keys.resolve(issued.key())).isEmpty();
        assertThat(keys.find(issued.id())).isEmpty();
    }

    @Test
    void storesUnderTheAuthRootAndOnlyTheHashNeverThePlaintext() throws IOException {
        KeyLoginKeys.Issued issued = keys().issue("keylogin/octocat", "acme", "octocat");

        // The key-space sits under the sanctioned shared auth root inside the product's own space, not a space of
        // its own beside the tenants.
        String onDisk = Files.readString(root.resolve(Scopes.SYSTEM).resolve(Scopes.AUTH)
                .resolve("keylogin").resolve("keys.properties"));
        assertThat(onDisk).doesNotContain(issued.key());
        // The stored id is the 64-hex SHA-256 of the key.
        assertThat(onDisk).contains(issued.id());
        assertThat(issued.id()).matches("[0-9a-f]{64}");
    }

    @Test
    void resolvesUnknownAndBlankToEmpty() throws IOException {
        KeyLoginKeys keys = keys();
        keys.issue("keylogin/octocat", "acme", "octocat");
        assertThat(keys.resolve("jkl_not-a-real-key")).isEmpty();
        assertThat(keys.resolve("")).isEmpty();
        assertThat(keys.resolve(null)).isEmpty();
    }

    @Test
    void listExposesPrincipalsAndTenantsButNoKeyMaterial() throws IOException {
        KeyLoginKeys keys = keys();
        KeyLoginKeys.Issued one = keys.issue("keylogin/alice", "acme", "alice");
        keys.issue("keylogin/bob", "globex", "bob");

        List<KeyLoginKeys.Entry> entries = keys.list();
        assertThat(entries).extracting(KeyLoginKeys.Entry::principal)
                .containsExactlyInAnyOrder("keylogin/alice", "keylogin/bob");
        assertThat(entries).extracting(KeyLoginKeys.Entry::tenant)
                .containsExactlyInAnyOrder("acme", "globex");
        assertThat(entries).extracting(KeyLoginKeys.Entry::id).allMatch(id -> id.matches("[0-9a-f]{64}"));
        assertThat(entries).noneMatch(e -> e.id().equals(one.key()) || e.principal().contains("jkl_"));
    }

    @Test
    void rejectsAPrincipalThatCouldInjectAStoreEntry() {
        // The store line is "<hash> = <principal> <tenant> [login]" and is re-parsed by splitting the value on
        // \s+, so a principal carrying ANY delimiter the parser honours ('=', ':' or any whitespace) could rewrite a
        // neighbouring entry's fields - most dangerously the tenant a key is bound to. A literal space is the obvious
        // case, but a tab or newline splits identically at read time while surviving trim() (which strips only the
        // ends), so the write-time guard must reject every whitespace character, not just the space. Every such
        // principal is refused at the door, before any hash is minted or written.
        KeyLoginKeys keys = keys();
        for (String injecting : List.of("keylogin/octocat acme", "keylogin\toctocat", "keylogin\noctocat",
                "keylogin=octocat", "keylogin:octocat", "  ", "")) {
            assertThatThrownBy(() -> keys.issue(injecting, "acme", "octocat"))
                    .as("a principal containing a store separator or blank must never be issued: '%s'", injecting)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void secondInstanceOverSameStoreSeesTheFirstsKeys() throws IOException {
        KeyLoginKeys.Issued issued = keys().issue("keylogin/carol", "acme", "carol");
        // A distinct instance over the same store (a second replica) resolves the key the first issued.
        assertThat(keys().resolve(issued.key())).isPresent();
    }
}
