package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * What one {@link SignatureScheme} needs supplied for the shared {@link SignatureSchemeContract} to run over it: a
 * signature of the scheme over known bytes, the trust material that holds its signer, material of the same kind
 * that does not, and the identity the scheme must name. Everything scheme-specific - the key minted, the chain
 * issued, the fake log that signed the bundle - lives in the fixture; the checks never learn which scheme they are
 * driving.
 *
 * <p>A fixture builds nothing in its constructor: the census instantiates every fixture merely to read the provider
 * class it claims, and the driver calls {@link #start()} once before the checks.
 */
public interface SignatureSchemeFixture {

    /** The fully qualified {@link SignatureScheme} implementation class this fixture covers, as the census reads it
     *  out of the provider module's {@code provides ... with ...} clause. */
    String schemeClass();

    /**
     * The scheme under test: the instance {@code ServiceLoader} constructed, looked up by the class this fixture
     * claims. A fixture does not instantiate it - the discovered instance is the only one the inspector ever
     * reaches - and the lookup doubles as a check that the fixture's test module really roots the provider's module.
     */
    default SignatureScheme scheme() {
        return SignatureScheme.installed().stream()
                .filter(scheme -> scheme.getClass().getName().equals(schemeClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(schemeClass() + " is not discoverable in this module graph; "
                        + "the fixture's test module must require the module that provides it."));
    }

    /** The scheme the provider must name - the constant a format puts on its evidence. */
    ArtifactSignatures.Scheme declared();

    /** Mint the material. Called once, before any check. */
    default void start() throws Exception {
    }

    /** The bytes the fixture's signature covers. */
    byte[] covered();

    /** Bytes the signature does not cover - the same artifact altered. */
    byte[] tampered();

    /** The fixture's signature as evidence of {@link #declared()}, over {@link #covered()}. */
    ArtifactSignatures.Evidence evidence();

    /** Evidence that is not of this scheme at all - other bytes in the signature's place, a location naming no
     *  signature member, a signer that is no chain - which the scheme must refuse to read. */
    ArtifactSignatures.Evidence unrecognisable();

    /** Trust material of the scheme's {@linkplain SignatureScheme#material() kind} that holds the fixture's signer. */
    byte[] material();

    /** Trust material of the same kind holding another signer, and never the fixture's. */
    byte[] foreign();

    /** The identity the scheme must name for the fixture's signature - in the scheme's own vocabulary, spelled as
     *  a pin would spell it. */
    SignerIdentity signer();

    /** A {@link ArtifactSignatures.Signed} over an array, opened afresh on each call. */
    static ArtifactSignatures.Signed over(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }
}
