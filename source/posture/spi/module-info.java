/**
 * The security-posture SPI: the configuration warnings a module reports about the effective deployment configuration.
 * Each is a {@link build.jenesis.repository.posture.SecurityAdvisory} with a stable {@code jenrepo.<feature>.<signal>}
 * id, a {@link build.jenesis.repository.posture.Severity severity}, a
 * {@link build.jenesis.repository.posture.Scope scope}, why it is unsafe, the safer key and value, and a docs link. A
 * module implements {@link build.jenesis.repository.posture.SafetyAdvisor}, is discovered with
 * {@link java.util.ServiceLoader} and is handed the effective {@link build.jenesis.repository.posture.Configuration}; a
 * disabled or absent module contributes nothing.
 *
 * <p>This reports the deployment's own configuration, not vulnerability advisories about published artifacts. The
 * module is {@code java.base}-only apart from the equally {@code java.base}-only observation and scope modules, so any
 * SPI can require it without Spring. The distribution collects one
 * {@link build.jenesis.repository.posture.PostureReport}, logs its deployment-wide advisories at boot and serves the
 * same list on the console and the admin API.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.posture {
    // The observation module owns Contributions, the containment every collected report shares.
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.scope;
    exports build.jenesis.repository.posture;
    uses build.jenesis.repository.posture.SafetyAdvisor;
    provides build.jenesis.repository.posture.SafetyAdvisor
            with build.jenesis.repository.posture.SecurityPosture;
}
