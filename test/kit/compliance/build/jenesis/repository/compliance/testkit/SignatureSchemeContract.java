package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The executable {@link SignatureScheme} contract: one body of checks every scheme's verifier runs through a
 * {@link SignatureSchemeFixture}, so a property of verification is stated once and proven per scheme rather than
 * re-asserted, differently, in a hand-written suite per library. Each {@link Property} names one documented
 * contract clause; {@link #checks()} binds them to a fixture's material.
 *
 * <p>Assertion-library-free on purpose, like the sibling kits: a check throws {@link AssertionError} naming the
 * scheme, the property and the expectation, and the JUnit driver lives under {@code test/**}.
 *
 * <h2>What the bound check actually proves</h2>
 * {@link Property#A_BOUND_RIDES_OUT} hands the verifier a covered stream that raises a marker {@link IOException}
 * part-way and requires that exact exception back. It exists because three of the verifiers used to catch
 * {@code RuntimeException} around their body read, and the inspector's read bound was an unchecked exception: an
 * artifact that outran the bound came back INVALID from those three and "not verified here" from the others. The
 * bound is a checked {@link IOException} now, and this check is what keeps it riding out of every scheme.
 *
 * @jenesis.covers build.jenesis.repository.compliance.SignatureScheme 1, 2, 3, 4, 5, 6, 7
 */
public final class SignatureSchemeContract {

    /** One documented contract clause of {@link SignatureScheme}. */
    public enum Property {

        /** Clause 1: the provider names the scheme the fixture declares, a material kind a {@link SignerIdentity}
         *  can carry, and a reason for evidence it does not recognise. */
        NAMES_ITS_SCHEME,

        /** Clause 2: the fixture's evidence reads; evidence not of this scheme answers empty, or raises, and is never
         *  read as a signature. */
        READS_ONLY_ITS_OWN_MATERIAL,

        /** Clause 3: the material holding the signer is held by, foreign, empty and absent material are not. */
        HOLDER_IS_CHOSEN_BY_MATERIAL,

        /** Clause 4: VALID over the covered bytes with the holder's material, INVALID over other bytes, NO_KEY with
         *  foreign material and with none - never VALID without a key, never a throw. */
        VERIFIES_OVER_THE_COVERED_BYTES,

        /** Clause 5: the verification names the fixture's signer, in the material's identity scheme, whatever the
         *  result and whether or not material was held. */
        NAMES_THE_SIGNER,

        /** Clause 6: a VALID verification is graded and carries its key's algorithm and size and its digest. */
        GRADES_WHAT_VERIFIED,

        /** Clause 7: an {@link IOException} the covered stream raises rides out of {@code verify} unchanged. */
        A_BOUND_RIDES_OUT
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The body of a {@link Check}, run against a started fixture. */
    @FunctionalInterface
    public interface Body {
        void run(SignatureSchemeFixture fixture) throws Exception;
    }

    /** One deliberately broken substitution for the scheme a property's check must fail against, and why. */
    public record Mutation(SignatureSchemeMutant mutant, String why) {

        public Mutation {
            Objects.requireNonNull(mutant, "mutant");
            Objects.requireNonNull(why, "why");
        }
    }

    /** The marker the bound check raises from the covered stream, and requires back. */
    public static final class Bound extends IOException {

        public Bound() {
            super("the covered stream was cut by the kit");
        }
    }

    private SignatureSchemeContract() {
    }

    /** Every check, in clause order. */
    public static List<Check> checks() {
        return List.of(
                new Check(Property.NAMES_ITS_SCHEME, "names the declared scheme, a material kind and a reason",
                        SignatureSchemeContract::namesItsScheme),
                new Check(Property.READS_ONLY_ITS_OWN_MATERIAL, "reads its own evidence and refuses other material",
                        SignatureSchemeContract::readsOnlyItsOwnMaterial),
                new Check(Property.HOLDER_IS_CHOSEN_BY_MATERIAL, "is held by the material holding its signer only",
                        SignatureSchemeContract::holderIsChosenByMaterial),
                new Check(Property.VERIFIES_OVER_THE_COVERED_BYTES,
                        "VALID over the covered bytes, INVALID over others, NO_KEY without the key",
                        SignatureSchemeContract::verifiesOverTheCoveredBytes),
                new Check(Property.NAMES_THE_SIGNER, "names the signer in the material's identity scheme",
                        SignatureSchemeContract::namesTheSigner),
                new Check(Property.GRADES_WHAT_VERIFIED, "grades a VALID verification and says what key made it",
                        SignatureSchemeContract::gradesWhatVerified),
                new Check(Property.A_BOUND_RIDES_OUT, "an IOException from the covered stream rides out unchanged",
                        SignatureSchemeContract::aBoundRidesOut));
    }

    /** The mutations a property's check must fail against. Every property has one: a check nothing falsifies is a
     *  claim, and the driver's falsification leg runs each of these. */
    public static List<Mutation> mutations(Property property) {
        return switch (property) {
            case NAMES_ITS_SCHEME -> List.of(new Mutation(SignatureSchemeMutant.A_SCHEME_MISNAMED,
                    "a provider naming another scheme would be looked up for evidence it cannot read"));
            case READS_ONLY_ITS_OWN_MATERIAL -> List.of(new Mutation(
                    SignatureSchemeMutant.A_READER_THAT_RECOGNISES_ANYTHING,
                    "a reader that answers a reading for any bytes turns every unreadable sidecar into a verdict"));
            case HOLDER_IS_CHOSEN_BY_MATERIAL -> List.of(new Mutation(
                    SignatureSchemeMutant.A_HOLDER_THAT_CLAIMS_EVERY_MATERIAL,
                    "a probe that claims every material makes the first trust source speak for every signer"));
            case VERIFIES_OVER_THE_COVERED_BYTES -> List.of(new Mutation(
                    SignatureSchemeMutant.A_VERIFIER_THAT_ANSWERS_VALID,
                    "a verifier answering VALID for other bytes or no key is the defect this whole dimension exists "
                            + "to refuse"));
            case NAMES_THE_SIGNER -> List.of(new Mutation(SignatureSchemeMutant.A_SIGNER_OF_ANOTHER_SCHEME,
                    "a signer named in another scheme's vocabulary matches no pin an operator can write"));
            case GRADES_WHAT_VERIFIED -> List.of(new Mutation(SignatureSchemeMutant.A_GRADE_OF_NOTHING,
                    "an unassessed grade on a verified signature reads on the screens as material nobody could read"));
            case A_BOUND_RIDES_OUT -> List.of(new Mutation(
                    SignatureSchemeMutant.A_VERIFIER_THAT_SWALLOWS_THE_BOUND,
                    "a bound turned into INVALID holds a well-signed artifact against its own signature"));
        };
    }

    private static void namesItsScheme(SignatureSchemeFixture fixture) {
        SignatureScheme scheme = fixture.scheme();
        require(scheme.scheme() == fixture.declared(), fixture, "names the scheme its fixture declares, "
                + fixture.declared() + ", not " + scheme.scheme());
        try {
            new SignerIdentity(scheme.material(), "probe");
        } catch (RuntimeException notAScheme) {
            throw new AssertionError(name(fixture) + ": material() must be an identity scheme token a pin can carry; '"
                    + scheme.material() + "' is not", notAScheme);
        }
        require(scheme.unrecognised() != null && !scheme.unrecognised().isBlank(), fixture,
                "says what evidence it does not recognise was not");
    }

    private static void readsOnlyItsOwnMaterial(SignatureSchemeFixture fixture) throws IOException {
        SignatureScheme scheme = fixture.scheme();
        require(scheme.read(fixture.evidence()).isPresent(), fixture, "reads the fixture's evidence");
        try {
            require(scheme.read(fixture.unrecognisable()).isEmpty(), fixture,
                    "answers empty for evidence that is not of its scheme, never a reading");
        } catch (IOException raised) {
            // Tolerated by clause 2: a stream the reader cannot read at all may raise, and the inspector reports
            // that as unreadable material. What it may not do is answer a reading.
        }
    }

    private static void holderIsChosenByMaterial(SignatureSchemeFixture fixture) throws IOException {
        SignatureScheme.Reading reading = reading(fixture);
        require(reading.heldBy(fixture.material()), fixture, "is held by the material that holds its signer");
        require(!reading.heldBy(fixture.foreign()), fixture, "is not held by material holding another signer");
        require(!reading.heldBy(null), fixture, "is not held by absent material");
        require(!reading.heldBy(new byte[0]), fixture, "is not held by empty material");
    }

    private static void verifiesOverTheCoveredBytes(SignatureSchemeFixture fixture) throws Exception {
        SignatureScheme.Reading reading = reading(fixture);
        Instant now = Instant.now();
        require(reading.verify(SignatureSchemeFixture.over(fixture.covered()), fixture.material(), now).result()
                == SignatureScheme.Result.VALID, fixture, "verifies over the covered bytes with the holder's material");
        require(reading.verify(SignatureSchemeFixture.over(fixture.tampered()), fixture.material(), now).result()
                == SignatureScheme.Result.INVALID, fixture, "is INVALID over other bytes");
        require(reading.verify(SignatureSchemeFixture.over(fixture.covered()), fixture.foreign(), now).result()
                == SignatureScheme.Result.NO_KEY, fixture, "is NO_KEY against material holding another signer");
        require(reading.verify(SignatureSchemeFixture.over(fixture.covered()), null, now).result()
                == SignatureScheme.Result.NO_KEY, fixture, "is NO_KEY with no material at all");
    }

    private static void namesTheSigner(SignatureSchemeFixture fixture) throws Exception {
        SignatureScheme.Reading reading = reading(fixture);
        Instant now = Instant.now();
        String kind = fixture.scheme().material();
        SignerIdentity held = reading.verify(SignatureSchemeFixture.over(fixture.covered()), fixture.material(), now)
                .signer();
        require(fixture.signer().equals(held), fixture, "names the fixture's signer " + fixture.signer()
                + " when its material is held, not " + held);
        SignerIdentity tampered = reading.verify(SignatureSchemeFixture.over(fixture.tampered()),
                fixture.material(), now).signer();
        require(fixture.signer().equals(tampered), fixture, "names the same signer for an INVALID verification");
        SignerIdentity unheld = reading.verify(SignatureSchemeFixture.over(fixture.covered()), null, now).signer();
        require(kind.equals(unheld.scheme()), fixture, "names the signer in the material's identity scheme '" + kind
                + "' with no material held, not '" + unheld.scheme() + "'");
        require(kind.equals(held.scheme()), fixture, "names the signer in the material's identity scheme '" + kind
                + "', not '" + held.scheme() + "'");
    }

    private static void gradesWhatVerified(SignatureSchemeFixture fixture) throws Exception {
        SignatureScheme.Verification verified = reading(fixture)
                .verify(SignatureSchemeFixture.over(fixture.covered()), fixture.material(), Instant.now());
        require(verified.result() == SignatureScheme.Result.VALID, fixture, "verifies the fixture's signature");
        require(verified.quality() != null && verified.quality().grade() != SignatureQuality.Grade.UNASSESSED,
                fixture, "grades a VALID verification rather than leaving it unassessed");
        require(verified.keyAlgorithm() != null && !verified.keyAlgorithm().isBlank(), fixture,
                "names the key algorithm of a VALID verification");
        require(verified.keyBits() > 0, fixture, "states the key size of a VALID verification");
        require(verified.hashAlgorithm() != null && !verified.hashAlgorithm().isBlank(), fixture,
                "names the digest of a VALID verification");
    }

    private static void aBoundRidesOut(SignatureSchemeFixture fixture) throws Exception {
        SignatureScheme.Reading reading = reading(fixture);
        Bound bound = new Bound();
        ArtifactSignatures.Signed cut = () -> new InputStream() {

            private int read;

            @Override
            public int read() throws IOException {
                if (read >= 64) {
                    throw bound;
                }
                read++;
                return 'x';
            }
        };
        try {
            SignatureScheme.Verification answered = reading.verify(cut, fixture.material(), Instant.now());
            throw new AssertionError(name(fixture) + ": a covered stream that raised was answered " + answered.result()
                    + " - an IOException from the covered stream must ride out of verify, never become a verdict");
        } catch (Bound expected) {
            require(expected == bound, fixture, "raises the covered stream's own exception, unchanged");
        }
    }

    private static SignatureScheme.Reading reading(SignatureSchemeFixture fixture) throws IOException {
        return fixture.scheme().read(fixture.evidence())
                .orElseThrow(() -> new AssertionError(name(fixture) + ": did not read the fixture's evidence"));
    }

    private static void require(boolean condition, SignatureSchemeFixture fixture, String expectation) {
        if (!condition) {
            throw new AssertionError(name(fixture) + ": " + expectation);
        }
    }

    private static String name(SignatureSchemeFixture fixture) {
        return fixture.schemeClass().substring(fixture.schemeClass().lastIndexOf('.') + 1);
    }
}
