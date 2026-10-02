/**
 * The publish-path compliance screen's boot-time wiring, contributed through
 * {@link build.jenesis.repository.server.kernel.ServerModuleProvider}. It arms the screen from beans a deployment
 * already has - the live configuration, the advisory feeds, the maintainer-health source, the meter registry - as one
 * binding the server layers into the store it builds, and retires the binding when the context closes, so the next
 * context in the same JVM starts clean. With this module absent nothing arms the screen and a publish is not screened.
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
    requires spring.beans;
    requires spring.context;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.gate.wiring.GateWiringModule;
}
