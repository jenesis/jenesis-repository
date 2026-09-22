package build.jenesis.repository.compliance;

/**
 * A {@link QualityInspector} whose signer trust can be rebound - the seam the screen uses to overlay a per-tenant
 * {@link SignerTrust} onto an inspector that verifies inbound signatures, without naming it.
 *
 * <p>It exists for exactly the reason {@link HealthAware} exists on the dimension side, and is written to match it:
 * the screen has the request's scoped store, and so the durable trust material; the {@link java.util.ServiceLoader}
 * -built inspector does not. An inspector that verifies nothing simply never implements this and the screen leaves it
 * untouched.
 *
 * <p>Why the <em>inspector</em> rather than the dimension: verification needs the artifact bytes, the signature and
 * the key at the same moment, and only the inspector is ever handed the bytes. A dimension is pure by contract and
 * performs no I/O, so it decides from what the inspector stamped on the subject. Splitting it the other way would
 * mean either re-implementing a signature format over a precomputed digest, or buffering artifacts on the publish
 * thread - a hand-rolled crypto primitive or a heap bound broken, and neither is worth the symmetry.
 */
public interface TrustAware {

    /** This inspector verifying against {@code trust} instead of the one it was built with. Returns the rebound
     *  inspector; the receiver is unchanged, as the dimensions' equivalent seam is. */
    QualityInspector withTrust(SignerTrust trust);
}
