/**
 * The compliance gate module in isolation over a real filesystem artifact store: the publication screen's
 * disposition routing (clean admits, a policy-violating upload is quarantined or rejected per the merged inspection
 * verdict, "parsed-empty ⇒ clean" is distinguished from "could-not-parse ⇒ held" at the screen, and a screen-held
 * path is recorded and withheld from serving), the durable {@code QuarantineLog} ledger, the retroactive KEV/license
 * hold lifecycle and its discovered release observers (self-heal §5). The gate is driven through its explicit seams - an injected {@code ComplianceGate} and a test
 * {@code QualityInspector} discovered as a service - so no server boots.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.gate
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.gate.test {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gate;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.jenesis;
    requires org.junit.jupiter;
    requires org.assertj.core;
    uses build.jenesis.repository.compliance.QualityInspector;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.gate.test.GateTestInspector;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.gate.test.GateReplayTestFormat,
            build.jenesis.repository.gate.test.GateEndpointTestFormat;
}
