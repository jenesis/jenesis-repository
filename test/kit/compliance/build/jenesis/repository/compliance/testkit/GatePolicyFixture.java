package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.Verdict;

/**
 * How one {@link GatePolicyProvider} registers with the shared {@link GatePolicyContract} suite: a fixture hands the
 * kit the settings that give its dimension something to gate on, the settings that give it nothing, the dials that do
 * not parse, and the subjects it must decide - then names the provider class it stands for and <em>declares the two
 * things the contract cannot infer</em>: whether the dimension is carried the moment it is switched on or only once an
 * operator configured it, and which verdict its two gate flavors must reach for each subject.
 *
 * <p>A dimension is covered by writing a fixture, never by copying assertions. Eight dimensions read eight different
 * settings vocabularies, but they owe the gate the same handful of promises - be absent when the deployment did not
 * configure you, throw naming the key when it configured you wrongly, decide the same way twice, report a permit the
 * way the family does, and behave the way your own {@link GatePolicyProvider#symmetry()} says you do.
 *
 * <p><strong>There is no declaration of what a permit looks like</strong>. There used to be: the dimensions
 * disagreed - six answered an {@code <dim>-action} of {@link Verdict#ALLOW} with no finding at all, five of them
 * before they looked at the subject - and a fixture declared which side it was on. They no longer disagree. A
 * dimension carrying an action dial evaluates the subject and records its permit as a {@code Finding(ALLOW, ...)},
 * the shape the gate already uses for a VEX-suppressed or waived advisory, and {@link GatePolicyContract} asserts that
 * of every one of them. A seam for saying otherwise would be a seam for reintroducing the divergence.
 *
 * <h2>The declarations the kit falsifies rather than trusts</h2>
 * <ul>
 *   <li>{@link #presence()} is checked in both directions: a {@link Presence#CONFIGURED} dimension must yield
 *       {@link Optional#empty()} over {@link #unconfigured()}, and a {@link Presence#ALWAYS} one must yield a policy
 *       over exactly the same settings. A fixture cannot declare its way out of either leg - it swaps which of the two
 *       assertions runs, and both are assertions.</li>
 *   <li>The provider's own {@link GatePolicyProvider#symmetry()} is checked against what the two legs actually decide:
 *       {@link GatePolicyProvider.Symmetry#SYMMETRIC} must reach identical findings on both, and
 *       {@link GatePolicyProvider.Symmetry#SOFTENED_ON_PROXY} must really be weaker on the proxy for at least one
 *       subject. That is the point of the declaration being data: a provider that behaves differently from
 *       what it declares fails here rather than being caught by eye.</li>
 *   <li>{@link Case#publish()} / {@link Case#proxy()} pin the verdict itself, so the symmetry legs cannot be satisfied
 *       by a dimension that decides nothing at all on either leg.</li>
 * </ul>
 *
 * <p>A fixture owns whatever it builds. {@link #start()} runs once per suite, {@link #close()} once after it, and the
 * settings and cases are valid only in between. Constructing a fixture must allocate nothing and read nothing: the
 * census instantiates every fixture purely to read {@link #providerClass()}.
 */
public interface GatePolicyFixture extends AutoCloseable {

    /**
     * When a dimension is carried by the gate - the one thing {@code create} decides that is genuinely per-dimension,
     * and therefore the one thing the kit cannot derive.
     */
    enum Presence {

        /**
         * The dimension is carried only once an operator configured it something to gate on: a floor list, a reserved
         * name, a rule set, a trust anchor, a resolvable catalogue or health source. Over {@link #unconfigured()} it
         * yields {@link Optional#empty()} - <em>absent</em>, never a policy object that finds nothing, because an
         * inert dimension is indistinguishable from an active one in every surface that counts the gate's dimensions
         * and "always allows" is exactly the shape a silent misconfiguration takes.
         */
        CONFIGURED,

        /**
         * The dimension is carried whenever it is switched on, because what it gates on arrives with the artifact
         * rather than from configuration: an artifact declaring no identifiable licence <em>is</em> the licence
         * dimension's case, and a detection stamped on the subject is the secret dimension's. Such a dimension has no
         * unconfigured shape, so its only off switches are the {@code jenreg.<name>} toggle and its own
         * {@link GatePolicyProvider#requiredConfig()} - which is what makes the kit assert presence here rather than
         * absence.
         */
        ALWAYS
    }

    /**
     * One dial an operator can set wrongly, and the value that does not parse. The value is a real one a settings
     * write or an environment variable could carry - a verdict that is not a verdict, a floor missing its comparator,
     * a rule whose expression does not compile - because the duty being tested is that the operator is told
     * <em>which</em> of a deployment's dials to fix, and the message can only carry the key if the code put it there.
     *
     * @param key   the setting key whose value does not parse; the failure message must name it verbatim
     * @param value the unparseable value
     * @param why   what an operator was trying to express, for the failure message when the leg does not hold
     */
    record Misconfiguration(String key, String value, String why) {

        public Misconfiguration {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(why, "why");
        }
    }

    /**
     * One subject this dimension must decide, the advisories the gate looked up for it, and the verdict each gate
     * flavor must reach. The expectation is part of the case rather than a separate method so a fixture cannot grow a
     * subject nobody states an outcome for - and stating the verdict per leg is what turns the provider's
     * {@link GatePolicyProvider#symmetry()} declaration into something falsifiable.
     *
     * @param name           what this subject is, for a failure message
     * @param subject        the artifact under assessment
     * @param advisories     the gate's single per-subject feed lookup, shared across dimensions
     * @param publish        the strongest verdict across the findings on the publish leg
     * @param proxy          the strongest verdict across the findings on the proxy leg; unused for a
     *                       {@link GatePolicyProvider.Symmetry#PROXY_ONLY} dimension's absent publish leg
     * @param actionGoverned the legs on which this subject's verdict is the one the dimension's {@code <dim>-action}
     *                       dial names, so the kit can drive the dial through every {@link Verdict} and hold the
     *                       dimension to it. Per leg rather than per case because a softening dimension overrides its
     *                       own dial on the leg it softens - the licence dial governs a publish and is deliberately
     *                       not consulted for an unknown licence on the proxy
     */
    record Case(String name,
                ComplianceGate.Subject subject,
                List<AdvisorySource.Advisory> advisories,
                Verdict publish,
                Verdict proxy,
                Set<GatePolicyProvider.Path> actionGoverned) {

        public Case {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(publish, "publish");
            Objects.requireNonNull(proxy, "proxy");
            advisories = List.copyOf(advisories);
            actionGoverned = Set.copyOf(actionGoverned);
        }

        /** A subject both legs must decide alike - the shape of every dimension that declares itself
         *  {@link GatePolicyProvider.Symmetry#SYMMETRIC}. */
        public static Case of(String name, ComplianceGate.Subject subject,
                              List<AdvisorySource.Advisory> advisories, Verdict verdict) {
            return new Case(name, subject, advisories, verdict, verdict, Set.of());
        }

        /** A subject the two legs decide differently - only legal for a dimension declaring
         *  {@link GatePolicyProvider.Symmetry#SOFTENED_ON_PROXY}, and required of it at least once. */
        public static Case softened(String name, ComplianceGate.Subject subject,
                                    List<AdvisorySource.Advisory> advisories, Verdict publish, Verdict proxy) {
            return new Case(name, subject, advisories, publish, proxy, Set.of());
        }

        /** This case re-declared as the one the dimension's action dial governs on <em>every</em> leg the dimension
         *  carries - the ordinary shape, since a dial normally decides both flavours alike. */
        public Case governedByItsActionDial() {
            return governedByItsActionDial(GatePolicyProvider.Path.values());
        }

        /** This case re-declared as the one the dimension's action dial governs on the named legs: the kit then drives
         *  that dial through every verdict and requires the dimension to report exactly the verdict named - including
         *  {@link Verdict#ALLOW}, which evaluates and permits rather than switching the dimension off. Naming
         *  legs is for a dimension that overrides its own dial on the leg it softens - the licence dial governs a
         *  publish and is deliberately not consulted for an unknown licence on the proxy. */
        public Case governedByItsActionDial(GatePolicyProvider.Path... paths) {
            return new Case(name, subject, advisories, publish, proxy, Set.of(paths));
        }

        /** The verdict this case must reach on one gate flavor. */
        public Verdict verdict(GatePolicyProvider.Path path) {
            return path == GatePolicyProvider.Path.PROXY ? proxy : publish;
        }
    }

    /** The fully qualified {@link GatePolicyProvider} implementation class this fixture covers, as the census parses
     *  it out of the dimension module's {@code provides ... with ...} clause. */
    String providerClass();

    /**
     * The provider under test: the instance {@code ServiceLoader} constructed, looked up by the class this fixture
     * claims. A fixture does not - and cannot - instantiate it: a dimension module {@code provides} its implementation
     * and exports it to no one, so the only handle onto it is the discovered one, which is also the only instance a
     * publish or proxy screen ever reaches. The lookup therefore doubles as a check that the fixture's test module
     * really roots the dimension's module.
     */
    default GatePolicyProvider provider() {
        return GatePolicyProvider.installed().stream()
                .filter(provider -> provider.getClass().getName().equals(providerClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(providerClass() + " is not discoverable in this module graph; "
                        + "the fixture's test module must require the module that provides it."));
    }

    /** Whether this dimension is carried the moment it is switched on, or only once configured. Proven, not trusted:
     *  see {@link Presence}. */
    Presence presence();

    /**
     * Whether the {@link GatePolicyProvider} this fixture claims is one the SPI's own discovery really finds. Every
     * registered fixture's is, and says so - the census proves it separately, against the static {@code provides}
     * scan as well as the runtime graph, because a missing {@code requires} has to be caught there rather than here.
     *
     * <p>It is a declaration rather than a runtime sniff for the reason every other declaration in this fixture is
     * one: a sniff would silently shorten the contract for a dimension whose module quietly left the graph, which is
     * exactly the failure the census exists to catch. Declared, the kit checks the claim first and only <em>then</em>
     * stands the {@link GatePolicyProvider#resolve} legs down - so the one caller that answers {@code false} is the
     * negative-control suite, whose probe dimensions are ordinary classes no module declares and which would
     * otherwise fail every resolve leg for a reason that has nothing to do with the property under test.
     */
    default boolean discovered() {
        return true;
    }

    /** Build whatever the fixture owns (a key pair, a signed envelope). Called once, before any check runs; a failure
     *  here is a test failure, never a skip. */
    default void start() throws Exception {
    }

    /**
     * The settings that give this dimension something to gate on - the deployment an operator configured. Read through
     * the same {@code jenreg.*}-prefixed lookup the server hands a provider, so a key here is spelled
     * exactly as the settings catalogue spells it.
     */
    Map<String, String> configured();

    /**
     * The settings under which this dimension has nothing to gate on. Empty by default, which is the honest shape for
     * almost every dimension: "the operator set no floor, no reserved name, no rule, no trust anchor". A dimension
     * whose absence needs a positive setting (a catalogue switched off by name) overrides it.
     */
    default Map<String, String> unconfigured() {
        return Map.of();
    }

    /** The dials an operator can set wrongly, each with a value that does not parse. Never empty: every dimension
     *  reads at least one value it can refuse, and a fixture declaring none would silently skip the whole
     *  fail-fast leg. */
    List<Misconfiguration> misconfigured();

    /**
     * The dial whose verdict decides what this dimension <em>reports</em> - and, per the earlier work, never whether the gate
     * carries it. Empty for a dimension that has none: the policy-as-code dimension carries its verdict on each rule
     * rather than on one dial, which is a real difference and is recorded as such in the census rather than papered
     * over with a synthetic key.
     */
    default Optional<String> actionKey() {
        return Optional.empty();
    }

    /**
     * The subjects this dimension must decide, with the verdict each leg must reach. At least one must gate (so the
     * purity and symmetry legs are not comparing "nothing" against "nothing") and at least one must not (so a
     * dimension that gated everything would fail rather than pass every comparison trivially); the kit asserts both.
     */
    List<Case> cases();

    @Override
    default void close() throws Exception {
    }
}
