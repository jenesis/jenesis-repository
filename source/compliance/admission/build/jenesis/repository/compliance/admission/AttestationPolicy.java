package build.jenesis.repository.compliance.admission;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.Verdict;

/**
 * The inbound-provenance admission dimension of the gate: it verifies the attestation an inspector stamped onto a
 * subject against the tenant's trust anchor and expected-builder / expected-source policy, and raises a finding at the
 * configured {@code action} when it does not hold - so an artifact whose provenance is unsigned by a trusted key,
 * signed for a different artifact, or built by an unexpected builder or from an unexpected source is held (default
 * {@link Verdict#QUARANTINE}) or blocked ({@link Verdict#REJECT}), while one carrying a well-formed, trusted, matching
 * attestation is admitted and keeps its referrer served beside it. The checks in order: the referrer parses as an
 * in-toto DSSE envelope; its signature verifies against a configured trust anchor; its signed subject digest binds to
 * this artifact once the artifact is present (a sidecar admitted before its artifact lands defers the binding, which
 * is re-checked when the artifact publishes; once the artifact IS present a binding that does not hold - a mismatched
 * subject, a subject carrying no sha256 digest, or an artifact too large to hash whole - is raised, never silently
 * admitted, so a valid attestation for a different artifact is not a replay token); and its provenance builder and
 * source match the expected
 * values (each optional - unset accepts any). The shared advisory lookup is not consulted - an attestation is
 * verified, not fed. A subject carrying no attestation yields nothing, so this dimension is invisible to every
 * artifact that ships without one.
 *
 * <p>An {@link Verdict#ALLOW} action permits rather than switches off: the dimension is still built, still carried by
 * the gate, and still <em>verifies</em> - an envelope that does not hold is reported as a {@code Finding(ALLOW, ...)}
 * saying what failed, so a permitted artifact's assessment tells an incident review that the attestation was checked
 * and found wanting rather than reading exactly like one whose provenance verified. It is the shape the
 * known-exploited, private-name, version-floor, embedded-secret and maintainer-health dimensions take under their own
 * ALLOW. Stopping the check is the {@code jenreg.provenance-admission} toggle or an unset trust anchor,
 * and those leave the dimension genuinely absent from the gate rather than present-and-permitting, which is the
 * distinction an operator needs the two spellings to keep.
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
        // A .jsonl bundle carries several envelopes; every one must verify, bind and match - not just the first - or a
        // trusted first envelope would launder an untrusted, replayed or wrong-builder one appended after it. The
        // first envelope that fails gates the whole referrer, exactly as a single failing envelope does (Principles
        // 5, 9): a bundle is admitted only when every envelope in it holds.
        for (AttestationStatement statement : statements) {
            List<ComplianceGate.Finding> findings = assess(attestation, statement);
            if (!findings.isEmpty()) {
                return findings;
            }
        }
        return List.of();
    }

    /** Verify one envelope of the referrer: its signature against a trust anchor, its signed subject digest against
     *  this artifact once present, and its provenance builder and source against the expected values - returning the
     *  gate finding for the first check that does not hold, or empty when the envelope holds. */
    private List<ComplianceGate.Finding> assess(ComplianceGate.Attestation attestation, AttestationStatement statement) {
        if (!statement.verifiedBy(keys)) {
            return finding(attestation, "its DSSE signature does not verify against any configured trust anchor "
                    + "(provenance-admission-key) - it was not signed by a trusted builder");
        }
        String digest = attestation.artifactDigest();
        Set<String> subjectDigests = statement.subjectDigests();
        // A verified signature and a matching builder/source prove only that SOME artifact was built as claimed - not
        // that it is THIS one. That link is the subject-digest binding, and a can't-verify here is never a verified
        // (Principles 5, 9): without it, a genuine trusted-builder attestation for a different, small artifact uploaded
        // beside a large malicious jar is a universal replay token.
        //
        // The binding is enforced at the point the ARTIFACT is present - the leg on which its real digest is known.
        // A sidecar admitted before its artifact lands (artifactPresent == false) carries no digest yet: its binding
        // is deferred and re-checked when the artifact publishes, so it is not held on binding grounds here - matching
        // the screen's deferred-binding model. Builder and source below are properties of the attestation itself and
        // are checked on both legs regardless.
        if (attestation.artifactPresent()) {
            if (digest == null) {
                // The artifact IS present but too large to hash whole (>=32 MiB: most models, fat-jars, containers),
                // so its binding cannot be confirmed. Held, never admitted as verified provenance - this is the
                // over-window arm of the replay hole (a foreign attestation beside a large jar).
                return finding(attestation, "this artifact is present but larger than the inspection window, so its "
                        + "digest could not be established to bind against the signed subject - an attestation whose "
                        + "binding to this artifact cannot be confirmed is not treated as its provenance");
            }
            if (subjectDigests.isEmpty()) {
                // The statement declares no sha256 subject digest to bind against (an empty subject, or one carrying
                // only a non-sha256 digest such as sha512 / gitCommit). Nothing can ever bind it to this artifact, so
                // a trusted DSSE with such a subject would otherwise be a universal replay token.
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
        // Worded for what was OBSERVED rather than for the disposition, because the disposition is the dial's: under
        // REJECT/QUARANTINE this reads as the reason the artifact was refused or held, and under ALLOW as the reason
        // it was let through anyway.
        return List.of(new ComplianceGate.Finding(action,
                "Inbound attestation " + attestation.location() + " did not hold: " + why));
    }

    /** An expected-value matcher: an exact string or a trailing-{@code *} prefix, the same shape the operator
     *  deny-list, version floor and private-name dimensions use, so {@code https://github.com/acme/*} matches a
     *  whole builder or source family. A blank entry or a bare {@code *} throws, so a bad settings save rolls back. */
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
