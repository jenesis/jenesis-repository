package build.jenesis.repository.gate.test;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * A test-only {@link QualityInspector}, discovered through {@code ServiceLoader} on the gate test module, that lets the
 * format-agnostic {@link build.jenesis.repository.gate.store.ComplianceScreen} be exercised in isolation without pulling in a
 * real format inspector. It claims only paths under the {@code /gatetest/} prefix - inert for every other test's paths -
 * and routes each one to a deterministic outcome by the first path segment after that prefix, so a test picks the exact
 * subject (or the exact parse failure) the screen must route on:
 *
 * <ul>
 *   <li>{@code /gatetest/clean/...}    - a subject with nothing to gate, which the gate admits;
 *   <li>{@code /gatetest/deny/...}     - the deny-listed coordinate {@code com.deny:pkg};
 *   <li>{@code /gatetest/malicious/...}- the coordinate {@code com.mal:stealer} a malicious-package advisory flags;
 *   <li>{@code /gatetest/empty/...}    - a CLAIMED artifact that parsed cleanly and declares nothing (empty ⇒ clean);
 *   <li>{@code /gatetest/contentonly/...} - a CLAIMED artifact yielding only a CONTENT-SCAN subject (a detected
 *       secret), the shape an embedded-secret scan, an attestation or a signature produces: it carries no licensable
 *       coordinate, so it must not be mistaken for the inspectors having read the package;
 *   <li>{@code /gatetest/malformed/...}- a CLAIMED artifact whose parse genuinely failed
 *       ({@link MalformedArtifactException}: could-not-parse ⇒ NOT clean, the distinction the screen honours);
 *   <li>{@code /gatetest/endpoint/...}  - a coordinate the PATH does not carry ({@code com.endpoint:pkg}), stamped
 *       with the ecosystem {@link GateEndpointTestFormat} owns: the shape, where the screened descriptor is a
 *       shared push endpoint and the artifact is served somewhere the coordinate alone derives;
 *   <li>{@code /gatetest/inspectorfault/...}- a CLAIMED artifact whose inspector throws a NON-Malformed runtime error
 *       (an unhandled edge over hostile content): the screen must fail closed, never letting the raw error escape.
 *   <li>{@code /gatetest/inspectorio/...}   - a CLAIMED artifact whose inspector throws a plain {@link IOException},
 *       the third failure shape its SPI signature permits: it must reach the same fail-closed hold as the two
 *       above rather than the one leg between them that used to escape;
 *   <li>{@code /gatetest/inspectorbroken/...}- a CLAIMED artifact whose inspector raises an {@link Error}: the runtime
 *       or module graph giving way under one guest, which must never be filed as a clean verdict on the artifact.
 * </ul>
 */
public final class GateTestInspector implements QualityInspector {

    private static final String PREFIX = "/gatetest/";

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) throws IOException {
        return route(path);
    }

    @Override
    public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup)
            throws IOException {
        return route(path);
    }

    private static List<ComplianceGate.Subject> route(String path) throws IOException {
        String kind = kind(path);
        return switch (kind) {
            case "clean" -> List.of(subject("org.clean:lib"));
            case "deny" -> List.of(subject("com.deny:pkg"));
            case "malicious" -> List.of(subject("com.mal:stealer"));
            // a coordinate the request path does NOT carry, stamped with the ecosystem GateEndpointTestFormat
            // owns - the shape of a format whose coordinate lives inside the artifact and whose push endpoint is one
            // path every push shares.
            case "endpoint" -> List.of(new ComplianceGate.Subject(
                    GateEndpointTestFormat.ECOSYSTEM, "com.endpoint:pkg", "2.0", List.of()));
            case "empty" -> List.of();   // claimed, parsed, nothing to gate - the "empty ⇒ clean" leg
            // A CONTENT-SCAN subject and nothing else - what an embedded-secret scan, an inbound attestation or a
            // publisher's signature produces: a finding derived from the bytes, carrying no licensable coordinate.
            // It is the shape that made the truncated-artifact fallback misfire, because it satisfies "the inspectors
            // returned something" while supplying nothing for the license and deny-list dimensions to bite on.
            case "contentonly" -> List.of(new ComplianceGate.Subject("test", "", "", List.of())
                    .withSecrets(List.of(new ComplianceGate.DetectedSecret(
                            "planted-key", "a credential in the truncated head", "****", path))));
            case "malformed" -> throw new MalformedArtifactException(
                    "deliberately corrupt test artifact at " + path);
            case "inspectorfault" -> throw new IllegalStateException(
                    "inspector hit an unhandled edge on " + path);   // a NON-Malformed runtime fault: must fail closed
            //, the host-contract routes. The SPI declares `throws IOException`, so a plain IOException is as
            // legal an inspector failure as a runtime one - a ZipException off a truncated central directory is the
            // everyday instance - and it must reach the same fail-closed hold rather than the publisher's 500.
            case "inspectorio" -> throw new IOException(
                    "inspector could not read the archive on " + path);
            // The runtime giving way under one guest rather than the guest failing: it must not be filed as a clean
            // verdict on the artifact, whatever else it does.
            case "inspectorbroken" -> throw new NoClassDefFoundError(
                    "planted: an inspector plugin's class is missing from the module graph");
            default -> List.of();
        };
    }

    /** The routing token: the first path segment after {@code /gatetest/}. */
    private static String kind(String path) {
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static ComplianceGate.Subject subject(String coordinate) {
        return new ComplianceGate.Subject("test", coordinate, "1.0", List.of());
    }
}
