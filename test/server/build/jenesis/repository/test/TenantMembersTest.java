package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant's members as {@link Authorization#members} lists them: the principals holding a grant of their own there,
 * and not every person a group names.
 *
 * <p>What is asserted is the direction that matters - the list never omits a principal holding a live grant, however
 * the writes cross and wherever a crash lands between them - and that what it may name besides is taken away by the
 * start-up repair, which also builds the list for grants written before it was kept.
 */
class TenantMembersTest {

    private static final Authorization.Subject ADA = Authorization.Subject.principal("oidc/ada");

    /** Where ada's marker lives in acme. */
    private static final String ADA_MARKER = ".system/auth/acme/members/oidc%2Fada";

    /** Where ada's own grants in acme live. */
    private static final String ADA_GRANTS = ".system/auth/acme/principal/oidc%2Fada/grants";

    @TempDir
    Path root;

    private ArtifactStore store;

    private Authorization authorization;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        authorization = Authorization.enforcing(store);
    }

    @Test
    void a_principal_only_a_group_names_is_no_member() throws IOException {
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*", Authorization.REPOSITORY_READ);
        authorization.groups().addMember("acme", "developers", "oidc/grace");
        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);

        assertThat(authorization.subjects("acme", Authorization.Kind.PRINCIPAL, null, 10).ids())
                .as("the group gave grace a subject in the tenant")
                .containsExactly("oidc/ada", "oidc/grace");
        assertThat(members("acme")).as("but only ada holds anything of her own").containsExactly("oidc/ada");
    }

    @Test
    void a_member_leaves_with_her_last_grant_and_with_her_subject() throws IOException {
        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        authorization.setGrant("acme", ADA, "releases", Authorization.REPOSITORY_WRITE);

        authorization.removeGrant("acme", ADA, "*");
        assertThat(members("acme")).as("a grant remains").containsExactly("oidc/ada");
        authorization.removeGrant("acme", ADA, "releases");
        assertThat(members("acme")).as("none remains").isEmpty();

        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        authorization.removeSubject("acme", ADA);
        assertThat(members("acme")).as("removed whole").isEmpty();
    }

    @Test
    void the_marker_lands_before_the_grant_it_stands_for() throws IOException {
        // A crash between the two writes must leave a marker with nothing behind it - which a reader passes by -
        // and never a grant no listing reaches.
        FaultInjectingStore traced = FaultInjectingStore.wrap(store);
        List<String> writes = Collections.synchronizedList(new ArrayList<>());
        traced.tracing((op, key) -> {
            if ((op == FaultInjectingStore.Op.WRITE || op == FaultInjectingStore.Op.WRITE_VERSIONED)
                    && (ADA_MARKER.equals(key) || ADA_GRANTS.equals(key))) {
                writes.add(key);
            }
        });

        Authorization.enforcing(traced).setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);

        assertThat(writes).containsExactly(ADA_MARKER, ADA_GRANTS);
    }

    @Test
    void a_grant_crossing_a_removal_keeps_its_member() throws IOException {
        // Node one finds ada holds nothing of her own and is about to take her marker away; in that moment node two
        // grants her something, finds the marker present and writes none. Node one reads the grants again after its
        // delete and puts the marker back.
        Authorization peer = Authorization.enforcing(store);
        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        AtomicBoolean crossed = new AtomicBoolean();
        FaultInjectingStore first = FaultInjectingStore.peer(store);
        first.tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.DELETE && ADA_MARKER.equals(key) && crossed.compareAndSet(false, true)) {
                try {
                    peer.setGrant("acme", ADA, "releases", Authorization.REPOSITORY_READ);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });

        Authorization.enforcing(first).removeGrant("acme", ADA, "*");

        assertThat(crossed).as("the peer's grant landed inside the removal").isTrue();
        assertThat(authorization.grants("acme", ADA)).as("she holds the peer's grant").containsOnlyKeys("releases");
        assertThat(members("acme")).as("so she is still a member").containsExactly("oidc/ada");
    }

    @Test
    void the_start_up_repair_lists_grants_from_before_the_list_and_drops_what_holds_nothing() throws IOException {
        // A grant written before the markers were kept: the document alone, as an older node wrote it.
        Properties held = new Properties();
        held.setProperty("*", Authorization.REPOSITORY_READ);
        write(ADA_GRANTS, held);
        // A marker a crash left behind a subject removed whole, and one standing for a grant that has lapsed.
        write(".system/auth/acme/members/oidc%2Fgone", new Properties());
        authorization.setGrant("acme", Authorization.Subject.principal("oidc/lapsed"), "*",
                Authorization.REPOSITORY_READ, Instant.now().minusSeconds(60));
        assertThat(members("acme")).as("the arrangement").containsExactly("oidc/gone", "oidc/lapsed");

        authorization.groups().repairDerivedGrants();

        assertThat(members("acme")).containsExactly("oidc/ada");
    }

    private List<String> members(String tenant) {
        return Authorization.enforcing(store).members(tenant, null, 100).ids();
    }

    private void write(String key, Properties document) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        document.store(bytes, null);
        store.write(key, new ByteArrayInputStream(bytes.toByteArray()));
    }
}
