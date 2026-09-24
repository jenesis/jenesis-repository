/**
 * The publish-path compliance screen's boot-time wiring as a removable module: it provides
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}, so the repository server imports the
 * configuration through {@code ServiceLoader} discovery and names no screen of its own.
 *
 * <p>It arms the screen from beans a deployment already has - the live configuration, the attributed advisory
 * feeds, the maintainer-health source, the meter registry - and unwires each when the context closes, so the
 * next context in the same JVM starts clean. With this module absent nothing arms the screen and a publish is
 * not screened, which is the ordinary shape of a deployment that installs no gate.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.gate.wiring {
    requires build.jenesis.repository.gate;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.store;
    requires micrometer.core;
    requires spring.context;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.gate.wiring.GateWiringModule;
}
