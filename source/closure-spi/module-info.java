/**
 * The closure's SPI: how a published version's closure is produced and recorded, what every surface reads of it, and
 * nothing that produces it. A {@link build.jenesis.repository.closure.spi.ClosureSource} produces a release's closure
 * for the ecosystems it declares, over the repositories a {@link build.jenesis.repository.closure.spi.ClosureWalk}
 * reaches; a {@link build.jenesis.repository.closure.spi.RequirementGrammar} reads one ecosystem's requirements and
 * orders its versions. What the closure pass records is two sections of the version's document - the closure itself
 * ({@link build.jenesis.repository.closure.spi.ClosureSection}) and what it reaches that is held for review or carries
 * findings ({@link build.jenesis.repository.closure.spi.ExposureSection}) - which a surface reads without depending on
 * the pass that wrote them.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.closure.spi {
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.maintenance;
    requires transitive build.jenesis.repository.metadata;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    requires org.slf4j;
    exports build.jenesis.repository.closure.spi;
    uses build.jenesis.repository.closure.spi.RequirementGrammar;
    uses build.jenesis.repository.closure.spi.ClosureSource;
}
