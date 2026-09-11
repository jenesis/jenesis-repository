package build.jenesis.repository.format;

import module java.base;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * The capability a {@code RepositoryFormat} implements when its artifacts carry a <em>publisher's</em> signature - the
 * detached OpenPGP {@code .asc} beside a Maven artifact, the {@code _gpgorigin} member inside a {@code .deb}, an RPM
 * header signature, a NuGet {@code .signature.p7s}, a cosign bundle. Detected by {@code instanceof}, like
 * {@link ArtifactLayout} and {@code ProxyFormat}, so a format opts in without a core edit.
 *
 * <p>It is deliberately <b>not</b> about the signatures this repository <em>produces</em>. A signed {@code InRelease},
 * {@code repomd.xml.asc} or {@code SHA256SUMS.sig} is this deployment vouching for its own index to a client, and the
 * format owns that end to end. This seam is the opposite direction: material someone else produced, which arrives with
 * an artifact and which the deployment must decide whether to believe.
 *
 * <h2>Why the format supplies evidence rather than a path</h2>
 *
 * The naive seam - "tell me where the signature file is" - is Maven's shape mistaken for everyone's. Four installed
 * formats answer <em>what is signed</em> four different ways: a Maven {@code .asc} commits to the artifact's own bytes;
 * a {@code .deb}'s {@code _gpgorigin} commits to the concatenation of the archive's <em>other</em> {@code ar} members
 * in archive order; an RPM header signature commits to the header blob alone; a NuGet {@code .signature.p7s} commits
 * to the package's content manifest rather than to the package. A seam that returns a path can express the first and
 * none of the rest, and a verifier built on one would acquire Maven's shape permanently.
 *
 * <p>So a format returns {@link Evidence}: the signature bytes, and a {@linkplain Signed reopenable stream of exactly
 * the bytes that signature commits to}. Those are the two things every scheme has and the only two that differ per
 * format. Verification, trust, grading and indexing sit above this seam and name no ecosystem.
 *
 * <p>{@link Signed} being reopenable rather than a {@code byte[]} is what keeps &sect;1 intact: a package is unbounded,
 * a signature is kilobytes, and the two-pass read ({@code _gpgorigin} lifted first, the large members streamed into the
 * verifier afterwards) is how a multi-gigabyte {@code .deb} is verified in bounded heap today.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@code RepositoryFormat}, so that contract still binds and the clauses below state
 * what producing inbound signature evidence adds. The format testkit's {@code FormatContract} proves them over every
 * implementation, and its census holds <em>every</em> installed format to either implementing this or declaring in its
 * fixture, with a reason, that it has no inbound signature story - so a format is never silently skipped.
 * <ol>
 * <li><b>Thread-safety.</b> Both methods are stateless reads on the format singleton, called concurrently from publish
 *     threads, from proxy fetches and from the background walk; an implementation keeps no per-call state on itself.
 *     </li>
 * <li><b>Absence sentinel.</b> {@link #expects} answers an <em>empty list</em> for a path that is not a signable
 *     artifact of this format (an index document, a checksum sibling, a signature itself) and {@link #evidence} answers
 *     an empty list when the artifact carries no signature material; {@code null} is never returned. An empty
 *     {@code evidence} for a path that {@code expects} something is the ordinary "unsigned" outcome and is the caller's
 *     to judge, never this seam's.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #expects} derives from the request path <em>alone</em> - no store read, no
 *     blob opened - so a serving read path may call it freely. {@link #evidence} reads only through the
 *     {@link Material} it is handed and writes nothing.</li>
 * <li><b>Streaming (&sect;1).</b> {@link #evidence} never materialises an artifact body. It may read the small
 *     signature material whole - bounded by {@link Material#LARGEST_SIGNATURE} - and must express everything else as a
 *     {@link Signed} the caller opens. A format that buffered the body to compose {@code signed} would put the package
 *     on the publish thread's heap, which is the defect this shape exists to prevent.</li>
 * <li><b>Bounded work (&sect;12).</b> Every sibling read goes through {@link Material#sibling} with an explicit limit
 *     and a short answer is reported rather than assumed whole; an archive walked to find an embedded member is walked
 *     iteratively under the format's own inflation bound. Reaching a bound is an outcome - the evidence is simply not
 *     produced - never a truncated signature offered as a whole one.</li>
 * <li><b>Error visibility (&sect;9).</b> Material that is <em>present but unreadable</em> - a truncated sidecar, an
 *     archive that will not parse, a store failure - propagates as an {@link IOException}. It must never be folded into
 *     an empty list, because the caller distinguishes "carries no signature" from "we could not read the signature",
 *     and collapsing the second into the first turns a tampered artifact into an unsigned one.</li>
 * <li><b>Determinism.</b> Two calls over unchanged stored bytes produce equal evidence in equal order, so a re-screen
 *     of the same artifact reaches the same verdict. Order is the format's to fix (archive order, sidecar order); it is
 *     never discovery order.</li>
 * <li><b>Traversal refusal.</b> A path composed to reach signature material is screened exactly as
 *     {@link ArtifactLayout#addressable} screens a coordinate: material is read through {@link Material}, whose paths
 *     are request paths, so a format composes no key of its own and a path it cannot screen yields no evidence.</li>
 * </ol>
 */
public interface ArtifactSignatures extends EcosystemLayout {

    /**
     * The signature schemes this format's artifacts can carry, and whether each is required, for the artifact at this
     * request path - empty when the path names no signable artifact of this format.
     *
     * <p>A format states this rather than inheriting a default, because {@link Coverage#REQUIRED} for Maven (whose
     * upstream has demanded a signature on every release for over a decade), {@link Coverage#OPTIONAL} for npm and
     * "nothing at all" for Go (whose integrity is a transparency log rather than a signature) are three different
     * product answers. A silent default would be a claim no one made.
     */
    List<Expectation> expects(String path);

    /**
     * The artifact a separately-addressed piece of signature material covers, for a format whose material is its own
     * request path - {@code foo-1.0.jar} for {@code foo-1.0.jar.asc}. Empty by default, which is the right answer for
     * every embedded scheme: a {@code .deb}'s signature has no path of its own.
     *
     * <p>It exists because such material typically arrives <em>after</em> the artifact it covers - a Maven deploy is
     * several requests - so the caller needs to know which stored artifact a just-published sidecar completes.
     */
    default Optional<String> covers(String path) {
        return Optional.empty();
    }

    /**
     * The inbound signature material for the artifact at this request path: each signature, and the bytes it commits
     * to. The caller verifies; this only produces.
     *
     * @throws IOException when material is present but could not be read (clause 6) - never for material that is
     *                     simply absent
     */
    List<Evidence> evidence(String path, Material material) throws IOException;

    /** A signature scheme, named by what a verifier must know to check it. */
    enum Scheme {

        /** A detached OpenPGP signature over the signed bytes ({@code .asc}, {@code _gpgorigin}, {@code .prov}). */
        OPENPGP_DETACHED,

        /** An OpenPGP clearsigned document, the signature inline with the text it covers. */
        OPENPGP_CLEARSIGNED,

        /** A PKCS#7 / CMS signed-data structure with its certificate chain (NuGet's {@code .signature.p7s}). */
        PKCS7,

        /** A Sigstore bundle - a DSSE envelope with a Fulcio certificate and a Rekor inclusion proof. */
        SIGSTORE_BUNDLE,

        /** A bare DSSE envelope over an in-toto statement. */
        DSSE
    }

    /** Whether a scheme's signature must be present for the artifact to be considered properly signed. */
    enum Coverage {

        /** The artifact is expected to carry one; its absence is a fact the caller may gate on. */
        REQUIRED,

        /** The artifact may carry one; its absence says nothing. */
        OPTIONAL
    }

    /** One scheme this format's artifact at a path may carry, and whether it is expected to. */
    record Expectation(Scheme scheme, Coverage coverage) {

        public Expectation {
            Objects.requireNonNull(scheme, "scheme");
            Objects.requireNonNull(coverage, "coverage");
        }

        /** A scheme the artifact is expected to carry. */
        public static Expectation required(Scheme scheme) {
            return new Expectation(scheme, Coverage.REQUIRED);
        }

        /** A scheme the artifact may carry. */
        public static Expectation optional(Scheme scheme) {
            return new Expectation(scheme, Coverage.OPTIONAL);
        }
    }

    /**
     * One signature and the bytes it commits to: the {@code signature} as the scheme encodes it, a {@link Signed} the
     * caller opens to feed a verifier, and the {@code location} the material was found at - a sibling request path, or
     * a name inside the artifact - which is what an operator reads when a verification fails.
     */
    record Evidence(Scheme scheme, byte[] signature, Signed signed, String location) {

        public Evidence {
            Objects.requireNonNull(scheme, "scheme");
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(signed, "signed");
            Objects.requireNonNull(location, "location");
        }
    }

    /**
     * The bytes a signature commits to, opened afresh on each call so a verifier can read them without anyone holding
     * them. The caller owns and closes each returned stream.
     *
     * <p>This is the whole reason the seam is general. A sidecar scheme returns the artifact's own body; a {@code .deb}
     * returns a stream that concatenates the archive's non-signature members in archive order; an RPM returns the
     * header blob. One verifier reads all three and knows about none of them.
     */
    @FunctionalInterface
    interface Signed {

        InputStream open() throws IOException;
    }

    /**
     * The bounded reader a format reads its signature material through - the artifact's own body, and the already
     * published siblings beside it. Supplied by the caller and adapted per ingress leg, so a format never reaches into
     * the store and the caller's existing read bounds keep governing.
     */
    interface Material {

        /**
         * The most of a signature a format may read into heap. A detached OpenPGP signature is a few hundred bytes, a
         * PKCS#7 structure with its chain a few kilobytes; a megabyte is generous, and it is the bound
         * {@code DebianSignature} already applies to a {@code _gpgorigin} member so a hostile archive declaring a huge
         * one cannot exhaust the verifier.
         */
        int LARGEST_SIGNATURE = 1024 * 1024;

        /**
         * Up to {@code limit} bytes of the already-published sibling at this request path, or empty when nothing is
         * published there. A sibling longer than the limit comes back flagged
         * {@linkplain PublishInterceptor.Content.Bounded#truncated() truncated} rather than raising, so a format
         * declines to produce evidence rather than asserting something about a prefix.
         *
         * <p>The bounded pair is the free store contract's own record rather than a third copy of two values that
         * already exist twice in this build (&sect;8).
         */
        Optional<PublishInterceptor.Content.Bounded> sibling(String path, int limit) throws IOException;

        /**
         * The artifact's own bytes, reopenable, or empty when the body is not available at this inspection - a sidecar
         * published before the artifact it names. A format that cannot see the body produces no evidence; the caller
         * knows the difference between that and an artifact that carries none.
         */
        Optional<Signed> body();
    }

    /**
     * The ordinary case, stated once: a detached signature at {@code <path><suffix>}, covering the artifact's own
     * bytes. A format delegates to this in one line rather than restating the convention.
     *
     * <p>It is here rather than per format for the reason {@code ServableNames} already gives for owning the sidecar
     * suffixes centrally - every path-addressed ecosystem spells this the same way, and a format that had to remember
     * the convention is a format that will forget it. What a format still states for itself is {@code signable} and
     * {@code coverage}, because those are the per-format product decisions the convention cannot make.
     *
     * @param ecosystem the delegating format's {@link EcosystemLayout#ecosystem()}
     * @param suffix    the sidecar suffix, including the dot
     * @param scheme    the scheme the sidecar encodes
     * @param signable  whether a request path names an artifact of this format that a signature would cover
     * @param coverage  whether such an artifact is expected to carry one
     */
    static ArtifactSignatures detachedSidecar(String ecosystem, String suffix, Scheme scheme,
                                              Predicate<String> signable, Coverage coverage) {
        Objects.requireNonNull(ecosystem, "ecosystem");
        Objects.requireNonNull(signable, "signable");
        Expectation expectation = new Expectation(scheme, coverage);
        if (suffix.isEmpty() || !suffix.startsWith(".")) {
            throw new IllegalArgumentException("A sidecar suffix begins with a dot: " + suffix);
        }
        return new ArtifactSignatures() {

            @Override
            public String ecosystem() {
                return ecosystem;
            }

            @Override
            public List<Expectation> expects(String path) {
                return signable.test(path) && !path.endsWith(suffix) ? List.of(expectation) : List.of();
            }

            @Override
            public Optional<String> covers(String path) {
                // Exactly one suffix is stripped and the result is never re-examined, which is the rule
                // ServableNames.subject already fixes for the sidecar family: a chain terminates in one step.
                return path.length() > suffix.length() && path.endsWith(suffix)
                        ? Optional.of(path.substring(0, path.length() - suffix.length()))
                        : Optional.empty();
            }

            @Override
            public List<Evidence> evidence(String path, Material material) throws IOException {
                if (!signable.test(path) || path.endsWith(suffix)) {
                    return List.of();
                }
                Optional<Signed> body = material.body();
                if (body.isEmpty()) {
                    return List.of();   // the artifact is not here yet; there is nothing for a signature to cover
                }
                Optional<PublishInterceptor.Content.Bounded> sidecar =
                        material.sibling(path + suffix, Material.LARGEST_SIGNATURE);
                if (sidecar.isEmpty()) {
                    return List.of();
                }
                if (sidecar.get().truncated()) {
                    // Present but unreadable (clause 6): a signature longer than the bound is not a signature we can
                    // check, and reporting "unsigned" here would read a suspicious artifact as a merely unsigned one.
                    throw new IOException("Signature at " + path + suffix + " exceeds "
                            + Material.LARGEST_SIGNATURE + " bytes");
                }
                return List.of(new Evidence(scheme, sidecar.get().content(), body.get(), path + suffix));
            }
        };
    }
}
