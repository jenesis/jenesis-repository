/**
 * A release's closure taken from the lock file it carries, as written: an {@code npm-shrinkwrap.json} inside an npm
 * tarball, which npm honours when the package is installed as a dependency, and a {@code Cargo.lock} inside a crate.
 * Each package the lock pins is placed in the repositories of the closure walk as a carried bill's components are,
 * after any bill the release carries and before the resolvers. Nothing is fetched.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.closure.lock {
    requires build.jenesis.repository.closure;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires org.apache.commons.compress;
    requires tools.jackson.databind;
    requires tools.jackson.dataformat.toml;
    exports build.jenesis.repository.closure.lock;
    provides build.jenesis.repository.closure.spi.ClosureSource
            with build.jenesis.repository.closure.lock.NpmShrinkwrap, build.jenesis.repository.closure.lock.CargoLock;
}
