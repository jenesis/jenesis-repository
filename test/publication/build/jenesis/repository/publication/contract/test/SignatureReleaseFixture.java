package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.gate.HoldKind;
import build.jenesis.repository.hooks.testkit.Coordinates;
import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;
import build.jenesis.repository.hooks.testkit.HookTestFormat;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.store.ArtifactStore;

/**
 * {@code SignatureHoldReleaseObserver}: the signature dimension's hold kind, a plain adapter over {@code HoldKind}.
 * Same promotion shape as the licence kind, over {@code holds/signature/...} and {@code overrides/signature/...}: the
 * subjects are the finding's own reason tokens, and a release promotes them into the sticky override the signature
 * sweep reads before it holds again.
 */
final class SignatureReleaseFixture extends HoldReleaseFixture {

    /** The kind the observer answers to, reached the way the screen writes its records. */
    private static final HoldKind KIND = HoldKind.of("signature");

    private static final String REASON = "signature-missing";

    @Override
    public String hook() {
        return "signature-hold-release";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.compliance.signatures.SignatureHoldReleaseObserver";
    }

    @Override
    public List<String> namespaces() {
        return ReleaseSpaces.of();
    }

    @Override
    protected void record(ArtifactStore store, String path) throws IOException {
        KIND.hold(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION, Set.of(REASON));
    }

    @Override
    public boolean records(ArtifactStore store, String path) throws IOException {
        return KIND.held(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION).isPresent();
    }

    @Override
    public Optional<String> override(ArtifactStore store, String path) throws IOException {
        Set<String> overridden =
                KIND.overridden(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION);
        return overridden.isEmpty() ? Optional.empty() : Optional.of(new TreeSet<>(overridden).toString());
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Hooks.rows(store, "overrides/signature");
    }
}
