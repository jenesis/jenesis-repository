package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.testkit.Falsification;
import build.jenesis.repository.compliance.testkit.SignatureSchemeContract;
import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;

/**
 * The JUnit driver for one scheme's leg of the shared {@code SignatureScheme} contract. Everything scheme-specific
 * lives in the {@link SignatureSchemeFixture} a subclass supplies; the checks come from the testkit, so a new
 * verifier is covered by a fixture and a four-line subclass rather than by a hand-written suite that decides for
 * itself what the contract said. Each property becomes one dynamic test named for the scheme and its expectation,
 * and each is run again against the mutant its property declares, requiring the check to say otherwise.
 *
 * <p>It lives here rather than beside the inspector contract because the material a scheme is proven over - a
 * generated OpenPGP key, an issued X.509 chain, a bundle the fake Sigstore signed - is minted by this module's
 * fixtures over Bouncy Castle, which the compliance contract module deliberately does not load.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SignatureSchemeContractSuite {

    private SignatureSchemeFixture fixture;

    /** The scheme under test. Called once; the fixture must build nothing in its constructor, because the census
     *  instantiates every fixture merely to read the provider class it claims. */
    abstract SignatureSchemeFixture fixture();

    @BeforeAll
    void start() throws Exception {
        SignatureSchemeFixture candidate = fixture();
        candidate.start();
        fixture = candidate;
    }

    @TestFactory
    Stream<DynamicTest> the_signature_scheme_contract() {
        String scheme = fixture.schemeClass().substring(fixture.schemeClass().lastIndexOf('.') + 1);
        return SignatureSchemeContract.checks().stream().map(check -> DynamicTest.dynamicTest(
                scheme + ": " + check.name(),
                () -> check.body().run(fixture)));
    }

    /** The falsification leg: each check, re-run against the mutation its property declares, requiring the check
     *  to fail. A check that survives is not measuring its property for this scheme. */
    @TestFactory
    Stream<DynamicTest> every_contract_check_is_falsifiable() {
        String scheme = fixture.schemeClass().substring(fixture.schemeClass().lastIndexOf('.') + 1);
        return SignatureSchemeContract.checks().stream()
                .flatMap(check -> SignatureSchemeContract.mutations(check.property()).stream()
                        .map(mutation -> DynamicTest.dynamicTest(
                                scheme + ": " + mutation.mutant() + " must break - " + check.name(),
                                () -> Falsification.requireBroken(fixture, check, mutation))));
    }
}
