package build.jenesis.repository.closure.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.testkit.ClosureContract;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.compliance.testkit.NoEgressResolver;
import build.jenesis.repository.contract.testkit.ContractCensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every closure source the graph declares is discovered and has a fixture, every contract property is run by some
 * fixture, every source's kind is one its role can have, and the tripwire the "nothing is fetched" checks read is
 * installed.
 */
class ClosureSourceCensusTest {

    static final List<ClosureFixture> FIXTURES = List.of(new CarriedBillFixture(), new NpmShrinkwrapFixture(),
            new CargoLockFixture(), new MavenResolverFixture(), new DeclaredClosureFixture());

    @Test
    void every_declared_source_is_discovered_and_driven_by_a_fixture() {
        List<ContractCensus.Provider> runtime = new ArrayList<>();
        for (ClosureSource source : ServiceLoader.load(ClosureSource.class)) {
            runtime.add(ContractCensus.Provider.runtime(source.getClass().getName(), source));
        }
        ContractCensus.of(ClosureSource.class, ContractCensus.declaredProviders(ClosureSource.class), runtime,
                FIXTURES.stream().map(fixture -> ClosureContract.source(fixture).getClass().getName()).toList(),
                List.of());
    }

    @Test
    void every_property_is_run_by_some_fixture() {
        Set<ClosureContract.Property> run = EnumSet.noneOf(ClosureContract.Property.class);
        FIXTURES.forEach(fixture -> ClosureContract.checks(fixture).forEach(check -> run.add(check.property())));

        assertThat(run).containsExactlyInAnyOrder(ClosureContract.Property.values());
    }

    @Test
    void every_source_is_of_a_kind_its_role_can_have() {
        assertThat(ClosureSource.installed()).allSatisfy(source -> assertThat(source.kind().carried())
                .as(source.name()).isEqualTo(source instanceof ClosureSource.Carried));
    }

    @Test
    void the_tripwire_the_fetch_checks_read_is_installed() {
        List<String> before = NoEgressResolver.attempted();

        assertThatThrownBy(() -> InetAddress.getByName("repo.maven.apache.org"))
                .isInstanceOf(UnknownHostException.class).hasMessageContaining("ClosureSource contract suite");
        assertThat(NoEgressResolver.since(before)).containsExactly("repo.maven.apache.org");
    }
}
