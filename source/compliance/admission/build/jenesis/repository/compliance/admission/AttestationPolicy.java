package build.jenesis.repository.compliance.admission;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.Verdict;

/**
 * The inbound-provenance admission dimension: it verifies the attestation stamped onto a subject against the tenant's
 * trust anchors and expected builder and source, and raises a finding at the configured {@code action} (default
 * {@link Verdict#QUARANTINE}, or {@link Verdict#REJECT}) when it does not hold. In order: the referrer parses as an
 * in-toto DSSE envelope; its signature verifies against a trust anchor; its signed subject digest binds to this
 * artifact once the artifact is present - a sidecar admitted first defers the binding to the artifact's publish, and a
 * mismatched subject, one without a sha256 digest, or an artifact too large to hash is raised, so a valid attestation
 * for another artifact is no replay token; and its builder and source match the expected values (each optional). The
 * advisory lookup is not consulted. A subject without an attestation yields nothing.
 *
 * <p>{@link Verdict#ALLOW} permits rather than switches off: the dimension still verifies and reports a failing
 * envelope as a {@code Finding(ALLOW, ...)}, so an incident review sees the attestation was checked and found wanting -
 * as the known-exploited, private-name, version-floor, secret and health dimensions do under ALLOW. The
 * {@code jenrepo.provenance-admission} toggle or an unset trust anchor removes the dimension from the gate instead.
 */
public final class AttestationPolicy implements GatePolicy {

    private final List<PublicKey> keys;
    private final List<Matcher> builders;
    private final List<Matcher> sources;
    private final Verdict action;

    public AttestationPolicy(List<PublicKey> keys, List<String> builders, List<String> sources, Verdict action) {
        this.keys = List.copyOf(keys);
        this.builders = builders.stream().map(Matcher::new).toList();
        this.sources = sources.stream().map(Matcher::new).toList();
        this.action = action;
    }

    @Override
    public List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject,
                                               List<AdvisorySource.Advisory> advisories) {
        ComplianceGate.Attestation attestation = subject.attestation();
        if (attestation == null) {
            return List.of();
        }
        List<AttestationStatement> statements = AttestationStatement.parseAll(attestation.envelope());
        if (statements.isEmpty()) {
            return finding(attestation, "the referrer is not a readable in-toto attestation envelope");
        }
        // Every envelope of a .jsonl bundle must verify, bind and match, or a trusted first envelope would launder an
        // untrusted, replayed or wrong-builder one after it. The first failing envelope gates the whole referrer.
        for (AttestationStatement statement : statements) {
            List<ComplianceGate.Finding> findings = assess(attestation, statement);
            if (!findings.isEmpty()) {
                return findings;
            }
        }
        return List.of();
    }

    /** Verify one envelope - signature against a trust anchor, subject digest against the present artifact, builder and
     *  source against the expected values - returning the finding for the first check that fails, or empty. */
    private List<ComplianceGate.Finding> assess(ComplianceGate.Attestation attestation, AttestationStatement statement) {
        if (!statement.verifiedBy(keys)) {
            return finding(attestation, "its DSSE signature does not verify against any configured trust anchor "
                    + "(provenance-admission-key) - it was not signed by a trusted builder");
        }
        String digest = attestation.artifactDigest();
        Set<String> subjectDigests = statement.subjectDigests();
        // A verified signature and a matching builder prove only that some artifact was built as claimed; the
        // subject-digest binding proves it is this one, and an unconfirmable binding is never treated as verified -
        // otherwise a trusted attestation for a small artifact beside a large malicious jar is a universal replay
        // token.
        //
        // The binding is enforced where the artifact is present and its digest known. A sidecar admitted before its
        // artifact (artifactPresent == false) defers it to the artifact's publish. Builder and source are checked on
        // both legs.
        if (attestation.artifactPresent()) {
            if (digest == null) {
                // Present but too large to hash whole: the binding cannot be confirmed, so it is held.
                return finding(attestation, "this artifact is present but larger than the inspection window, so its "
                        + "digest could not be established to bind against the signed subject - an attestation whose "
                        + "binding to this artifact cannot be confirmed is not treated as its provenance");
            }
            if (subjectDigests.isEmpty()) {
                // No sha256 subject digest (an empty subject, or only sha512/gitCommit): nothing could bind it to any
                // artifact, so it would be a universal replay token.
                return finding(attestation, "it declares no sha256 subject digest to bind this artifact (sha256:"
                        + digest + ") against - an attestation whose binding to this artifact cannot be confirmed is "
                        + "not treated as its provenance");
            }
            if (!subjectDigests.contains(digest)) {
                return finding(attestation, "its signed subject digest does not match this artifact (sha256:" + digest
                        + ") - a valid attestation for a different artifact cannot be admitted here");
            }
        }
        if (!builders.isEmpty()) {
            String builder = statement.builderId().orElse(null);
            if (builder == null || builders.stream().noneMatch(matcher -> matcher.matches(builder))) {
                return finding(attestation, "its builder " + (builder == null ? "(none declared)" : builder)
                        + " is not among the expected builders (provenance-admission-builder)");
            }
        }
        if (!sources.isEmpty()) {
            List<String> uris = statement.sourceUris();
            if (uris.stream().noneMatch(uri -> sources.stream().anyMatch(matcher -> matcher.matches(uri)))) {
                return finding(attestation, "its source " + (uris.isEmpty() ? "(none declared)" : uris)
                        + " is not among the expected sources (provenance-admission-source)");
            }
        }
        return List.of();
    }

    private List<ComplianceGate.Finding> finding(ComplianceGate.Attestation attestation, String why) {
        // Worded for what was observed, since the disposition is the dial's: the reason for a refusal or hold, or for
        // letting it through under ALLOW.
        return List.of(new ComplianceGate.Finding(action,
                "Inbound attestation " + attestation.location() + " did not hold: " + why));
    }

    /** An expected-value matcher: an exact string or a trailing-{@code *} prefix, as the deny-list, version floor and
     *  private-name dimensions use, so {@code https://github.com/acme/*} matches a builder or source family. A blank
     *  entry or a bare {@code *} throws, so a bad save rolls back. */
    record Matcher(String pattern) {

        Matcher {
            pattern = pattern.strip();
            if (pattern.isEmpty() || pattern.equals("*")) {
                throw new IllegalArgumentException(
                        "an expected builder/source must be a value or a prefix, not blank or a bare '*': " + pattern);
            }
        }

        boolean matches(String value) {
            if (value == null) {
                return false;
            }
            return pattern.endsWith("*")
                    ? value.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.equals(value);
        }
    }
}
