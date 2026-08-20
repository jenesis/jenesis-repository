package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * How one {@code ArtifactStoreProvider} backend registers itself with the shared {@link StoreContract} suite: a fixture
 * hands the kit a live, empty, tenant-scoped {@link ArtifactStore} and names the provider class it stands for, and the
 * kit then drives <em>every</em> contract property against it. A backend is covered by writing a fixture, never by
 * copying assertions - which is why the four hand-written backend suites diverged before this kit existed.
 *
 * <p>Three declarations carry the fixture's honesty, and each is machine-checked rather than trusted:
 * <ul>
 *   <li>{@link #providerClass()} keys the fixture to a statically declared {@code provides ... with ...} class, so the
 *       census can prove no declared or runtime-discovered backend is unfixtured (and no fixture names a dead one);</li>
 *   <li>{@link #unsupported()} names the properties this fixture's <em>environment</em> cannot express, each with a
 *       mandatory reason. It is a statement about the emulator, never about the backend: a property no fixture
 *       anywhere exercises is a hole the census fails on;</li>
 *   <li>{@link #unavailable()} reports that the fixture cannot start at all (no Docker daemon, no credentials).
 *       {@link #skipReason} turns that into a self-skip on a developer machine and into a <em>failure</em> on the
 *       strict lane, where the environment is declared complete and a skip would be a broken lane hiding as green.</li>
 * </ul>
 *
 * <p>A fixture owns whatever it starts. {@link #start()} runs once per suite, {@link #close()} once after it, and
 * {@link #store()} is valid only in between. The store handed out must be scoped to a namespace no other fixture or
 * run shares, because the contract checks assert absence as well as presence.
 */
public interface StoreFixture extends AutoCloseable {

    /** The system property the strict CI lane sets to declare the environment complete; under it an
     *  {@link #unavailable()} fixture fails rather than skips. */
    String REQUIRED_PROPERTY = "jenreg.test.required";

    /** The {@code ArtifactStoreProvider} name this fixture drives ({@code filesystem}, {@code s3}, {@code gcs},
     *  {@code azure-blob}) - the same string an operator writes as {@code jenreg.store}. */
    String backend();

    /** The fully qualified {@code ArtifactStoreProvider} implementation class this fixture covers, as the census
     *  parses it out of the backend module's {@code provides ... with ...} clause. */
    String providerClass();

    /** Start whatever the fixture owns (a container, a temp directory) and build the store. Called once, before any
     *  check runs; a failure here is always a test failure, never a skip - a fixture that could not finish starting is
     *  exactly the broken lane the strict property exists to surface. */
    void start() throws Exception;

    /** The live, empty, tenant-scoped store the contract checks run against. Valid only between {@link #start()} and
     *  {@link #close()}. */
    ArtifactStore store();

    /** Why this fixture cannot run here at all - "no Docker daemon", "no credentials" - or empty when it can. Checked
     *  before {@link #start()}. */
    default Optional<String> unavailable() {
        return Optional.empty();
    }

    /** Whether a strict lane must fail rather than skip when this fixture is {@link #unavailable()}. True for anything
     *  a complete CI environment provides (a container image); false only for an entitlement CI cannot install, such as
     *  a live cloud account's credentials. */
    default boolean required() {
        return true;
    }

    /** The contract properties this fixture's environment cannot express, each mapped to the reason and to where the
     *  property <em>is</em> proven instead. Empty by default: an exclusion is a deliberate, reviewable statement. */
    default Map<StoreContract.Property, String> unsupported() {
        return Map.of();
    }

    /**
     * The plaintext-transport declaration behind {@link StoreContract.Property#PLAINTEXT_ENDPOINT_REFUSED}: the config
     * this fixture resolves its backend with, and the key inside it that opts out of the https-only endpoint screen.
     * Empty - the default - declares that this backend has no endpoint at all, which is a statement the fixture must
     * then also make as a reason-bearing {@link #unsupported()} exclusion, so a missing declaration can never quietly
     * drop the property.
     *
     * <p>Every containerised fixture here reaches its emulator over plaintext {@code http}, so its declared config
     * <em>must</em> carry the opt-out set to {@code true}. That is not an inconvenience of the kit; it is the proof the
     * screen bites, and the check asserts both halves - the config is refused without the opt-out, and honoured with it.
     */
    default Optional<Plaintext> plaintext() {
        return Optional.empty();
    }

    /** A fixture's plaintext-endpoint declaration: the whole config map {@link #start()} resolves the backend with,
     *  and the opt-out key inside it. */
    record Plaintext(Map<String, String> config, String allowInsecureKey) {

        public Plaintext {
            config = Map.copyOf(Objects.requireNonNull(config, "config"));
            Objects.requireNonNull(allowInsecureKey, "allowInsecureKey");
        }
    }

    @Override
    default void close() throws Exception {
    }

    /**
     * The skip-versus-fail decision, in one place for every backend. Returns the reason a caller should turn into a
     * JUnit assumption, or empty when the fixture can run. Under {@code -Djenreg.test.required} - the strict CI lane's
     * process override - a {@link #required()} fixture that cannot start throws instead, because CI installs every tool
     * the selected suites need and a self-skip there would be a broken lane reported as green.
     */
    static Optional<String> skipReason(StoreFixture fixture) {
        Optional<String> unavailable = fixture.unavailable();
        if (unavailable.isEmpty()) {
            return Optional.empty();
        }
        String required = System.getProperty(REQUIRED_PROPERTY);
        if (fixture.required() && required != null && !required.equalsIgnoreCase("false")) {
            throw new AssertionError("The '" + fixture.backend() + "' store-contract fixture cannot start ("
                    + unavailable.get() + ") and -D" + REQUIRED_PROPERTY + " declares this environment complete, so "
                    + "skipping it would hide a broken lane. Start the backend, or mark the fixture not required.");
        }
        return unavailable;
    }
}
