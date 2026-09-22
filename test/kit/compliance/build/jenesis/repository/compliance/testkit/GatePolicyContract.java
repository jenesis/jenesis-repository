package build.jenesis.repository.compliance.testkit;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.GatePolicyProvider.Path;
import build.jenesis.repository.compliance.GatePolicyProvider.Symmetry;
import build.jenesis.repository.compliance.Verdict;

/**
 * The executable {@link GatePolicyProvider} contract: one parameterized body of checks that every gate dimension runs
 * through a {@link GatePolicyFixture}, so a dimension property is stated once and proven per implementation instead of
 * being re-asserted - and quietly re-interpreted - in a hand-written suite per dimension. Each {@link Property} names
 * one documented contract clause; {@link #checks()} is the whole contract, and every dimension runs all of it.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the provider, the property and the
 * expectation, so this module stays the compliance and store SPIs plus the JDK, and any test module can
 * require it. The JUnit driver lives under {@code test/**} and turns each check into one dynamic test. It is the
 * sibling of {@link InspectorContract}: an inspector reads the artifact, a dimension decides about it, and the two
 * halves of the screen are contracted the same way.
 *
 * <h2>What the symmetry check actually proves</h2>
 * made the publish/proxy asymmetry <em>data</em> - {@link Symmetry}, honoured by both {@code GateDimension.of}
 * and {@link GatePolicyProvider#resolve} - so that a dimension excluded from a flavor cannot be built for it. That
 * closes one direction. {@link Property#SYMMETRY_MATCHES_BEHAVIOUR} closes the other, which no amount of plumbing can:
 * a dimension declaring {@link Symmetry#SYMMETRIC} and then softening itself on the proxy, or one declaring
 * {@link Symmetry#SOFTENED_ON_PROXY} and softening nothing, still compiles and still resolves. Both legs are therefore
 * driven over the same subjects and compared - identical findings for a symmetric dimension, strictly weaker somewhere
 * for a softening one - so the declaration is a claim about behaviour that the kit can falsify.
 *
 * <h2>What the purity checks actually prove</h2>
 * The two halves of clause 2 are asserted separately because they fail differently.
 * <ul>
 *   <li>{@link Property#CREATE_IS_PURE} models what the console really does: it builds a whole throwaway gate purely
 *       to <em>validate</em> a candidate settings write and then discards it, including one that throws. The check
 *       builds a dimension, builds and discards a candidate (a valid one and a rejected one), rebuilds, and requires
 *       the rebuilt dimension <em>and the one built before the throwaway</em> to decide identically - so a provider
 *       that memoised, mutated static state or let a rejected candidate poison the live gate fails here. It also reads
 *       the config through a recording lookup and requires the same dials, in the same order, on every build: a
 *       create whose reads drift is stateful whatever its answers look like.</li>
 *   <li>{@link Property#ASSESS_IS_PURE} proves "identical subject and advisories yield identical findings, in any
 *       order, on either leg". The order half is the subtle one and is the reason a re-screen reproduces the verdict
 *       an artifact was published under: the same subjects are assessed on one policy instance in declared order, in
 *       reversed order and rotated, on a second instance built from the same settings, with the advisory list
 *       reversed, and with the two legs interleaved - and every one of those must answer what the first pass did.</li>
 * </ul>
 *
 * <h2>Where a substitution goes, and why not on the fixture</h2>
 * This kit deliberately drives the product's own discovery: a check builds its dimension through
 * {@code provider.create(config, path)} <em>and</em> through {@link GatePolicyProvider#resolve}, the static
 * {@code ServiceLoader} path a publish or proxy screen really takes. That buys fidelity and costs falsifiability,
 * because <b>no substitution for a fixture's provider reaches either leg</b>: {@code resolve} discovers its own
 * instances, and even the {@code create} leg runs against the <em>discovered</em> provider, looked up by the class the
 * fixture names, falling back to {@link GatePolicyFixture#provider()} only when discovery misses - which a registered
 * fixture's never does.
 *
 * <p>So the substitution is on the resolved {@link GatePolicy} instead: {@link GatePolicyMutant} is applied to every
 * policy this kit obtains, whichever entry point produced it. A probe therefore cannot be green here for the sole
 * reason that it was never driven, which is the contamination reproduced (an inert dimension substituted at the
 * fixture's provider passed <b>64 of 64</b> checks with all <b>8</b> fixtures wholly satisfied - the signature of a
 * mutant nothing ran, not a vacuity measurement).
 *
 * <h2>There is no exclusion seam</h2>
 * Unlike a store backend, a gate dimension has no environment that could fail to express a property, so a per-fixture
 * exclusion could only ever mean "this one does not comply". The one thing a fixture may say about itself that changes
 * which assertions run is {@link GatePolicyFixture#discovered()} - and that is a <em>declaration the kit checks first</em>,
 * against the SPI's own discovery, before it stands the four {@link GatePolicyProvider#resolve} legs down. A fixture
 * claiming to be discoverable and not being one fails; only the negative-control probes, which no module declares, say
 * otherwise.
 *
 * <h2>Clauses this kit discharges</h2>
 *
 * {@code CREATE_IS_PURE} is the idempotency clause (a purely validating {@code create}), {@code DISABLED_YIELDS_EMPTY}
 * the absent-versus-inert sentinel, {@code MISCONFIG_NAMES_ITS_KEY} the selection-failure clause,
 * {@code ASSESS_IS_PURE} the read-purity clause, and the two symmetry checks the declared-asymmetry clause. The
 * remaining clauses - thread-safety, streaming, tenant scoping, error visibility, staleness, lifecycle, ordering,
 * bounded work, durability - are deliberately not claimed: no check here falsifies them.
 *
 * @jenesis.covers build.jenesis.repository.compliance.GatePolicyProvider 2, 3, 4, 9, 13
 */
public final class GatePolicyContract {

    /**
     * One documented contract clause of {@link GatePolicyProvider}. The enum is the kit's vocabulary: the census fails
     * on a property no fixture exercises, so the list can never grow a clause that is asserted nowhere.
     */
    public enum Property {

        /** The provider's declared {@link Symmetry} decides which flavors it is built for, through
         *  {@code create} <em>and</em> through the {@link GatePolicyProvider#resolve} path a screen really takes -
         *  a dimension excluded from a flavor yields nothing there, one that carries it yields a policy, and the two
         *  entry points hand back the same dimension rather than merely agreeing that there is one. */
        SYMMETRY_IS_HONOURED,

        /** The declared {@link Symmetry} matches what the two legs actually decide: {@link Symmetry#SYMMETRIC} reaches
         *  identical findings on both, {@link Symmetry#SOFTENED_ON_PROXY} is never stronger on the proxy and is
         *  strictly weaker for at least one subject, and {@link Symmetry#PROXY_ONLY} really decides something on the
         *  leg it does carry. A provider cannot behave differently from what it declares. */
        SYMMETRY_MATCHES_BEHAVIOUR,

        /** Every route by which a deployment does not carry this dimension yields {@link Optional#empty()} - the
         *  {@code jenreg.<name>} toggle, an unset {@link GatePolicyProvider#requiredConfig()} key, and
         *  (for a {@link GatePolicyFixture.Presence#CONFIGURED} dimension) nothing configured to gate on. Absent,
         *  never a policy object that finds nothing, and never {@code null}. A
         *  {@link GatePolicyFixture.Presence#ALWAYS} dimension is held to the opposite over the same settings. */
        DISABLED_YIELDS_EMPTY,

        /** A dial that does not parse throws out of {@code create} with the offending key in the message, on every
         *  flavor the dimension carries - so a live settings rebuild rolls back and an operator with seven dials is
         *  told which one to fix. The same unparseable dial on a dimension switched off does not throw, because the
         *  toggle is the way out of a dimension that cannot presently be configured correctly. */
        MISCONFIG_NAMES_ITS_KEY,

        /** {@code create} is a pure function of {@code config} and {@code path}: the same lookup yields an equivalent
         *  dimension however often it is called, reading the same dials each time, and building - or refusing - a
         *  throwaway candidate leaves both the next build and the dimension built before it unchanged. */
        CREATE_IS_PURE,

        /** {@link GatePolicy#assess} is pure: identical subject and advisories yield identical findings however often
         *  they are assessed, in whatever order the subjects arrive, across two policies built from the same settings,
         *  with the advisory list in either order, and on either leg. */
        ASSESS_IS_PURE,

        /** The findings themselves: never {@code null}, never a finding without a verdict or with a blank detail, and
         *  exactly the verdict the fixture declares for that subject on that leg - with at least one subject gated and
         *  at least one left alone, so none of the comparisons above is between two empty lists. */
        FINDINGS_ARE_WELL_FORMED,

        /** an {@code <dim>-action} of {@link Verdict#ALLOW} makes the dimension <em>evaluate and permit</em>,
         *  it does not withdraw it from the gate. The dial decides what the dimension reports - every verdict it names
         *  is the verdict reported - and never whether the gate carries it; an unconfigured dimension stays absent
         *  whatever the dial says. an earlier change settled what permitting looks like: the dimension assesses the subject and
         *  records a {@code Finding(ALLOW, <why>)}, so a permitted artifact is distinguishable from one the dimension
         *  had nothing against. */
        ACTION_DECIDES_THE_REPORT_NOT_THE_PRESENCE
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /**
     * The body of a {@link Check}, run against a started fixture and one {@link GatePolicyMutant}.
     *
     * <p>The mutant is a parameter rather than a decoration of the fixture because of what this kit drives: the
     * dimension is reached through {@code provider.create} <em>and</em> through {@link GatePolicyProvider#resolve}, the
     * SPI's own static {@code ServiceLoader} path, which no substitution for a fixture can reach. Both hand back a
     * {@link GatePolicy}, so that is where the substitution goes - see {@link GatePolicyMutant} for the measurement
     * that settled it.
     */
    @FunctionalInterface
    public interface Body {
        void run(GatePolicyFixture fixture, GatePolicyMutant mutant) throws Exception;
    }

    private GatePolicyContract() {
        throw new UnsupportedOperationException("GatePolicyContract is a static utility");
    }

    /**
     * Every contract check, in declaration order. The list <em>is</em> the contract: a dimension runs all of it, which
     * is why there is no fixture-taking overload to select a subset - see the class documentation on the absence of an
     * exclusion seam.
     */
    public static List<Check> checks() {
        return List.of(
                new Check(Property.SYMMETRY_IS_HONOURED,
                        "the declared symmetry decides which gate flavors the dimension is built for, through create "
                                + "and through resolve",
                        GatePolicyContract::symmetryIsHonoured),
                new Check(Property.SYMMETRY_MATCHES_BEHAVIOUR,
                        "the declared symmetry matches what the two legs decide - identical, softened, or proxy-only",
                        GatePolicyContract::symmetryMatchesBehaviour),
                new Check(Property.DISABLED_YIELDS_EMPTY,
                        "every route by which the deployment does not carry this dimension yields an absent dimension",
                        GatePolicyContract::disabledYieldsEmpty),
                new Check(Property.MISCONFIG_NAMES_ITS_KEY,
                        "a dial that does not parse throws naming its key, on every flavor the dimension carries",
                        GatePolicyContract::misconfigNamesItsKey),
                new Check(Property.CREATE_IS_PURE,
                        "create reads the same dials and yields an equivalent dimension, and a discarded candidate "
                                + "changes neither the next build nor the previous one",
                        GatePolicyContract::createIsPure),
                new Check(Property.ASSESS_IS_PURE,
                        "the same subject and advisories yield the same findings twice, in any order, on either leg",
                        GatePolicyContract::assessIsPure),
                new Check(Property.FINDINGS_ARE_WELL_FORMED,
                        "the findings carry a verdict and a reason, and reach exactly the verdict the fixture declares",
                        GatePolicyContract::findingsAreWellFormed),
                new Check(Property.ACTION_DECIDES_THE_REPORT_NOT_THE_PRESENCE,
                        "the action dial decides what the dimension reports, never whether the gate carries it",
                        GatePolicyContract::actionDecidesTheReport));
    }

    // --- the contract ------------------------------------------------------------------------------------------

    private static void symmetryIsHonoured(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        for (Path path : Path.values()) {
            boolean carries = underTest.symmetry().carries(path);
            Optional<GatePolicy> created = underTest.create(underTest.configured(), path);
            if (carries && created.isEmpty()) {
                throw underTest.failure("declares " + underTest.symmetry() + ", which carries the " + path + " leg, "
                        + "but built no policy for it over the settings this fixture declares as configured ("
                        + underTest.keys() + "). Either the settings are not what this dimension calls configured, or "
                        + "the declaration names a leg the code does not build.");
            }
            if (!carries && created.isPresent()) {
                throw underTest.failure("declares " + underTest.symmetry() + ", which does not carry the " + path
                        + " leg, yet built a policy for it. The declaration is what produces the empty leg "
                        + "(GateDimension.of and resolve both honour it), so a policy here means the provider "
                        + "bypassed its own declaration - and the gate would run a dimension nothing says it runs.");
            }
            if (!underTest.discovered()) {
                continue;
            }
            Optional<GatePolicy> resolved = underTest.sole(underTest.configured(), path);
            if (resolved.isPresent() != carries) {
                throw underTest.failure("GatePolicyProvider.resolve(" + path + ") "
                        + (carries ? "did not carry" : "carried") + " this dimension, but its declared "
                        + underTest.symmetry() + " says it " + (carries ? "does" : "does not") + ". create and "
                        + "resolve honour the same declaration; a disagreement means a screen and a console would "
                        + "describe different gates.");
            }
            if (carries) {
                // The two entry points must hand back the same dimension, not merely agree that there is one: the
                // console validates a settings write through create() and the screen runs what resolve() built, so a
                // divergence between them is an operator validating one gate and deploying another.
                same(underTest, "the dimension resolve() hands a " + path + " screen, against the one create() hands "
                                + "the console's settings validation",
                        underTest.decisions(resolved.orElseThrow(), path),
                        underTest.decisions(created.orElseThrow(), path),
                        "create and resolve differ only in discovery and enablement; the dimension they build from "
                                + "one configuration is the same dimension.");
            }
        }
    }

    private static void symmetryMatchesBehaviour(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        if (underTest.symmetry() == Symmetry.PROXY_ONLY) {
            // Nothing to compare - but the absent publish leg must not be the whole story, or "proxy-only" would be
            // indistinguishable from "decides nothing anywhere".
            GatePolicy proxyOnly = underTest.policy(Path.PROXY);
            if (underTest.cases().stream().noneMatch(scenario ->
                    !underTest.findings(proxyOnly, scenario, Path.PROXY).isEmpty())) {
                throw underTest.failure("declares PROXY_ONLY but gates no subject on the proxy leg either, so the "
                        + "declaration is not the reason the publish leg is empty - this dimension decides nothing "
                        + "at all. Supply a subject the proxy leg holds.");
            }
            return;
        }
        GatePolicy publish = underTest.policy(Path.PUBLISH);
        GatePolicy proxy = underTest.policy(Path.PROXY);
        int softened = 0;
        for (GatePolicyFixture.Case scenario : underTest.cases()) {
            List<ComplianceGate.Finding> onPublish = underTest.findings(publish, scenario, Path.PUBLISH);
            List<ComplianceGate.Finding> onProxy = underTest.findings(proxy, scenario, Path.PROXY);
            if (underTest.symmetry() == Symmetry.SYMMETRIC) {
                if (!onPublish.equals(onProxy)) {
                    throw underTest.failure("declares SYMMETRIC, but '" + scenario.name() + "' is decided differently "
                            + "on the two legs:\n    publish " + onPublish + "\n    proxy   " + onProxy
                            + "\nA symmetric dimension gates what is equally risky however the artifact arrived, and "
                            + "the declaration is what a console, an operator and a re-screen read. Declare "
                            + "SOFTENED_ON_PROXY and say why, or decide alike.");
                }
                continue;
            }
            Verdict strongestPublish = ComplianceGate.strongest(onPublish);
            Verdict strongestProxy = ComplianceGate.strongest(onProxy);
            if (strongestProxy.compareTo(strongestPublish) > 0) {
                throw underTest.failure("declares SOFTENED_ON_PROXY, but '" + scenario.name() + "' is decided more "
                        + "STRONGLY on the proxy (" + strongestProxy + ") than on the publish leg ("
                        + strongestPublish + "). A softening is one-directional: a proxied artifact may be let "
                        + "through where an upload is held, never the reverse.");
            }
            if (strongestProxy.compareTo(strongestPublish) < 0) {
                softened++;
            }
        }
        if (underTest.symmetry() == Symmetry.SOFTENED_ON_PROXY && softened == 0) {
            throw underTest.failure("declares SOFTENED_ON_PROXY, but decides every one of this fixture's "
                    + underTest.cases().size() + " subjects identically on both legs. A softening nothing softens is "
                    + "a declaration that says something the code does not do - which is exactly what making the "
                    + "asymmetry data was meant to stop. Declare SYMMETRIC, or fixture the subject the proxy leg "
                    + "lets through.");
        }
    }

    private static void disabledYieldsEmpty(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        String name = underTest.name();
        Map<String, String> unconfigured = fixture.unconfigured();
        // The self-disable's settings, built once: the configured deployment minus exactly the keys the dimension
        // declares it cannot run without. The same map drives the create leg and the resolve leg, so "unset" cannot
        // mean an absent key on one and a blank value on the other.
        Map<String, String> withoutRequired = new LinkedHashMap<>(underTest.configured());
        withoutRequired.keySet().removeAll(underTest.provider().requiredConfig());
        for (Path path : underTest.carried()) {
            // 1. The operator's off switch. It is read before anything is created, so this leg also proves the
            //    dimension is never even asked - see MISCONFIG_NAMES_ITS_KEY's escape-hatch leg.
            if (underTest.discovered()
                    && underTest.sole(with(underTest.configured(), name, "false"), path).isPresent()) {
                throw underTest.failure(Features.key(name) + "=false still resolved this dimension onto the "
                        + path + " gate. The toggle is the operator's off switch; a dimension that survives it cannot "
                        + "be switched off at all.");
            }
            // 2. What "configured" means for this dimension, asserted in whichever direction the fixture declares.
            boolean built = underTest.create(unconfigured, path).isPresent();
            if (fixture.presence() == GatePolicyFixture.Presence.CONFIGURED && built) {
                throw underTest.failure("declares Presence.CONFIGURED, but built a policy over " + unconfigured
                        + " - a deployment that configured this dimension nothing to gate on. An inert dimension is "
                        + "indistinguishable from an active one in every surface that counts the gate's dimensions, "
                        + "and 'always allows' is exactly the shape a silent misconfiguration takes: the sentinel is "
                        + "Optional.empty(), not a policy object that finds nothing.");
            }
            if (fixture.presence() == GatePolicyFixture.Presence.ALWAYS && !built) {
                throw underTest.failure("declares Presence.ALWAYS - what it gates on arrives with the artifact rather "
                        + "than from configuration - but yielded nothing over " + unconfigured + ". Declare "
                        + "Presence.CONFIGURED and name the settings that configure it.");
            }
            // 3. The self-disable: a dimension missing the configuration it cannot run without is absent, both
            //    through resolve (one log line, no throw) and through a create() called outside it.
            if (underTest.provider().requiredConfig().isEmpty()) {
                continue;
            }
            if (withoutRequired.size() == underTest.configured().size()) {
                throw underTest.failure("declares requiredConfig "
                        + new TreeSet<>(underTest.provider().requiredConfig()) + " but this fixture's configured "
                        + "settings (" + underTest.keys() + ") do not set all of them, so the self-disable leg below "
                        + "would pass without ever removing anything.");
            }
            if (underTest.discovered() && underTest.sole(withoutRequired, path).isPresent()) {
                throw underTest.failure("resolved onto the " + path + " gate with its required configuration "
                        + new TreeSet<>(underTest.provider().requiredConfig()) + " unset. A dimension that cannot run "
                        + "without a key self-disables with one log line rather than running on a default.");
            }
            if (underTest.create(withoutRequired, path).isPresent()) {
                throw underTest.failure("built a policy with its required configuration "
                        + new TreeSet<>(underTest.provider().requiredConfig()) + " unset. resolve() filters on that, "
                        + "but create() is also called outside resolve (the console's validating rebuild), so the "
                        + "guard has to hold here too.");
            }
        }
    }

    private static void misconfigNamesItsKey(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        if (fixture.misconfigured().isEmpty()) {
            throw underTest.failure("declares no misconfigurable dial. Every dimension reads at least one value it "
                    + "can refuse - a verdict, a decimal, a rule, a key - and a fixture that declares none silently "
                    + "skips the whole fail-fast leg, which is the leg an operator's rolled-back settings write "
                    + "depends on.");
        }
        for (Path path : underTest.carried()) {
            // The baseline first: if the configured settings themselves threw, every refusal below would prove nothing.
            underTest.create(underTest.configured(), path);
        }
        for (GatePolicyFixture.Misconfiguration broken : fixture.misconfigured()) {
            Map<String, String> misconfigured = with(underTest.configured(), broken.key(), broken.value());
            for (Path path : underTest.carried()) {
                RuntimeException refused = null;
                Optional<GatePolicy> accepted = Optional.empty();
                try {
                    accepted = underTest.create(misconfigured, path);
                } catch (RuntimeException thrown) {
                    refused = thrown;
                }
                if (refused == null) {
                    throw underTest.failure("accepted '" + broken.key() + "=" + broken.value() + "' on the " + path
                            + " leg and answered " + accepted + ". " + broken.why() + " A value that does not parse "
                            + "must throw out of create, so the settings writer rolls back to the last good gate and "
                            + "the scheduled re-read refuses to run a gate the operator did not ask for - reaching "
                            + "for a default instead loosens the gate without saying so.");
                }
                String message = refused.getMessage();
                if (message == null || message.isBlank()) {
                    throw underTest.failure("refused '" + broken.key() + "=" + broken.value() + "' on the " + path
                            + " leg with a blank message. An operator reading a rolled-back settings write, or a boot "
                            + "log, sees only this text.");
                }
                if (!message.contains(broken.key())) {
                    throw underTest.failure("refused '" + broken.key() + "=" + broken.value() + "' on the " + path
                            + " leg with \"" + message + "\", which never names the key. On the boot and "
                            + "scheduled-re-read path there is no settings-write context to add it back, so an "
                            + "operator with several dials learns only that SOME value somewhere is unreadable "
                            + "(PRINCIPLES §9).");
                }
            }
            // The way out: a dimension switched off is not asked to create anything, so an unparseable dial on it must
            // not wedge the rebuild. Without this, the off switch would be unusable exactly when it is needed.
            if (!underTest.discovered()) {
                continue;
            }
            Map<String, String> switchedOff = with(misconfigured, underTest.name(), "false");
            List<GatePolicy> resolved;
            try {
                resolved = underTest.resolve(switchedOff, underTest.carried().getFirst());
            } catch (RuntimeException wedged) {
                throw underTest.failure("refused '" + broken.key() + "=" + broken.value() + "' out of resolve() even "
                        + "though the dimension was switched off with jenreg." + underTest.name()
                        + "=false: " + wedged.getMessage() + ". Enablement is read before anything is created, so an "
                        + "operator can always disable a dimension they cannot presently configure correctly.");
            }
            if (!resolved.isEmpty()) {
                throw underTest.failure("was switched off and still resolved " + resolved.size() + " policy(ies).");
            }
        }
    }

    private static void createIsPure(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        for (Path path : underTest.carried()) {
            Recording first = new Recording(underTest.configured());
            GatePolicy before = underTest.create(first, path).orElseThrow(() -> underTest.failure(
                    "built no policy over its own configured settings on the " + path + " leg"));
            Map<String, List<ComplianceGate.Finding>> baseline = underTest.decisions(before, path);

            rebuilt(underTest, first, baseline, path, "the second create over the same settings");

            // The console's validating rebuild, both outcomes: a candidate that builds and a candidate that is
            // refused. Each is constructed purely to be thrown away.
            underTest.create(fixture.unconfigured(), path);
            GatePolicyFixture.Misconfiguration rejected = fixture.misconfigured().getFirst();
            try {
                underTest.create(with(underTest.configured(), rejected.key(), rejected.value()), path);
            } catch (RuntimeException _) {
                // Expected: MISCONFIG_NAMES_ITS_KEY owns that leg. What matters here is what it left behind.
            }

            rebuilt(underTest, first, baseline, path,
                    "a create after a candidate dimension was built and discarded (including a refused one)");
            same(underTest, "the dimension built BEFORE the throwaway candidate, re-assessed afterwards, on the "
                            + path + " leg - a live gate must not be disturbed by a settings validation",
                    underTest.decisions(before, path), baseline, PURE);
        }
    }

    /** One rebuild over the same settings: the dials it reads and the decisions it reaches must both match the first
     *  build's, whatever happened in between. */
    private static void rebuilt(Dimension underTest, Recording first,
                                Map<String, List<ComplianceGate.Finding>> baseline, Path path, String what) {
        Recording again = new Recording(underTest.configured());
        GatePolicy rebuilt = underTest.create(again, path)
                .orElseThrow(() -> underTest.failure("built no policy on " + what));
        if (!first.read().equals(again.read())) {
            throw underTest.failure(what + " read different dials on the " + path + " leg:\n    first "
                    + first.read() + "\n    then  " + again.read() + "\ncreate is a pure function of config and path; "
                    + "reads that drift mean state survived the call, and the console's validating rebuild would not "
                    + "be validating what boot will build.");
        }
        same(underTest, what + ", on the " + path + " leg", underTest.decisions(rebuilt, path), baseline, PURE);
    }

    private static void assessIsPure(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        List<GatePolicyFixture.Case> scenarios = underTest.cases();
        Map<Path, GatePolicy> policies = new LinkedHashMap<>();
        Map<Path, Map<String, List<ComplianceGate.Finding>>> baselines = new LinkedHashMap<>();
        for (Path path : underTest.carried()) {
            GatePolicy policy = underTest.policy(path);
            policies.put(path, policy);
            baselines.put(path, underTest.decisions(policy, path));
        }
        for (Path path : underTest.carried()) {
            GatePolicy policy = policies.get(path);
            Map<String, List<ComplianceGate.Finding>> baseline = baselines.get(path);

            same(underTest, "the same subjects assessed a second time on the same policy, on the " + path + " leg",
                    underTest.decisions(policy, path), baseline, PURE);
            same(underTest, "the same subjects assessed in REVERSED order on the same policy, on the " + path
                            + " leg - a re-screen walks the store in its own order, and the verdict an artifact was "
                            + "published under must survive that",
                    underTest.decisions(policy, reversed(scenarios), path), baseline, PURE);
            same(underTest, "the same subjects assessed in ROTATED order on a second policy built from the same "
                            + "settings, on the " + path + " leg",
                    underTest.decisions(underTest.policy(path), rotated(scenarios), path), baseline, PURE);

            for (GatePolicyFixture.Case scenario : scenarios) {
                // Compared as a multiset, unlike every neighbouring leg: a dimension that raises one finding per
                // advisory (the known-exploited cross-reference) legitimately emits them in the order the feed handed
                // them over, and the gate folds findings order-independently. What must not change is WHICH findings
                // there are - a dimension that read the list positionally would answer a different set.
                List<ComplianceGate.Finding> withReversedFeed = underTest.findings(policy, scenario.name(),
                        scenario.subject(), reversed(scenario.advisories()), path);
                if (!sorted(withReversedFeed).equals(sorted(baseline.get(scenario.name())))) {
                    throw underTest.failure("decided '" + scenario.name() + "' differently when the gate's shared "
                            + "advisory list arrived in the opposite order, on the " + path + " leg:\n    "
                            + baseline.get(scenario.name()) + "\n    " + withReversedFeed + "\nThe gate looks the "
                            + "feed up once for all dimensions and folds the findings order-independently, so a "
                            + "dimension that reads the list positionally decides by an accident of the feed.");
                }
            }
        }
        if (underTest.carried().size() > 1) {
            // The two legs are built from one provider; a dimension that shared state between them would decide a
            // publish differently after a proxy fetch had passed through, which is a race no test of one leg can see.
            for (GatePolicyFixture.Case scenario : scenarios) {
                for (Path leg : underTest.carried()) {
                    underTest.findings(policies.get(leg), scenario, leg);
                }
            }
            for (Path path : underTest.carried()) {
                same(underTest, "every subject on the " + path + " leg after the other leg assessed it too",
                        underTest.decisions(policies.get(path), path), baselines.get(path), PURE);
            }
        }
    }

    private static void findingsAreWellFormed(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        if (underTest.cases().isEmpty()) {
            throw underTest.failure("declares no subject at all, so every comparison in this contract would be "
                    + "between two empty maps.");
        }
        for (Path path : underTest.carried()) {
            GatePolicy policy = underTest.policy(path);
            int quiet = 0;
            for (GatePolicyFixture.Case scenario : underTest.cases()) {
                List<ComplianceGate.Finding> found = underTest.findings(policy, scenario, path);
                for (ComplianceGate.Finding finding : found) {
                    if (finding == null || finding.verdict() == null) {
                        throw underTest.failure("raised a finding without a verdict for '" + scenario.name()
                                + "' on the " + path + " leg.");
                    }
                    if (finding.detail() == null || finding.detail().isBlank()) {
                        throw underTest.failure("raised a " + finding.verdict() + " finding for '" + scenario.name()
                                + "' on the " + path + " leg with no detail. The detail is the whole explanation an "
                                + "operator gets for a held or refused artifact.");
                    }
                }
                if (found.isEmpty()) {
                    quiet++;
                }
                Verdict reached = ComplianceGate.strongest(found);
                if (reached != scenario.verdict(path)) {
                    throw underTest.failure("reached " + reached + " for '" + scenario.name() + "' on the " + path
                            + " leg, but the fixture declares " + scenario.verdict(path) + ". Findings:\n    " + found);
                }
            }
            if (quiet == underTest.cases().size()) {
                throw underTest.failure("gates none of its subjects on the " + path + " leg, so every comparison this "
                        + "contract makes there is between two empty lists and would pass for a dimension that "
                        + "decided nothing. Fixture a subject this dimension holds.");
            }
            if (quiet == 0) {
                throw underTest.failure("raises a finding for every subject it was given on the " + path + " leg. A "
                        + "dimension that never stays quiet would satisfy the purity legs while flagging the whole "
                        + "repository; fixture a subject it has nothing against.");
            }
        }
    }

    private static void actionDecidesTheReport(GatePolicyFixture fixture, GatePolicyMutant mutant) {
        Dimension underTest = Dimension.of(fixture, mutant);
        Optional<String> dial = fixture.actionKey();
        if (dial.isEmpty()) {
            // A dimension carrying no single verdict dial - the policy-as-code one, whose verdict rides each rule - has
            // no ALLOW to report under. The census keeps that reviewed rather than self-declared: a dimension that
            // grows a dial and does not name it here fails there.
            return;
        }
        String key = dial.get();
        for (Verdict verdict : Verdict.values()) {
            for (String spelling : List.of(verdict.name(), verdict.name().toLowerCase(Locale.ROOT))) {
                for (Path path : underTest.carried()) {
                    GatePolicy policy = underTest.create(with(underTest.configured(), key, spelling), path)
                            .orElseThrow(() -> underTest.failure("withdrew itself from the " + path + " gate when '"
                                    + key + "' named " + spelling + ". The verdict decides what a dimension REPORTS, "
                                    + "never whether the gate carries it: a withdrawn dimension evaluates nothing and "
                                    + "shows up in no report or audit trail, which is indistinguishable from one this "
                                    + "deployment never installed. Two ways to switch a dimension off already exist "
                                    + "(the jenreg." + underTest.name() + " toggle and its own required "
                                    + "configuration); a third spelled as a verdict is the weakest of the three."));
                    for (GatePolicyFixture.Case scenario : underTest.cases()) {
                        if (scenario.actionGoverned().contains(path)) {
                            reports(underTest, policy, scenario, path, key, spelling, verdict);
                        }
                    }
                }
            }
        }
        if (fixture.presence() == GatePolicyFixture.Presence.CONFIGURED) {
            // The other direction, so the rule above cannot be satisfied by making the dimension unconditionally
            // present: ALLOW means "evaluate and permit", it never means "configure me".
            for (Verdict verdict : Verdict.values()) {
                for (Path path : underTest.carried()) {
                    if (underTest.create(with(fixture.unconfigured(), key, verdict.name()), path).isPresent()) {
                        throw underTest.failure("was carried onto the " + path + " gate over settings that configure "
                                + "it nothing to gate on, merely because '" + key + "' named " + verdict + ". A "
                                + "verdict dial is not a configuration: the dimension is still absent.");
                    }
                }
            }
        }
    }

    /** What the dimension must report for the subject its action dial governs, under one spelling of one verdict. */
    private static void reports(Dimension underTest, GatePolicy policy, GatePolicyFixture.Case scenario, Path path,
                                String key, String spelling, Verdict verdict) {
        List<ComplianceGate.Finding> found = underTest.findings(policy, scenario, path);
        Verdict reached = ComplianceGate.strongest(found);
        if (reached != verdict) {
            throw underTest.failure("reported " + reached + " for '" + scenario.name() + "' on the " + path
                    + " leg with '" + key + "=" + spelling + "'. This subject is declared as the one that dial "
                    + "governs, so the dimension must evaluate it and report exactly the verdict named - including "
                    + "ALLOW, which permits rather than switching anything off.");
        }
        if (verdict != Verdict.ALLOW) {
            return;
        }
        // The permit itself. Without this the whole ALLOW iteration would be vacuous for a dimension that
        // short-circuits before it looks at the subject: an empty finding list folds to ALLOW by definition, so
        // "reported ALLOW" is true of a dimension that reported nothing at all - which is exactly what six of the
        // seven dial-carrying dimensions used to do.
        if (found.isEmpty()) {
            throw underTest.failure("raised no finding at all for '" + scenario.name() + "' on the " + path
                    + " leg with '" + key + "=ALLOW', although this subject is the one that dial governs - so the "
                    + "dimension evaluated it and permitted it, and said nothing. A permitted artifact's assessment "
                    + "is then byte-identical to one this dimension had nothing against, and an incident review "
                    + "cannot tell 'nobody configured this' from 'somebody set it to permit'. A permit is a "
                    + "Finding(ALLOW, <why>) - the shape the gate already uses for a VEX-suppressed or waived "
                    + "advisory, and the shape every dimension in this family takes.");
        }
    }

    // --- one dimension under test -------------------------------------------------------------------------------

    /**
     * Everything a check needs about the dimension it is driving, resolved once per check: the discovered provider,
     * the gate flavors its declaration carries, and the settings that switch every <em>other</em> installed dimension
     * off. All three are constants for the run, and each of them costs a {@link ServiceLoader} pass - so deriving them
     * inside a loop header, as an earlier shape of this kit did, ran the discovery hundreds of times per suite to
     * answer the same question.
     *
     * <p>Building one also settles {@link GatePolicyFixture#discovered()} against the SPI's own discovery before any
     * check runs, so a fixture whose module quietly left the graph fails loudly here rather than silently skipping the
     * legs that need {@link GatePolicyProvider#resolve}.
     */
    private record Dimension(GatePolicyFixture fixture,
                             GatePolicyProvider provider,
                             List<Path> carried,
                             Map<String, String> silenced,
                             boolean discovered,
                             GatePolicyMutant mutant) {

        private static Dimension of(GatePolicyFixture fixture, GatePolicyMutant mutant) {
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(mutant, "mutant");
            List<GatePolicyProvider> installed = GatePolicyProvider.installed();
            Optional<GatePolicyProvider> found = installed.stream()
                    .filter(candidate -> candidate.getClass().getName().equals(fixture.providerClass()))
                    .findFirst();
            if (fixture.discovered() != found.isPresent()) {
                throw new AssertionError(fixture.providerClass() + ": the fixture declares discovered()="
                        + fixture.discovered() + ", but GatePolicyProvider.installed() "
                        + (found.isPresent() ? "does find it" : "does not find it")
                        + ". A registered fixture's dimension module must be required by the test module - the kit "
                        + "stands its resolve legs down for an undiscovered provider, and a wrong declaration here "
                        + "would silently shorten the contract instead of failing the census.");
            }
            GatePolicyProvider provider = found.orElseGet(fixture::provider);
            Map<String, String> silenced = new LinkedHashMap<>();
            for (GatePolicyProvider other : installed) {
                if (!other.name().equals(provider.name())) {
                    silenced.put(other.name(), "false");
                }
            }
            List<Path> carried = Stream.of(Path.values()).filter(provider.symmetry()::carries).toList();
            return new Dimension(fixture, provider, carried, Map.copyOf(silenced), found.isPresent(), mutant);
        }

        private Symmetry symmetry() {
            return provider.symmetry();
        }

        private String name() {
            return provider.name();
        }

        private Map<String, String> configured() {
            return fixture.configured();
        }

        private List<GatePolicyFixture.Case> cases() {
            return List.copyOf(fixture.cases());
        }

        private String keys() {
            return new TreeSet<>(configured().keySet()).toString();
        }

        /** The dimension built for one flavor over a settings map; {@code null} is refused here rather than allowed to
         *  surface as a {@link NullPointerException} three frames away. */
        private Optional<GatePolicy> create(Map<String, String> settings, Path path) {
            return create(settings(settings), path);
        }

        private Optional<GatePolicy> create(UnaryOperator<String> settings, Path path) {
            // The substitution seam. It sits on the POLICY rather than on the provider because the kit reaches
            // the dimension two ways and only one of them goes through an object a fixture could hand over; both hand
            // a GatePolicy back, so wrapping it here is the one place that reaches both. Applied after the null probe
            // below, so a provider answering null is still caught rather than wrapped.
            Optional<GatePolicy> created = provider.create(settings, path);
            if (created == null) {
                throw failure("returned null instead of an Optional when asked to create its " + path + " leg; the "
                        + "absence sentinel is Optional.empty(), and null is never a legal SPI result.");
            }
            return created.map(mutant::substitute);
        }

        /** The dimension built for one flavor over the fixture's configured settings. */
        private GatePolicy policy(Path path) {
            return create(configured(), path).orElseThrow(() -> failure("built no policy for the " + path
                    + " leg over its own configured settings (" + keys() + ")"));
        }

        /** Every dimension this settings map resolves onto one gate flavor, with every other installed dimension
         *  switched off - so the list is this fixture's dimension or nothing. */
        private List<GatePolicy> resolve(Map<String, String> settings, Path path) {
            // The SPI's own static ServiceLoader path - the one no fixture substitution can reach - carries the mutant
            // exactly as the create leg does, so a probe cannot be green here for the sole reason that it was never
            // driven.
            return GatePolicyProvider.resolve(settings(merge(silenced, settings)), path).stream()
                    .map(mutant::substitute)
                    .toList();
        }

        /**
         * This dimension resolved through {@link GatePolicyProvider#resolve} - the discovery path a publish or proxy
         * screen really takes. That also makes the lookup a proof the test module roots the dimension's own module: a
         * provider missing from the graph resolves to nothing here.
         */
        private Optional<GatePolicy> sole(Map<String, String> settings, Path path) {
            List<GatePolicy> resolved = resolve(settings, path);
            if (resolved.size() > 1) {
                throw failure("resolve() answered " + resolved.size() + " policies with every other dimension "
                        + "switched off; exactly this dimension should have been left.");
            }
            return resolved.isEmpty() ? Optional.empty() : Optional.of(resolved.getFirst());
        }

        private Map<String, List<ComplianceGate.Finding>> decisions(GatePolicy policy, Path path) {
            return decisions(policy, cases(), path);
        }

        private Map<String, List<ComplianceGate.Finding>> decisions(GatePolicy policy,
                                                                    List<GatePolicyFixture.Case> scenarios,
                                                                    Path path) {
            Map<String, List<ComplianceGate.Finding>> decided = new LinkedHashMap<>();
            for (GatePolicyFixture.Case scenario : scenarios) {
                decided.put(scenario.name(), findings(policy, scenario, path));
            }
            return decided;
        }

        private List<ComplianceGate.Finding> findings(GatePolicy policy, GatePolicyFixture.Case scenario, Path path) {
            return findings(policy, scenario.name(), scenario.subject(), scenario.advisories(), path);
        }

        private List<ComplianceGate.Finding> findings(GatePolicy policy, String what, ComplianceGate.Subject subject,
                                                      List<AdvisorySource.Advisory> advisories, Path path) {
            List<ComplianceGate.Finding> found = policy.assess(subject, advisories);
            if (found == null) {
                throw failure("answered null for '" + what + "' on the " + path + " leg; the absence sentinel is an "
                        + "empty List, because null is never a legal return.");
            }
            return found;
        }

        private AssertionError failure(String message) {
            return new AssertionError(fixture.providerClass() + ": " + message);
        }
    }

    // --- comparison and settings --------------------------------------------------------------------------------

    private static final String PURE = "assess is pure: identical subject and advisories yield identical findings, in "
            + "any order, on either leg, so a re-screen of a stored artifact reproduces the verdict it was published "
            + "under rather than drifting.";

    private static void same(Dimension underTest, String what,
                             Map<String, List<ComplianceGate.Finding>> actual,
                             Map<String, List<ComplianceGate.Finding>> expected,
                             String note) {
        if (actual.equals(expected)) {
            return;
        }
        StringBuilder detail = new StringBuilder(what + " decided differently:");
        for (String subject : expected.keySet()) {
            List<ComplianceGate.Finding> was = expected.get(subject);
            List<ComplianceGate.Finding> now = actual.get(subject);
            if (!Objects.equals(was, now)) {
                detail.append("\n    '").append(subject).append("'\n      first ").append(was)
                        .append("\n      then  ").append(now);
            }
        }
        detail.append("\n  ").append(note);
        throw underTest.failure(detail.toString());
    }

    /** The {@code jenreg.*}-prefixed lookup a provider is handed, over a fixed map; an unset key answers
     *  {@code null}, exactly as the server's accessor does. */
    private static UnaryOperator<String> settings(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return copy::get;
    }

    /** The same lookup, recording every key read in order - so a create whose reads drift between two identical calls
     *  is caught even when its answers happen to agree. */
    private static final class Recording implements UnaryOperator<String> {

        private final Map<String, String> values;
        private final List<String> read = new ArrayList<>();

        private Recording(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public String apply(String key) {
            read.add(key);
            return values.get(key);
        }

        private List<String> read() {
            return List.copyOf(read);
        }
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.put(key, value);
        return Map.copyOf(merged);
    }

    private static Map<String, String> merge(Map<String, String> base, Map<String, String> overlay) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(overlay);
        return Map.copyOf(merged);
    }

    private static <T> List<T> reversed(List<T> values) {
        return List.copyOf(values.reversed());
    }

    private static <T> List<T> rotated(List<T> values) {
        if (values.size() < 2) {
            return List.copyOf(values);
        }
        List<T> copy = new ArrayList<>(values);
        Collections.rotate(copy, 1);
        return List.copyOf(copy);
    }

    private static List<String> sorted(List<ComplianceGate.Finding> findings) {
        return findings.stream().map(Objects::toString).sorted().toList();
    }
}
