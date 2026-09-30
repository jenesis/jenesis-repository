package build.jenesis.repository.search.lucene.test;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * A test-only {@link QualityInspector} the search-index backfill discovers, so a "pre-existing" artifact (one with no
 * gate-written licenses section) can be re-derived from its stored metadata without pulling a real format's inspector
 * onto the test path. It claims the {@code /fake/} paths the {@link FakeLicensedFormat} lays out and reports a single
 * declared Apache license for each - a distinct value from any section a test writes, so a test can tell a backfill
 * (this inspector's Apache) apart from a preferred section (its own license).
 */
public final class FakeLicensedInspector implements QualityInspector {

    static final String DECLARED_NAME = "Apache License, Version 2.0";

    @Override
    public boolean handles(String path) {
        return path.startsWith("/fake/");
    }

    @Override
    public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) {
        return inspectArtifact(path, content, lookup);
    }

    @Override
    public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup) {
        return List.of(new ComplianceGate.Subject("fake", path, "0",
                List.of(new ComplianceGate.DeclaredLicense(DECLARED_NAME, null))));
    }
}
