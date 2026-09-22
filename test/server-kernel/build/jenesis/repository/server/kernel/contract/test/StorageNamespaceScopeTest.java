package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tenancy rule of the per-module storage manifest: every plugin's data is per-tenant - its key-spaces
 * resolve under a {@code <tenant>/} (usually {@code <tenant>/<repository>/}) scope - and the ONE deployment-global
 * exception is the pair of root spaces that are root-level by design, {@code auth/} (a user spans tenants) and the
 * superadmin {@code config/}. The manifest's shared flag enforces it: a new module defaults to per-tenant, a
 * shared declaration outside the auth/config roots fails at construction, a stored document claiming one is
 * skipped rather than acted on, and the modules that do legitimately own a shared space are exactly the known
 * auth/config owners - so a future module cannot silently claim deployment-global storage without failing here.
 */
class StorageNamespaceScopeTest {

    /** The modules that legitimately own a shared (deployment-global) space: the manifest's own documents, the
     *  deployment-wide upstream proxy credentials, the mirrored known-exploited catalogue - the same public data
     *  for every tenant, drawn once, which is why the signal SPI carries no tenant at all - the deployment's runtime
     *  settings documents and the multi-node consistency fingerprints (both), all under {@code config/}, plus
     *  the console's issued-key index under {@code auth/}, since a login key spans tenants.
     *  Growing this set is a deliberate decision, not a default - a new entry must argue why its data is not
     *  per-tenant; the two added argue it in {@code test/namespace}'s reviewed list, which carries the reasons.
     *
     *  <p>This is the <em>runtime</em> half of the rule, over the modules this test module's graph resolves.
     *  {@code build.jenesis.repository.auth.keylogin} extends the console shell rather than the repository server and is not on
     *  it, which is precisely why the earlier {@code test/namespace} carries the same list as a <em>static</em>
     *  scan of {@code source/}: a shared claim made by a module no test graph roots is invisible here, and this list
     *  was three entries long for exactly that reason until the static leg found the fourth. */
    private static final Set<String> SHARED_OWNERS = Set.of(
            "build.jenesis.repository.auth.keylogin",
            "build.jenesis.repository.compliance.kev",
            "build.jenesis.repository.server.kernel",
            "build.jenesis.repository.maintenance",
            "build.jenesis.repository.telemetry",
            "build.jenesis.repository.upstream.store");

    @TempDir
    Path root;

    private ArtifactStore store;
    private StorageNamespaces namespaces;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        namespaces = new StorageNamespaces(store);
    }

    @Test
    void only_the_auth_config_owners_declare_shared_spaces_and_every_other_module_is_tenant_scoped() {
        List<StorageNamespace.Declared> declared = StorageNamespace.declared();
        assertThat(declared).as("the installed manifest is not empty").isNotEmpty();
        for (StorageNamespace.Declared entry : declared) {
            for (String prefix : entry.sharedPrefixes()) {
                // .system/<root>/<something>: the product's own space, then one of the two by-design roots.
                String[] parts = prefix.split("/", 3);
                assertThat(parts[0]).as("a shared key-space sits inside the product's own space").isEqualTo(Scopes.SYSTEM);
                assertThat(StorageNamespace.SHARED_ROOTS)
                        .as("a shared key-space sits under a deployment-global-by-design root")
                        .contains(parts[1]);
            }
            if (!entry.sharedPrefixes().isEmpty()) {
                assertThat(SHARED_OWNERS)
                        .as("module '%s' declares a shared key-space %s; only the known auth/config owners may - "
                                + "a new module's data is per-tenant", entry.module(), entry.sharedPrefixes())
                        .contains(entry.module());
            }
        }
        // Exactly the reviewed owners this graph actually installs - intersected rather than compared whole, because
        // the list is the deployment-wide rule while this module's graph is a subset of the deployment. Comparing it
        // whole would fail the day a shared owner joins the graph, which is the wrong direction of pressure: the list
        // must be free to grow with the product, and the completeness half belongs to the static leg in
        // test/namespace, which reads source/ and therefore sees every claimant whether it is on a graph or not.
        Set<String> installed = declared.stream()
                .map(StorageNamespace.Declared::module).collect(Collectors.toCollection(TreeSet::new));
        Set<String> expected = SHARED_OWNERS.stream()
                .filter(installed::contains).collect(Collectors.toCollection(TreeSet::new));
        assertThat(expected)
                .as("no reviewed shared owner is installed here, so this leg would pass over nothing")
                .isNotEmpty();
        assertThat(declared)
                .as("the shared owners this graph installs are declared, so the exception list is live, not vacuous")
                .filteredOn(entry -> !entry.sharedPrefixes().isEmpty())
                .extracting(StorageNamespace.Declared::module)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void a_new_module_defaults_to_per_tenant() {
        StorageNamespace plugin = new StorageNamespace() {
            @Override
            public Set<String> repositoryPrefixes() {
                return Set.of("mydata");
            }
        };
        assertThat(plugin.sharedPrefixes()).as("nothing is shared unless a module explicitly says so").isEmpty();
        assertThat(plugin.tenantPrefixes()).isEmpty();
    }

    @Test
    void a_mis_scoped_shared_declaration_fails_at_construction() {
        assertThatThrownBy(() -> new StorageNamespace.Declared("build.jenesis.repository.rogue",
                Set.of(), Set.of(), Set.of("index/search")))
                .as("a per-tenant key-space cannot be declared deployment-global")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mis-scoped");
        assertThatThrownBy(() -> new StorageNamespace.Declared("build.jenesis.repository.rogue",
                Set.of(), Set.of(), Set.of("locks")))
                .as("another of the product's spaces is refused too - only auth/config are shared by design")
                .isInstanceOf(IllegalArgumentException.class);
        // A proper sub-space of the two by-design roots constructs fine.
        new StorageNamespace.Declared("build.jenesis.repository.ok", Set.of(), Set.of(), Set.of(Scopes.space(Scopes.CONFIG) + "/mine"));
        new StorageNamespace.Declared("build.jenesis.repository.ok", Set.of(), Set.of(), Set.of(Scopes.space(Scopes.AUTH) + "/keys"));
        // The bare root itself is refused: a module owns a space UNDER auth/config, never the root - a bare
        // declaration would authorize purging the entire deployment-global tree through one manifest entry.
        assertThatThrownBy(() -> new StorageNamespace.Declared("build.jenesis.repository.rogue",
                Set.of(), Set.of(), Set.of("auth")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mis-scoped");
    }

    @Test
    void a_stored_entry_claiming_a_root_space_outside_auth_config_is_skipped_never_purged_on() throws IOException {
        store.writeVersioned(StorageNamespaces.ROOT + "/build.jenesis.repository.rogue",
                "repository=\ntenant=\nshared=wildspace\n".getBytes(StandardCharsets.UTF_8), null);
        store.write("wildspace/data", new ByteArrayInputStream("d".getBytes(StandardCharsets.UTF_8)));

        assertThat(namespaces.manifest())
                .extracting(StorageNamespace.Declared::module)
                .doesNotContain("build.jenesis.repository.rogue");
        assertThat(namespaces.purge("build.jenesis.repository.rogue", List.of("acme"))).isEmpty();
        assertThat(store.exists("wildspace/data")).isTrue();
    }

    @Test
    void a_shared_space_registers_with_the_flag_and_round_trips_through_the_stored_document() throws IOException {
        namespaces.register();

        Optional<ArtifactStore.Versioned> stored =
                store.readVersioned(StorageNamespaces.ROOT + "/build.jenesis.repository.upstream.store");
        assertThat(stored).as("the shared owner's declaration is persisted").isPresent();
        assertThat(new String(stored.get().content(), StandardCharsets.UTF_8))
                .contains("shared=" + Scopes.space(Scopes.CONFIG) + "/upstream-auth");

        assertThat(namespaces.manifest())
                .anySatisfy(entry -> {
                    assertThat(entry.module()).isEqualTo("build.jenesis.repository.maintenance");
                    assertThat(entry.sharedPrefixes()).containsExactly(StorageNamespaces.ROOT);
                });
    }

    @Test
    void a_shared_space_is_swept_once_at_the_root_not_per_tenant() throws IOException {
        store.writeVersioned(StorageNamespaces.ROOT + "/build.jenesis.repository.phantom",
                ("repository=\ntenant=\nshared=" + Scopes.space(Scopes.CONFIG) + "/phantom\n")
                        .getBytes(StandardCharsets.UTF_8), null);
        store.write(Scopes.space(Scopes.CONFIG) + "/phantom/secret", new ByteArrayInputStream("ss".getBytes(StandardCharsets.UTF_8)));
        store.scope("acme").write("kept/data", new ByteArrayInputStream("k".getBytes(StandardCharsets.UTF_8)));

        Optional<StorageNamespaces.Report> plan =
                namespaces.plan("build.jenesis.repository.phantom", List.of("acme", "globex"));
        assertThat(plan).isPresent();
        assertThat(plan.get().objects()).as("counted once, not once per tenant").isEqualTo(1);
        assertThat(plan.get().spaces())
                .extracting(StorageNamespaces.Report.Space::prefix)
                .containsExactly(Scopes.space(Scopes.CONFIG) + "/phantom");
        assertThat(store.exists(Scopes.space(Scopes.CONFIG) + "/phantom/secret")).as("a dry run deletes nothing").isTrue();

        Optional<StorageNamespaces.Report> purge =
                namespaces.purge("build.jenesis.repository.phantom", List.of("acme", "globex"));
        assertThat(purge.get().objects()).isEqualTo(1);
        assertThat(store.exists(Scopes.space(Scopes.CONFIG) + "/phantom/secret")).as("the explicit purge reclaims the shared space").isFalse();
        assertThat(store.exists("acme/kept/data")).as("tenant data beside it is untouched").isTrue();
    }
}
