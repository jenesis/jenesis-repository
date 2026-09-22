package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.FormatFixture;

/**
 * Every format a deployment installs is held to the shared {@code RepositoryFormat} contract, or carries the reason
 * it is not.
 *
 * <p>This module had no completeness ratchet, so it stayed green while covering nothing new - a format could land
 * with no fixture and the only thing that would notice was somebody remembering. The declared leg reads the resolved
 * module graph, the runtime leg the SPI's own accessor, and the fixture leg {@link #ALL}; all three must agree.
 */
class FormatFixtureCensusTest {

    /** Every registered fixture - {@link EcosystemFormatFixture#all()}, so a new format joins one list and every
     *  suite over the set sees it. A format with no fixture joins {@link #UNFIXTURED} with its reason instead. */
    private static final List<EcosystemFormatFixture> ALL = EcosystemFormatFixture.all();

    /** Formats with no contract fixture yet - each with a reason, and shrink-only. */
    private static final List<Exemption> UNFIXTURED = List.of(
            new Exemption("build.jenesis.repository.format.oci.inventory.OciBlobLayout",
                    "not a served format at all: handles() is always false and serve() throws, because it registers "
                            + "as a RepositoryFormat only so that BlobLayout can ride that clause. There is no "
                            + "request path to publish to or read back, so every property in this contract would be "
                            + "vacuous. Its blob-layout seam is covered by BlobLayoutCoordinateSeamTest"));

    @Test
    void every_installed_format_is_held_to_the_contract_or_says_why_not() {
        List<Provider> declared = ContractCensus.declaredProviders(RepositoryFormat.class,
                module -> !module.getName().endsWith(".test"));
        Set<String> declaredClasses = declared.stream()
                .map(Provider::implementation).collect(Collectors.toSet());
        // Through the SPI's own accessor rather than a bare ServiceLoader.load: the loader is caller-sensitive and a
        // test module declares no uses clause of its own.
        List<Provider> runtime = RepositoryFormat.installed().stream()
                .filter(format -> declaredClasses.contains(format.getClass().getName()))
                .map(format -> Provider.runtime(format.name(), format))
                .toList();
        List<String> fixtures = ALL.stream().map(FormatFixture::providerClass).toList();

        ContractCensus.of(RepositoryFormat.class, declared, runtime, fixtures, UNFIXTURED);
    }
}
