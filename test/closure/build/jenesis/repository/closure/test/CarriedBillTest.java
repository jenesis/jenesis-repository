package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.CarriedBill;
import build.jenesis.repository.closure.CarriedClosure;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import java.util.jar.Attributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A release's closure taken from the bill it carries, as its build resolved it: each component the holding this
 * repository keeps of it, at its distance along the bill's edges, and every component it does not hold a cut - while a
 * bill naming its direct dependencies alone is no closure, and the resolvers answer instead.
 */
class CarriedBillTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final String JAR = "/maven/org/acme/app/1.0/app-1.0.jar";

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreRepositoryInventory inventory;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope("releases");
        inventory = new StoreRepositoryInventory(store);
        publication = new Publication(store);
    }

    @Test
    void a_bill_naming_the_closure_is_the_closure_as_the_build_resolved_it() throws IOException {
        release(JAR, jar("""
                {"bomFormat":"CycloneDX","specVersion":"1.5",
                 "metadata":{"component":{"bom-ref":"root","group":"org.acme","name":"app","version":"1.0",
                                          "purl":"pkg:maven/org.acme/app@1.0"}},
                 "components":[
                   {"bom-ref":"a","group":"org.dep","name":"a","version":"1.1","purl":"pkg:maven/org.dep/a@1.1"},
                   {"bom-ref":"b","group":"org.dep","name":"b","version":"2.0","purl":"pkg:maven/org.dep/b@2.0"},
                   {"bom-ref":"c","group":"org.dep","name":"c","version":"3.0","purl":"pkg:maven/org.dep/c@3.0"},
                   {"bom-ref":"d","group":"org.dep","name":"d","version":"4.0","purl":"pkg:maven/org.dep/d@4.0"},
                   {"bom-ref":"n","name":"left-pad","version":"1.3.0","purl":"pkg:npm/left-pad@1.3.0"}],
                 "dependencies":[{"ref":"root","dependsOn":["a"]},{"ref":"a","dependsOn":["b","c","d","n"]}]}"""));
        inventory.cache("Maven", "org.dep:a", "1.1", "https://repo.example/maven2/", NOW);
        inventory.record("Maven", "org.dep:b", "2.0", NOW);
        // c is a copy the screen held at its fill: no holding, its hold's subject and review pointer alone.
        String held = "/maven/org/dep/c/3.0/c-3.0.jar";
        HeldSubjects.hold(publication, store, held, publication.storeBlob(new ByteArrayInputStream(new byte[]{1})),
                "Maven", "org.dep:c", "3.0");

        ClosureSection.Closure closure = CarriedClosure.resolve(new CarriedBill(), ClosureWalk.of(store),
                "Maven", "org.acme:app", "1.0", NOW).orElseThrow();

        assertThat(closure.kind()).isEqualTo(ClosureSource.Kind.BILL);
        assertThat(closure.source()).isEqualTo(CarriedBill.NAME);
        assertThat(closure.components()).as("each the holding kept of it, at its distance along the bill's edges and "
                        + "reached through the component whose edge names it")
                .containsExactly(new ClosureSection.Component("org.dep:a", "1.1", true, 1, ""),
                        new ClosureSection.Component("org.dep:b", "2.0", false, 2, "", "org.dep:a", "1.1"));
        assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate, ClosureSection.Cut::reason)
                .containsExactlyInAnyOrder(
                        tuple("org.dep:c", "held for review"),
                        tuple("org.dep:d", "named by the version's bill, not held by this repository"));
        assertThat(closure.foreign()).as("what it names in another ecosystem, relied on by coordinate rather than cut")
                .extracting(ClosureSection.Foreign::ecosystem, ClosureSection.Foreign::coordinate)
                .containsExactly(tuple("npm", "left-pad"));
        assertThat(closure.status()).isEqualTo(ClosureSection.Status.PARTIAL);
    }

    @Test
    void a_bill_naming_the_direct_dependencies_alone_is_no_closure() throws IOException {
        release(JAR, jar("""
                {"bomFormat":"CycloneDX","specVersion":"1.5",
                 "metadata":{"component":{"bom-ref":"root","name":"app","version":"1.0"}},
                 "components":[{"bom-ref":"a","name":"a","version":"1.1","purl":"pkg:maven/org.dep/a@1.1"}],
                 "dependencies":[{"ref":"root","dependsOn":["a"]}]}"""));

        assertThat(CarriedClosure.resolve(new CarriedBill(), ClosureWalk.of(store),
                "Maven", "org.acme:app", "1.0", NOW))
                .as("the resolvers fill in what it does not name").isEmpty();
    }

    private void release(String path, byte[] body) throws IOException {
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(body)));
        inventory.record(path, NOW);
    }

    /** A jar carrying {@code bill} at the CycloneDX location. */
    private static byte[] jar(String bill) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(bytes, manifest)) {
            out.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.json"));
            out.write(bill.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("org/acme/App.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
