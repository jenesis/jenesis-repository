package build.jenesis.repository.compliance;

import module java.base;

/**
 * What a deployment believes about signers: the key material a signature is checked against, and which signer a
 * coordinate's next version is expected to carry.
 *
 * <p>Both answers are durable state, and neither belongs to the boot-built gate. The screen has the request's scoped
 * store; the policy and the inspectors do not - which is the same split {@code HealthAware} already states for the
 * maintainer-health ledger, and this follows it rather than inventing a second shape. A deployment with no trust
 * module installed gets {@link #NONE}, under which every signature that verifies is reported {@code UNTRUSTED} rather
 * than {@code VALID}: absence of a trust store is not a reason to believe a signature, and it is emphatically not a
 * reason to report one as believed.
 *
 * <h2>Continuity is the default, and it is learned rather than configured</h2>
 *
 * The expensive part of signature checking has never been the cryptography; it is deciding which key ought to have
 * signed a thing. Curating that by hand per namespace is the high-assurance answer and it does not scale to a
 * dependency tree. So the default answer here is <em>continuity</em>: whoever signed this coordinate's earlier
 * versions is who the next one should carry, learned from what was actually ingested. That needs no configuration,
 * and it catches the attack that matters - an account or key taken over, publishing a version signed by something
 * new - on the first version after the takeover.
 *
 * <p>An operator's pins sit above it for the cases where being told after the fact is not good enough. A pinned
 * namespace is authoritative and continuity never overrides it.
 */
public interface SignerTrust {

    /**
     * The armoured public key material a signature of {@code scheme} is verified against, or empty when the
     * deployment holds none for it.
     *
     * <p>Empty is not "trust anything": a verifier handed no material reports every signature {@code UNTRUSTED},
     * which is the fail-closed direction. It is the caller's to decide what that warrants.
     */
    Optional<byte[]> material(String scheme);

    /**
     * Whether this identity is one the deployment trusts for the given coordinate at all - the union of what an
     * operator pinned for the namespace and what the coordinate's own history established.
     *
     * <p><b>Asked of the source whose material identified the signer</b>, never of an arbitrary one. Every
     * implementation answers on the assumption that the signer came from its own keys - "no pin narrows this
     * coordinate, so the key I hold stands", "this is a Debian coordinate and these are the Debian keys" - and that
     * assumption is what makes a scoped source scoped. A caller that verified against a pooled keyring and then asked
     * any source would get trust widened by exactly the composition meant to preserve it: a key admitted for one
     * ecosystem vouching for another. {@link #parts()} is how a caller keeps the two together.
     */
    boolean trusts(SignerIdentity signer, String ecosystem, String coordinate);

    /**
     * Whether this trust holds anchors that could vouch for a signer of {@code ecosystem}'s artifacts at all - the
     * deployment has stated whom it trusts there - as distinct from {@link #trusts}, which asks about one signer.
     * It is what turns a format's {@code REQUIRED_WHEN_TRUSTED} coverage into a requirement: an ecosystem whose own
     * trust runs through a signed index (Debian) treats an unsigned artifact as ordinary until an operator has
     * provisioned trusted signers for it, and from then on as a finding. A source answers for the ecosystems its
     * material speaks for - a format's own provisioned keyring for its ecosystem, an operator's deployment-wide
     * keyring for any - and a source that holds nothing answers {@code false}, which is the default.
     */
    default boolean anchored(String ecosystem) {
        return false;
    }

    /**
     * This trust as its independent sources, each holding its own key material and answering {@link #trusts} only for
     * what that material speaks for. A single source is itself; a {@link #composite} is its constituents.
     *
     * <p>A verifier walks these rather than a pooled keyring, so the source that identifies a signature is the source
     * that decides whether to believe it. Verification is then its own membership check and no source can vouch for a
     * key it does not hold.
     */
    default List<SignerTrust> parts() {
        return List.of(this);
    }

    /**
     * Where this source's key material came from, as a short name a finding carries beside the signer it
     * identified: {@code configured} for what an operator supplied (the default), a format's own provisioned
     * keyring by its name, {@code discovered} for a key a discovery pass fetched and nobody has admitted. What the
     * trust decided and where the key came from stay distinguishable that way - a verified signature by a
     * discovered key is reported as exactly that, and an operator reading the finding knows which dial admits it.
     */
    default String source() {
        return "configured";
    }

    /**
     * What this coordinate's earlier versions established <em>for one signature scheme</em>, or empty for a
     * coordinate never seen signed that way before.
     *
     * <p><b>The scheme is part of the question, and leaving it out was a defect.</b> An artifact may carry
     * signatures of several schemes at once - a Maven release carries a detached OpenPGP signature its layout
     * requires and may carry a Sigstore bundle beside it - and those are two independent facts about who signed.
     * With one expectation per coordinate the two overwrote each other, and whichever was recorded last made the
     * other read as a signer change: the continuity dial then held every properly signed release that happened to
     * carry both. Measured 2026-09-15 by the end-to-end Sigstore leg, which is the only tier where both signatures
     * of one artifact reach the gate in the order a client sends them.
     *
     * <p>Comparing an OpenPGP fingerprint against a keyless identity is a category error in any case: they are not
     * the same kind of name, so neither can contradict the other. What continuity means is "the same OpenPGP key
     * as last time" and "the same workflow as last time", asked separately.
     */
    Optional<Expectation> expected(String ecosystem, String coordinate, String scheme);

    /**
     * What a coordinate's history says about who signs it: the identity, how many versions carried it, and since
     * when. The counts are what let a screen say "this key has signed all 47 versions since March 2021" rather than
     * merely "unexpected", which is the difference between an operator who can act and one who cannot.
     *
     * @param pinned whether an operator named this identity outright, rather than it being learned from history - a
     *               pinned expectation is authoritative and a signer change against it is never auto-adopted
     */
    record Expectation(SignerIdentity signer, int versions, Instant since, boolean pinned) {

        public Expectation {
            Objects.requireNonNull(signer, "signer");
        }
    }

    /** Record that an accepted publish of this coordinate version carried this signer, so continuity is learned from
     *  what actually landed. Best-effort by nature: a lost observation delays an expectation being established, and
     *  can never turn an untrusted signer into a trusted one. */
    void observed(String ecosystem, String coordinate, String version, SignerIdentity signer, Instant when)
            throws IOException;

    /**
     * Record that a signature by this signer was met at {@code path} and no source held its key - the counterpart
     * of {@link #observed} for what could <em>not</em> be verified, which is what a key-discovery source needs to
     * know what to fetch. Best-effort like an observation, and nothing by default: a source that discovers nothing
     * ignores it, and no call here ever admits a signer.
     */
    default void wanted(SignerIdentity signer, String path, Instant when) throws IOException {
    }

    /**
     * {@link #wanted(SignerIdentity, String, Instant)}, with the {@linkplain Maintainer#ids identities} the
     * artifact's own metadata names as its maintainers - what a source that looks a key up by its owner rather
     * than by its id needs to know, and what a key found that way is bound to. The plain form by default, so a
     * source that discovers by id alone ignores them.
     */
    default void wanted(SignerIdentity signer, String path, Set<String> maintainers, Instant when)
            throws IOException {
        wanted(signer, path, when);
    }

    /** The deployment that holds no trust material and has learned nothing - every signature reports
     *  {@code UNTRUSTED}, nothing is expected, and observations go nowhere. */
    SignerTrust NONE = new SignerTrust() {

        @Override
        public Optional<byte[]> material(String scheme) {
            return Optional.empty();
        }

        @Override
        public boolean trusts(SignerIdentity signer, String ecosystem, String coordinate) {
            return false;
        }

        @Override
        public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
            return Optional.empty();
        }

        @Override
        public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer,
                             Instant when) {
        }
    };

    /**
     * The union of several sources of trust, because trust legitimately arrives from more than one place.
     *
     * <p>An operator's configured keyring is one. A format that carried its own operator-provisioned keyring before
     * this dimension existed is another, and its keys have to keep working without becoming global - the Debian one
     * is exactly that, admitted for {@code .deb} packages and for nothing else. Continuity, when it lands, is a
     * third. Requiring a single provider would force those into one class that knew about all of them, which is the
     * shape this codebase removes elsewhere.
     *
     * <p>The composition rules follow from what each answer means:
     *
     * <ul>
     *   <li><b>Material unions</b>, for a caller that only wants to know whether the deployment holds anything at
     *       all. It is deliberately <em>not</em> what a verifier should use: pooling the keys and then asking any
     *       source about the signer is how a composition widens trust, since each source answers on the assumption
     *       that the signer came from its own keys. A verifier walks {@link #parts()} instead.</li>
     *   <li><b>Trust is an OR over sources that each answer only for their own material.</b> That is not a weakening
     *       when the caller keeps identification and decision together: the Debian keyring answers for Debian
     *       coordinates and holds only the keys an operator put in it, so a union of scoped sources is still
     *       scoped.</li>
     *   <li><b>Expectation takes the first answer</b>, so a source that knows a coordinate's history speaks before
     *       one that merely holds a key; an empty answer defers to the next.</li>
     *   <li><b>Observation fans out</b> to every source, so whichever one learns continuity sees the publish.</li>
     * </ul>
     *
     * <p>No source is {@link #NONE} - the fail-closed direction, and the same answer an uninstalled module gives.
     */
    static SignerTrust composite(List<SignerTrust> sources) {
        // Flattened: a composite handed in contributes its sources, never itself. The parts a caller chooses among
        // - the inspector picking the source whose material holds a signer, then asking that source's pins - must be
        // the sources themselves; a nested composite among them has a pooled material and answers anyone's trusts,
        // which is exactly the widening choosing by source exists to prevent. Measured 2026-09-14 with two providers
        // installed: the provider's own composite sat as one part beside the format keyring's, so a signature the
        // provenance part admitted was recorded as admitted by "configured" - the composite's default source.
        List<SignerTrust> present = sources.stream()
                .filter(Objects::nonNull)
                .flatMap(source -> source.parts().stream())
                .filter(Objects::nonNull)
                .filter(source -> source != NONE)
                .toList();
        if (present.isEmpty()) {
            return NONE;
        }
        if (present.size() == 1) {
            return present.getFirst();
        }
        return new Composite(present);
    }

    /** The {@link #composite} implementation, named rather than anonymous so a stack trace says which trust answered. */
    record Composite(List<SignerTrust> sources) implements SignerTrust {

        @Override
        public List<SignerTrust> parts() {
            return sources;
        }

        @Override
        public Optional<byte[]> material(String scheme) {
            List<byte[]> held = sources.stream()
                    .map(source -> source.material(scheme))
                    .flatMap(Optional::stream)
                    .filter(material -> material.length > 0)
                    .toList();
            if (held.isEmpty()) {
                return Optional.empty();
            }
            // Joined by a newline rather than run together: an armoured block ends with a checksum line, and a
            // following BEGIN sharing that line is not a block any decoder will read.
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for (byte[] material : held) {
                if (joined.size() > 0) {
                    joined.write('\n');
                }
                joined.writeBytes(material);
            }
            return Optional.of(joined.toByteArray());
        }

        @Override
        public boolean trusts(SignerIdentity signer, String ecosystem, String coordinate) {
            return sources.stream().anyMatch(source -> source.trusts(signer, ecosystem, coordinate));
        }

        @Override
        public boolean anchored(String ecosystem) {
            return sources.stream().anyMatch(source -> source.anchored(ecosystem));
        }

        @Override
        public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
            return sources.stream()
                    .map(source -> source.expected(ecosystem, coordinate, scheme))
                    .flatMap(Optional::stream)
                    .findFirst();
        }

        @Override
        public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer,
                             Instant when) throws IOException {
            for (SignerTrust source : sources) {
                source.observed(ecosystem, coordinate, version, signer, when);
            }
        }

        @Override
        public void wanted(SignerIdentity signer, String path, Instant when) throws IOException {
            for (SignerTrust source : sources) {
                source.wanted(signer, path, when);
            }
        }

        @Override
        public void wanted(SignerIdentity signer, String path, Set<String> maintainers, Instant when)
                throws IOException {
            for (SignerTrust source : sources) {
                source.wanted(signer, path, maintainers, when);
            }
        }
    }
}
