package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenants a principal holds a grant in, as {@link Authorization#tenantsOf} names them: one document per principal,
 * kept by every write that changes what they hold in a tenant, so a person's sign-in asks one point read instead of
 * probing every tenant.
 *
 * <p>What is asserted is that the index never omits a tenant the grants say the principal holds - whichever path
 * conferred it, whichever {@code Authorization} instance took the write, and whichever order two writers on two
 * nodes cross in - and that it is written only when the set of tenants changes.
 */
class PrincipalTenantsTest {

    private static final Authorization.Subject ADA = Authorization.Subject.principal("oidc/ada");

    /** Where ada's index lives - the key a peer's write is arranged around. */
    private static final String ADA_INDEX = ".system/auth/.principals/oidc%2Fada";

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
    void a_role_held_only_through_a_group_lists_its_tenant_and_leaves_with_the_group_grant() throws IOException {
        authorization.groups().addMember("acme", "developers", ADA.id());
        assertThat(tenantsOf(ADA.id())).as("a member of a group that grants nothing holds nothing").isEmpty();

        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*", Authorization.REPOSITORY_READ);
        assertThat(tenantsOf(ADA.id()))
                .as("the group's grant reaches the index on the write that derives it, not at a repair")
                .containsExactly("acme");

        authorization.removeGrant("acme", Authorization.Subject.group("developers"), "*");
        assertThat(tenantsOf(ADA.id())).as("and the tenant leaves once nothing is held there").isEmpty();
    }

    @Test
    void a_tenant_stays_listed_while_either_a_direct_or_a_derived_grant_remains() throws IOException {
        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        authorization.setGrant("acme", Authorization.Subject.group("developers"), "*", Authorization.REPOSITORY_READ);
        authorization.groups().addMember("acme", "developers", ADA.id());

        authorization.removeSubject("acme", ADA);
        authorization.groups().rederive("acme", ADA.id());
        assertThat(tenantsOf(ADA.id())).as("the direct grant went, the group's did not").containsExactly("acme");

        authorization.groups().removeMember("acme", "developers", ADA.id());
        assertThat(tenantsOf(ADA.id())).as("now neither remains").isEmpty();
    }

    @Test
    void the_index_names_every_tenant_and_the_deployment_is_not_one() throws IOException {
        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        authorization.setGrant("globex", ADA, "releases", Authorization.REPOSITORY_WRITE);
        authorization.setGrant(Authorization.DEPLOYMENT, ADA, "*", Authorization.MANAGE_READ);

        assertThat(tenantsOf(ADA.id()))
                .as("a deployment-wide grant is held in every tenant, which no list of tenants can say")
                .containsExactly("acme", "globex");
    }

    @Test
    void a_grant_in_a_tenant_already_listed_writes_nothing_to_the_index() throws IOException {
        FaultInjectingStore counted = FaultInjectingStore.wrap(store);
        List<String> indexWrites = Collections.synchronizedList(new ArrayList<>());
        counted.tracing((op, key) -> {
            if (ADA_INDEX.equals(key) && (op == FaultInjectingStore.Op.WRITE_VERSIONED
                    || op == FaultInjectingStore.Op.WRITE || op == FaultInjectingStore.Op.DELETE)) {
                indexWrites.add(key);
            }
        });
        Authorization node = Authorization.enforcing(counted);

        node.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        assertThat(indexWrites).as("the first grant in a tenant names it").hasSize(1);

        node.setGrant("acme", ADA, "releases", Authorization.REPOSITORY_WRITE);
        node.removeGrant("acme", ADA, "releases");
        node.groups().rederive("acme", ADA.id());
        assertThat(indexWrites)
                .as("a write costs a dozen reads, so one that leaves the tenant set as it was adds none")
                .hasSize(1);

        node.removeGrant("globex", Authorization.Subject.principal("oidc/grace"), "*");
        assertThat(new String(store.readVersioned(".system/auth/.principals/oidc%2Fgrace").map(
                ArtifactStore.Versioned::content).orElse(new byte[0]), StandardCharsets.UTF_8))
                .as("and a principal who holds nothing is given no document saying so").isEmpty();
    }

    @Test
    void two_nodes_deriving_at_once_both_land_their_tenant() throws IOException {
        // The crossing made deterministic: node one has read ada's index and is about to write acme into it when
        // node two derives her a role in globex and writes that first. Node one's write is against the index it
        // read, so it must lose the compare-and-set and go round again rather than put back a list without globex.
        Authorization peer = Authorization.enforcing(store);
        for (String tenant : List.of("acme", "globex")) {
            authorization.groups().addMember(tenant, "developers", ADA.id());
        }
        AtomicBoolean crossed = new AtomicBoolean();
        FaultInjectingStore first = FaultInjectingStore.peer(store);
        first.tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.WRITE_VERSIONED && ADA_INDEX.equals(key) && crossed.compareAndSet(false, true)) {
                try {
                    peer.setGrant("globex", Authorization.Subject.group("developers"), "*",
                            Authorization.REPOSITORY_READ);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });

        Authorization.enforcing(first).setGrant("acme", Authorization.Subject.group("developers"), "*",
                Authorization.REPOSITORY_READ);

        assertThat(crossed).as("the peer's derivation landed inside node one's").isTrue();
        assertThat(tenantsOf(ADA.id())).as("neither node's tenant was written over by the other's")
                .containsExactly("acme", "globex");
    }

    @Test
    void many_derivations_on_two_nodes_at_once_all_reach_the_index() throws Exception {
        Authorization peer = Authorization.enforcing(store);
        int tenants = 16;
        for (int index = 0; index < tenants; index++) {
            authorization.groups().addMember("tenant-" + index, "developers", ADA.id());
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < tenants; index++) {
            String tenant = "tenant-" + index;
            Authorization node = index % 2 == 0 ? authorization : peer;
            Thread writer = Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    node.setGrant(tenant, Authorization.Subject.group("developers"), "*",
                            Authorization.REPOSITORY_READ);
                } catch (Throwable failed) {
                    failures.add(failed);
                }
            });
            writers.add(writer);
            writer.start();
        }
        start.countDown();
        for (Thread writer : writers) {
            writer.join();
        }

        assertThat(failures).as("no derivation gave up").isEmpty();
        assertThat(tenantsOf(ADA.id())).as("every tenant a group granted her in, whichever node derived it")
                .hasSize(tenants);
    }

    @Test
    void a_removal_crossing_a_grant_puts_the_tenant_back() throws IOException {
        // Node one takes ada's last grant in acme away, finds she holds nothing there, and is about to write acme
        // out of her index; in that moment node two grants her something else in acme, reads the index, finds acme
        // named and writes nothing. Compare-and-set cannot see this - node two wrote no index - so node one reads
        // the grants again after its removal and finds node two's.
        Authorization peer = Authorization.enforcing(store);
        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        AtomicBoolean crossed = new AtomicBoolean();
        FaultInjectingStore first = FaultInjectingStore.peer(store);
        first.tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.WRITE_VERSIONED && ADA_INDEX.equals(key) && crossed.compareAndSet(false, true)) {
                try {
                    peer.setGrant("acme", ADA, "releases", Authorization.REPOSITORY_READ);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });

        Authorization.enforcing(first).removeGrant("acme", ADA, "*");

        assertThat(crossed).as("the peer's grant landed inside the removal").isTrue();
        assertThat(Authorization.enforcing(store).grants("acme", ADA)).as("she holds the peer's grant")
                .containsOnlyKeys("releases");
        assertThat(tenantsOf(ADA.id())).as("so her index names the tenant").containsExactly("acme");
    }

    @Test
    void an_index_never_kept_is_built_by_the_start_up_repair() throws IOException {
        // A deployment whose people were granted before the index was kept: the grants are there, direct in one
        // tenant and through a group in another, and no index document is.
        authorization.setGrant("acme", ADA, "*", Authorization.REPOSITORY_READ);
        authorization.setGrant("globex", Authorization.Subject.group("developers"), "*", Authorization.REPOSITORY_READ);
        authorization.groups().addMember("globex", "developers", ADA.id());
        store.delete(ADA_INDEX);
        authorization.forget();
        assertThat(tenantsOf(ADA.id())).as("the premise: nothing is indexed yet").isEmpty();

        // What every node does as it starts, off the request path.
        Authorization.enforcing(store).groups().repairDerivedGrants();

        assertThat(tenantsOf(ADA.id())).as("the repair reconciles every principal it re-derives")
                .containsExactly("acme", "globex");
    }

    /** What a node that has read nothing before answers - so an assertion is about the store, not a cache. */
    private List<String> tenantsOf(String principal) throws IOException {
        return Authorization.enforcing(store).tenantsOf(principal);
    }
}
