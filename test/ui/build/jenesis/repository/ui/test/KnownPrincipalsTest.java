package build.jenesis.repository.ui.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.ui.ConsoleAdministrators;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.Principals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Signing in is how this deployment learns a person's provider subject, and learning it is what lets an
 * administrator grant to them.
 *
 * <p>Before this, the id an administrator had to type was an opaque {@code oidc/<sub>} that only its owner could
 * produce - and the console made that worse by refusing the sign-in that would have produced it. So these are the
 * claims that make the open sign-in useful rather than merely permissive: a first sign-in is recorded, a returning
 * one costs no write, and a recording that cannot be made never costs the person their sign-in.
 */
class KnownPrincipalsTest {

    @TempDir
    Path root;

    @Test
    void a_first_sign_in_puts_the_person_on_the_list_an_administrator_grants_from() {
        ArtifactStore store = store();
        Principals policy = policy(store);

        assertThat(new KnownPrincipals(Authorization.enforcing(store)).page(null, 10))
                .as("nobody has signed in yet").isEmpty();

        policy.authorities("oidc/8f3c1a", "Ada Lovelace");

        assertThat(new KnownPrincipals(Authorization.enforcing(store)).page(null, 10))
                .as("the id to grant to, under the name they signed in as")
                .singleElement()
                .satisfies(seen -> {
                    assertThat(seen.id()).isEqualTo("oidc/8f3c1a");
                    assertThat(seen.label()).isEqualTo("Ada Lovelace");
                });
    }

    @Test
    void a_returning_sign_in_writes_nothing() {
        // This rides the sign-in path of every mechanism, so a write per sign-in would be a store write per login
        // on every console in the product. A returning person costs a cached point read and nothing else.
        ArtifactStore store = store();
        KnownPrincipals known = new KnownPrincipals(Authorization.enforcing(store));
        known.record("oidc/8f3c1a", "Ada Lovelace");

        Counting counting = new Counting(store);
        new KnownPrincipals(Authorization.enforcing(counting)).record("oidc/8f3c1a", "Ada Lovelace");

        assertThat(counting.writes).as("the same person under the same name is already recorded").isZero();
        assertThat(known.page(null, 10)).as("and is still on the list exactly once").hasSize(1);
    }

    @Test
    void a_renamed_person_is_recorded_under_the_name_they_now_present() {
        // The label is what an administrator reads; the id is what everything decides on. A display name that
        // changed must not strand the list on the old one - and must not, of course, move any authority.
        ArtifactStore store = store();
        KnownPrincipals known = new KnownPrincipals(Authorization.enforcing(store));
        known.record("oidc/8f3c1a", "Ada Lovelace");
        known.record("oidc/8f3c1a", "Ada Byron");

        assertThat(known.page(null, 10)).singleElement()
                .satisfies(seen -> assertThat(seen.label()).isEqualTo("Ada Byron"));
    }

    @Test
    void a_recording_that_cannot_be_written_never_costs_the_person_their_sign_in() {
        // A read-only deployment is a legitimate one, and a person may sign in to it. Recording is bookkeeping, so
        // failing it here would reintroduce - as an exception on the sign-in path - exactly the refusal this whole
        // change removed.
        ArtifactStore readOnly = new ReadOnlyArtifactStore(store());
        Principals policy = policy(readOnly);

        assertThatCode(() -> policy.authorities("oidc/8f3c1a", "Ada Lovelace")).doesNotThrowAnyException();
    }

    private Principals policy(ArtifactStore store) {
        Authorization authorization = Authorization.enforcing(store);
        return new Principals(new ConsoleAdministrators(authorization, ""), new KnownPrincipals(authorization));
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** Counts writes reaching the backend, so "a returning sign-in writes nothing" is a measurement rather than a
     *  reading of the code. Everything else passes straight through to a real store. */
    private static final class Counting implements ArtifactStore {

        private final ArtifactStore delegate;

        private int writes;

        private Counting(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new Counting(delegate.scope(tenant));
        }

        @Override
        public Object identity() {
            return delegate.identity();
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            writes++;
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            writes++;
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
            return delegate.scan(prefix, startAfter, limit, consumer);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            writes++;
            return delegate.writeVersioned(key, content, expected);
        }
    }
}
