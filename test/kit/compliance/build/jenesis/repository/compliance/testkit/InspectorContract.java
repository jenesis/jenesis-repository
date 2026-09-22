package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.BoundedBodyReader;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * The executable {@link QualityInspector} contract: one parameterized body of checks that every inspector runs through
 * an {@link InspectorFixture}, so an inspection property is stated once and proven per implementation instead of being
 * re-asserted - and quietly re-interpreted - in a hand-written suite per format. Each {@link Property} names one
 * documented contract clause; {@link #checks(InspectorFixture)} binds them to a fixture's artifacts.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the inspector, the property and
 * the expectation, so this module stays the compliance and store SPIs plus the JDK, and any test module can
 * require it. The JUnit driver lives under {@code test/**} and turns each check into one dynamic test.
 *
 * <h2>What the tier check actually proves</h2>
 * {@link Property#TIER_HONOURED} hands the inspector a {@link CountingContent} over an oversized body and reads the
 * <em>meter</em> afterwards. It is deliberately not a source scan: an inspector that grew a whole-body read - or a
 * shared helper that stopped bounding one - is caught by the bytes it actually pulled, on the same leg the hardened
 * proxy screens through. The oversized artifact is streamed, never materialised by the kit, so proving a 32 MiB bound
 * costs the inspector's own budget and nothing else.
 *
 * <h2>What the criterion check actually proves</h2>
 * {@link Property#BOUND_DISPOSITION} is the leg that stops the next inspector getting the earlier criterion wrong. A
 * bound-stopped read is driven through the fixture's parsing leg and the disposition its declared
 * {@link InspectorFixture.Reading} owes is asserted: an identity-bearing read must refuse, an optional one must
 * degrade to the path-derived coordinate, a content scan must not fail the publish at all, and a path-only inspector
 * must answer what it answers over an empty body - the one reading for which no bound can bind, asserted positively so
 * it cannot become a way out of the other two. A fixture that declares one side and behaves like the other fails here,
 * which is the whole point of making the side a declaration.
 *
 * <h2>Clauses this kit discharges</h2>
 * so a clause named here leaves the principle-checkup checklist. Claim only what a check really falsifies:
 * {@code DECLARES_NOTHING_IS_EMPTY} is the absence sentinel, {@code MALFORMED_FAILS_CLOSED} the error-visibility
 * clause, {@code IDEMPOTENT_SUBJECTS} the idempotency clause, {@code CONTENT_REOPENS} together with
 * {@code PROXIED_SUBJECTS} the streaming clause (the body is reached through the re-openable handle, never a heap
 * copy), and {@code TIER_HONOURED} the bounded-work clause (the declared ceiling is shown to bound the read).
 *
 * @jenesis.covers build.jenesis.repository.compliance.QualityInspector 2, 3, 5, 7, 12
 */
public final class InspectorContract {

    /**
     * One documented contract clause of {@link QualityInspector}. The enum is the kit's vocabulary: the census fails
     * on a property no fixture exercises, so the list can never grow a clause that is asserted nowhere.
     */
    public enum Property {

        /** {@link QualityInspector#handles} claims every artifact this fixture supplies, refuses a path of the
         *  fixture's format that is served by nothing, and answers the same both times - it is a pure predicate over
         *  the path, not a read. */
        CLAIMS_ITS_OWN_ARTIFACTS,

        /** The publish leg over a well-formed artifact yields exactly the subjects the fixture declares - the
         *  coordinate the format keys on, and whatever licences the manifest declared. */
        WELL_FORMED_SUBJECTS,

        /** The fully-spooled leg over a well-formed artifact yields exactly the subjects the fixture declares for it,
         *  and it reaches them through {@link QualityInspector.Content}, never a heap copy of the body. */
        PROXIED_SUBJECTS,

        /** An artifact that carries nothing to assess yields an empty list - a positive "understood, declares
         *  nothing", never {@code null} and never a papered-over parse failure. Asserted on the fully-spooled leg
         *  always, and on the publish leg for every inspector whose fixture declares
         *  {@link InspectorFixture#publishLegIsRouteGated()}. */
        DECLARES_NOTHING_IS_EMPTY,

        /** An artifact the inspector claimed but cannot parse fails closed with {@link MalformedArtifactException} -
         *  except for a content scan, which must never fail a publish over bytes it could not interpret, a path-only
         *  reading, which never parsed anything, and an inspector whose fixture declares the §13 divergence
         *  {@link InspectorFixture#malformedIsRefused()}, whose degraded answer is asserted instead. */
        MALFORMED_FAILS_CLOSED,

        /** The same path and the same bytes yield equal subjects however often they are inspected, on either leg - a
         *  publish is re-screened on migration and re-inspection, so a drifting result would change a stored verdict
         *  without the artifact having changed. */
        IDEMPOTENT_SUBJECTS,

        /** {@link QualityInspector.Content} is re-openable and is re-opened: inspecting one handle twice yields equal
         *  subjects and opens the body twice, so no inspector holds a drained stream, and the handle still streams the
         *  whole body afterwards. */
        CONTENT_REOPENS,

        /** The declared inspection tier is honoured over an oversized body: the counting content shows the inspector
         *  consumed no more than its declared ceiling and strictly less than the body, the declaration matches whether
         *  the spooled leg is actually overridden, and a {@link InspectorFixture.Tier#FULL_BODY} ceiling sits strictly
         *  ABOVE the prefix tier - a whole-artifact leg that reads less far than the bounded-prefix leg buys nothing
         *  and breaks the screens' completeness test. */
        TIER_HONOURED,

        /** A read the bound stopped takes the disposition the fixture's declared {@link InspectorFixture.Reading}
         *  owes: identity refuses, an optional declaration degrades, a content scan does neither, and a path-only
         *  reading answers what the path alone yields - proven against the same path over an empty body. */
        BOUND_DISPOSITION,

        /** A read the bound stopped is REPORTED as one, and a read that ran to completion is reported as that: the
         *  fully-spooled leg's {@link QualityInspector.Inspection#complete()} tells the screen which kind of answer it
         *  is holding, so an empty list over a body nobody finished reading can never be taken for "understood,
         *  declares nothing". Asserted in both directions - over the oversized artifact and over the
         *  well-formed one - because a leg that reported everything incomplete would be as useless as one that
         *  reported everything complete, and only the pair pins the report to the read. */
        BOUND_STOPPED_IS_REPORTED
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
        void run(InspectorFixture fixture) throws Exception;
    }

    /**
     * One deliberately broken substitution for the inspector a property's check <em>must</em> fail against, and why
     * (carrying the earlier mechanism here). The predicate is the contract's, not the fixture's: a mutation whose
     * removed behaviour an inspector's declared shape genuinely does not have - a spooled re-open for an inspector
     * that does not own the spooled leg - is declared inapplicable here, in front of whoever reviews the kit, rather
     * than waived inside the fixture it would excuse.
     */
    public record Mutation(InspectorMutant mutant, Predicate<InspectorFixture> appliesTo, String why) {

        public Mutation {
            Objects.requireNonNull(mutant, "mutant");
            Objects.requireNonNull(appliesTo, "appliesTo");
            if (why == null || why.isBlank()) {
                throw new AssertionError("a mutation must say which claim of the property it removes, or a later "
                        + "reader cannot tell a targeted mutation from one that merely happens to fail");
            }
        }

        /** A mutation every fixture's leg of this property must break. */
        public Mutation(InspectorMutant mutant, String why) {
            this(mutant, _ -> true, why);
        }
    }

    /**
     * What must break each property, keyed by property - the kit's falsification declaration.
     *
     * <p>The mutations are the <em>weakest</em> break each property forbids rather than the most destructive one
     * available: {@link InspectorMutant#AN_INSPECTOR_THAT_REPORTS_NOTHING} would fail several of these, and a table
     * full of it would be a table of trivial mutations satisfying a count while proving nothing. It appears here
     * exactly nowhere; it is the general vacuity probe the census runs, and its survivors are what this table has to
     * account for.
     *
     * <p>Three mutations are predicated on the inspector's tier, and that is the kit's honest edge rather than a
     * convenience. A {@link InspectorFixture.Tier#PREFIX} inspector does not override the fully-spooled leg at all -
     * the SPI's own bounded bridge is that leg for it - so no substitution for the <em>inspector</em> can change how
     * the spooled handle is opened or how far it is read. {@link Property#CONTENT_REOPENS},
     * {@link Property#TIER_HONOURED} and {@link Property#BOUND_STOPPED_IS_REPORTED} are therefore claims about the
     * bridge for those fixtures, and the census argues their survivors on exactly that ground instead of pretending
     * a mutation reaches them.
     */
    public static Map<Property, List<Mutation>> mutations() {
        Map<Property, List<Mutation>> mutations = new EnumMap<>(Property.class);
        mutations.put(Property.CLAIMS_ITS_OWN_ARTIFACTS, List.of(
                new Mutation(InspectorMutant.A_WIDER_CLAIM,
                        "the clause is the predicate's PRECISION, in both directions, and only the negative half can "
                                + "be lost quietly: an inspector that claims everything still claims all its own "
                                + "artifacts, so every positive assertion here stays green while the inspector gates "
                                + "another format's uploads and makes its own claims vacuous")));
        mutations.put(Property.WELL_FORMED_SUBJECTS, List.of(
                new Mutation(InspectorMutant.A_SUBJECT_THE_ARTIFACT_DOES_NOT_CARRY,
                        "the clause is that the publish leg reads EXACTLY the declared coordinate and licences, so the "
                                + "weakest break is one subject more than the artifact carries - the over-declaring "
                                + "direction, which gates a coordinate the repository does not hold")));
        mutations.put(Property.PROXIED_SUBJECTS, List.of(
                new Mutation(InspectorMutant.A_SUBJECT_THE_ARTIFACT_DOES_NOT_CARRY,
                        "the same claim on the fully-spooled leg, and it is asserted separately because the two legs "
                                + "reach the artifact by different routes and have diverged per format before")));
        mutations.put(Property.DECLARES_NOTHING_IS_EMPTY, List.of(
                new Mutation(InspectorMutant.A_SUBJECT_THE_ARTIFACT_DOES_NOT_CARRY,
                        "THE entry this table exists for. The check's whole expectation is an empty list, so an "
                                + "inspector that reports nothing about anything satisfies it by construction and the "
                                + "general vacuity probe cannot tell the two apart. What the clause really forbids is "
                                + "the opposite of the empty answer - screening a coordinate off a document that "
                                + "declares none (clause 3 versus clause 7) - and one invented subject is exactly "
                                + "that, and nothing else")));
        mutations.put(Property.MALFORMED_FAILS_CLOSED, List.of(
                new Mutation(InspectorMutant.A_SWALLOWED_REFUSAL,
                        fixture -> fixture.malformedIsRefused()
                                && fixture.reading() != InspectorFixture.Reading.CONTENT_FINDINGS
                                && fixture.reading() != InspectorFixture.Reading.IDENTITY_IN_PATH,
                        "for an inspector that refuses, the clause is the refusal itself, and the way it is lost in "
                                + "practice is a catch that turns 'this is not the archive it says it is' into "
                                + "'understood, declares nothing' - the one answer clause 7 says it must never be "
                                + "confused with"),
                new Mutation(InspectorMutant.A_SUBJECT_THE_ARTIFACT_DOES_NOT_CARRY,
                        fixture -> !fixture.malformedIsRefused()
                                || fixture.reading() == InspectorFixture.Reading.CONTENT_FINDINGS
                                || fixture.reading() == InspectorFixture.Reading.IDENTITY_IN_PATH,
                        "for an inspector that legitimately does NOT refuse, the clause is what its degraded answer "
                                + "may contain, and the leg is only worth anything while 'admits it' cannot quietly "
                                + "become 'invents a coordinate for it'. One subject the unparseable body does not "
                                + "carry is that, minimally")));
        mutations.put(Property.IDEMPOTENT_SUBJECTS, List.of(
                new Mutation(InspectorMutant.A_DRIFTING_ANSWER,
                        "the clause is a relation - the same bytes answer the same twice - and a relation is "
                                + "trivially satisfied by any constant answer, the empty one included. So the "
                                + "mutation is the smallest thing the relation forbids: a second inspection that "
                                + "differs from the first, which is what re-screening a stored artifact on a "
                                + "migration would turn into a changed verdict over bytes that never moved")));
        mutations.put(Property.CONTENT_REOPENS, List.of(
                new Mutation(InspectorMutant.A_HANDLE_OPENED_ONCE_AND_REMEMBERED,
                        fixture -> fixture.tier() == InspectorFixture.Tier.FULL_BODY,
                        "the clause is that the inspector re-STREAMS rather than holding what it read, so the "
                                + "mutation is the holding: one open, the answer remembered against that handle, and "
                                + "every later inspection served from memory. Declared only for a full-body "
                                + "inspector, because a prefix-tier one does not own the spooled leg - the SPI's "
                                + "bridge does, and it is the bridge this check then measures")));
        mutations.put(Property.TIER_HONOURED, List.of(
                new Mutation(InspectorMutant.AN_UNBOUNDED_READ,
                        fixture -> fixture.tier() == InspectorFixture.Tier.FULL_BODY,
                        "the clause is that full-body does not mean unbounded, so the mutation is the unbounded read "
                                + "and nothing else: the body is drained end to end before the inspector is asked, "
                                + "which is the attacker-shaped artifact pinning the publish thread. Declared only "
                                + "for a full-body inspector, because a prefix-tier one cannot spend more than the "
                                + "SPI's bridge hands it whatever it does")));
        mutations.put(Property.BOUND_DISPOSITION, List.of(
                new Mutation(InspectorMutant.A_REFUSAL_WHERE_A_DEGRADE_IS_OWED,
                        fixture -> fixture.reading() != InspectorFixture.Reading.IDENTITY_IN_ARTIFACT,
                        "for every reading whose bounded read carries no identity, the clause is that reaching the "
                                + "bound DEGRADES: losing an optional declaration can only under-declare, so refusing "
                                + "there holds a publish nothing is wrong with. The mutation is that refusal"),
                new Mutation(InspectorMutant.A_SWALLOWED_REFUSAL,
                        fixture -> fixture.reading() == InspectorFixture.Reading.IDENTITY_IN_ARTIFACT,
                        "and for the one reading that must fail closed, the mirror: the coordinate exists nowhere but "
                                + "inside the body, so a swallowed refusal publishes a package that NOTHING screened")));
        mutations.put(Property.BOUND_STOPPED_IS_REPORTED, List.of(
                new Mutation(InspectorMutant.A_BOUND_STOPPED_READ_REPORTED_COMPLETE,
                        fixture -> fixture.tier() == InspectorFixture.Tier.FULL_BODY
                                && fixture.reading() != InspectorFixture.Reading.IDENTITY_IN_ARTIFACT,
                        "the clause is the report itself, so the mutation is the smallest possible lie about it: the "
                                + "subjects stay exactly what the inspector really found and only the completeness "
                                + "flips. Nothing else in the kit can see that difference, which is the point - it is "
                                + "what a screen reads to tell 'there is nothing there' from 'I could not look'. "
                                + "Declared only for a full-body inspector, because a prefix-tier one's report is "
                                + "computed by the SPI's bridge from the body it was handed and no substitution for "
                                + "the inspector can reach it - and not for a reading whose bound-stopped read "
                                + "REFUSES, because a refusal carries no completeness report to lie about. That is "
                                + "not a weaker claim for those: BOUND_DISPOSITION asserts the refusal itself, and "
                                + "a swallowed refusal is the mutation that breaks it")));
        return Collections.unmodifiableMap(mutations);
    }

    /** The mutations {@code fixture}'s leg of {@code property} must fail against - every declared one whose shape this
     *  inspector has. An empty answer means the property carries no falsification for this fixture, which the census
     *  holds to a reviewed list. */
    public static List<Mutation> mutations(InspectorFixture fixture, Property property) {
        Objects.requireNonNull(fixture, "fixture");
        return mutations().getOrDefault(property, List.of()).stream()
                .filter(mutation -> mutation.mutant().appliesTo(fixture))
                .filter(mutation -> mutation.appliesTo().test(fixture))
                .toList();
    }

    private InspectorContract() {
        throw new UnsupportedOperationException("InspectorContract is a static utility");
    }

    /**
     * Every contract check, in declaration order. The list <em>is</em> the contract: an inspector runs all of it.
     * There is deliberately no per-fixture exclusion seam - unlike a store backend, an inspector has no environment
     * that could fail to express a property, so an exclusion could only ever mean "this one does not comply".
     */
    public static List<Check> checks() {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check(Property.CLAIMS_ITS_OWN_ARTIFACTS,
                "handles() claims every artifact the fixture supplies and refuses one served by nothing",
                InspectorContract::claimsItsOwnArtifacts));
        checks.add(new Check(Property.WELL_FORMED_SUBJECTS,
                "the publish leg reads the declared coordinate and licences off a well-formed artifact",
                InspectorContract::wellFormedSubjects));
        checks.add(new Check(Property.PROXIED_SUBJECTS,
                "the fully-spooled leg screens a well-formed artifact into the declared subjects",
                InspectorContract::proxiedSubjects));
        checks.add(new Check(Property.DECLARES_NOTHING_IS_EMPTY,
                "an artifact carrying nothing to assess yields an empty list, never null",
                InspectorContract::declaresNothingIsEmpty));
        checks.add(new Check(Property.MALFORMED_FAILS_CLOSED,
                "a claimed but unparseable artifact fails closed (a content scan finds nothing instead)",
                InspectorContract::malformedFailsClosed));
        checks.add(new Check(Property.IDEMPOTENT_SUBJECTS,
                "the same bytes inspected twice yield equal subjects on both legs",
                InspectorContract::idempotentSubjects));
        checks.add(new Check(Property.CONTENT_REOPENS,
                "one Content handle is re-opened per pass and still streams the whole body afterwards",
                InspectorContract::contentReopens));
        checks.add(new Check(Property.TIER_HONOURED,
                "the declared inspection tier binds on an oversized body, proven by a counting Content",
                InspectorContract::tierHonoured));
        checks.add(new Check(Property.BOUND_DISPOSITION,
                "a bound-stopped read refuses or degrades exactly as the declared reading owes",
                InspectorContract::boundDisposition));
        checks.add(new Check(Property.BOUND_STOPPED_IS_REPORTED,
                "the fully-spooled leg reports a bound-stopped read as one, and a completed read as completed",
                InspectorContract::boundStoppedIsReported));
        return List.copyOf(checks);
    }

    /** The checks {@code fixture} runs - every one of them; the argument exists so a driver names the fixture it is
     *  binding and so the census can assert that no inspector runs a shortened contract. */
    public static List<Check> checks(InspectorFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        return checks();
    }

    // --- the contract ------------------------------------------------------------------------------------------

    private static void claimsItsOwnArtifacts(InspectorFixture fixture) {
        QualityInspector inspector = fixture.inspector();
        for (InspectorFixture.Artifact artifact : artifacts(fixture)) {
            boolean claimed = inspector.handles(artifact.path());
            if (claimed != inspector.handles(artifact.path())) {
                throw failure(fixture, "handles() answered differently for " + artifact.path() + " on two calls; it "
                        + "is a pure predicate over the path, called on every publish and every proxy read.");
            }
            if (!claimed) {
                throw failure(fixture, "handles() refuses " + artifact.path() + ", which this fixture supplies as an "
                        + "artifact of its own format. An inspector that does not claim a path is never asked to "
                        + "screen it, so the artifact would publish and serve ungated.");
            }
        }
        if (inspector.handles(fixture.unclaimed())) {
            throw failure(fixture, "handles() claims " + fixture.unclaimed() + ", which is served by nothing. A "
                    + "claim that wide either gates artifacts of another format or makes the fixture's positive "
                    + "claims vacuous; narrow the predicate, or declare a shape this inspector legitimately owns.");
        }
    }

    private static void wellFormedSubjects(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact artifact = fixture.published();
        equal(fixture, "the publish leg over " + artifact.path(),
                publish(fixture, artifact), artifact.subjects());
    }

    private static void proxiedSubjects(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact artifact = fixture.proxied();
        CountingContent content = content(artifact);
        equal(fixture, "the fully-spooled leg over " + artifact.path(),
                spooled(fixture, artifact.path(), content), artifact.subjects());
        if (content.opened() > 0) {
            return;
        }
        // Not every leg has to read the body to ANSWER, and one that did not is not thereby ignoring it.
        // An inspector whose declaration sources are ranked may find a better one beside the artifact -
        // Maven prefers the deployed sibling POM to the descriptor the jar carries, and documents the
        // order - so the first pass proves nothing on its own. What no leg may do is ignore the body
        // altogether, so the question is put again with nothing beside the artifact: with no sibling to
        // prefer, a leg that screens the body has to reach for it. Only the opening is read here, never
        // the subjects, because an inspection with no siblings is a different inspection.
        CountingContent alone = content(artifact);
        spooled(fixture, artifact.path(), alone, QualityInspector.Lookup.none());
        if (alone.opened() == 0) {
            throw failure(fixture, "the fully-spooled leg over " + artifact.path() + " never opened the "
                    + "Content it was handed - with the fixture's siblings and again with none. A leg "
                    + "that ignores the spooled body cannot be screening it.");
        }
    }

    private static void declaresNothingIsEmpty(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact artifact = fixture.nothingToAssess();
        List<List<ComplianceGate.Subject>> answers = new ArrayList<>();
        answers.add(spooled(fixture, artifact.path(), content(artifact)));
        if (fixture.publishLegIsRouteGated()) {
            answers.add(publish(fixture, artifact));
        }
        for (List<ComplianceGate.Subject> answer : answers) {
            if (answer == null) {
                throw failure(fixture, "an inspection of " + artifact.path() + " returned null; the absence sentinel "
                        + "is an empty List, because null is never a legal return.");
            }
            if (!answer.isEmpty()) {
                throw failure(fixture, artifact.path() + " carries nothing to assess, but the inspector answered with "
                        + answer + ". Screening a coordinate off a document that declares none invents a subject the "
                        + "repository does not hold, and is the exact opposite of a failed parse (clause 3 vs 7).");
            }
        }
    }

    private static void malformedFailsClosed(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact artifact = fixture.malformed();
        if (fixture.reading() == InspectorFixture.Reading.CONTENT_FINDINGS) {
            equal(fixture, "a content scan of the unparseable " + artifact.path(),
                    parse(fixture, artifact), artifact.subjects());
            return;
        }
        if (fixture.reading() == InspectorFixture.Reading.IDENTITY_IN_PATH) {
            // Nothing was parsed, so nothing can have failed to parse: the answer must be the path's, unchanged. An
            // inspector that refused here would be reading a body its own declaration says it never opens.
            equal(fixture, "the path-derived reading of the unparseable " + artifact.path(),
                    parse(fixture, artifact), artifact.subjects());
            return;
        }
        if (!fixture.malformedIsRefused()) {
            // A declared §13 divergence (see InspectorFixture.malformedIsRefused): this inspector admits a body that
            // is not the artifact it claims to be, on its path-derived coordinate. The leg is not skipped - the
            // degraded answer is asserted, so "admits it" cannot quietly become "invents a coordinate for it".
            equal(fixture, "the declared-divergent degraded reading of the unparseable " + artifact.path(),
                    parse(fixture, artifact), artifact.subjects());
            return;
        }
        try {
            List<ComplianceGate.Subject> subjects = parse(fixture, artifact);
            throw failure(fixture, "the " + fixture.parsingLeg() + " leg accepted " + artifact.path() + ", which this "
                    + "inspector claims but cannot parse, and answered " + subjects + ". A could-not-parse of a "
                    + "CLAIMED artifact is MalformedArtifactException - the screens hold the publish and refuse the "
                    + "proxied body on it - and is strictly distinct from an empty result.");
        } catch (MalformedArtifactException expected) {
            if (expected.getMessage() == null || expected.getMessage().isBlank()) {
                throw failure(fixture, "the refusal of " + artifact.path() + " carries no message; an operator sees "
                        + "only this text when a publish is held.");
            }
        }
    }

    private static void idempotentSubjects(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact published = fixture.published();
        equal(fixture, "the publish leg over " + published.path() + ", inspected twice",
                publish(fixture, published), publish(fixture, published));
        InspectorFixture.Artifact proxied = fixture.proxied();
        equal(fixture, "the fully-spooled leg over " + proxied.path() + ", inspected twice",
                spooled(fixture, proxied.path(), content(proxied)),
                spooled(fixture, proxied.path(), content(proxied)));
    }

    /**
     * Asked under the fixture's own siblings and, if that did not reach the body, again with none.
     *
     * <p>Which of the two reaches it is a property of the inspector rather than of this claim. One whose
     * declaration sources are RANKED may answer from a sibling ahead of the artifact - Maven prefers the deployed
     * POM to the descriptor the jar carries, and documents the order - so under its own lookup it never opens the
     * handle. One whose material IS a sibling does the opposite: the signature inspector finds nothing to verify
     * without the lookup and then has no reason to read the body either. Either is legitimate; what no leg may do
     * is ignore the body under both, and that is what this asserts.
     */
    private static void contentReopens(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact artifact = fixture.proxied();
        if (reopened(fixture, artifact, fixture.lookup())
                || reopened(fixture, artifact, QualityInspector.Lookup.none())) {
            return;
        }
        throw failure(fixture, "the fully-spooled leg over " + artifact.path() + " never opened the Content it was "
                + "handed - with the fixture's siblings and again with none. A leg that ignores the spooled body "
                + "cannot be screening it.");
    }

    /** Two inspections of ONE handle under one lookup, and whether the leg reached for the body at all. */
    private static boolean reopened(InspectorFixture fixture, InspectorFixture.Artifact artifact,
                                    QualityInspector.Lookup lookup) throws Exception {
        CountingContent content = content(artifact);
        List<ComplianceGate.Subject> first = spooled(fixture, artifact.path(), content, lookup);
        // What the SECOND inspection opened, not what both did between them. An inspector that opens the handle
        // more than once per inspection - Debian reads the control member and the copyright in two passes -
        // already satisfies a cumulative "opened at least twice" after its FIRST inspection, so the cumulative
        // form cannot see a second inspection served entirely from memory. Measured 2026-09-15: the mutant that
        // remembers one answer per handle survived this check for exactly that reason, which is what the
        // falsifiability census is for.
        int beforeSecond = content.opened();
        List<ComplianceGate.Subject> second = spooled(fixture, artifact.path(), content, lookup);
        equal(fixture, "one Content handle inspected twice", second, first);
        if (beforeSecond == 0) {
            return false;                    // it never reached for the body under this lookup; try the other
        }
        if (content.opened() <= beforeSecond) {
            throw failure(fixture, "the second inspection of one Content handle opened it no further ("
                    + beforeSecond + " opens before it, " + content.opened() + " after). A spooled body is "
                    + "re-openable precisely so an inspector streams it again rather than holding the bytes; "
                    + "an inspector that read it once and kept what it read would not survive the hardened "
                    + "leg's second pass.");
        }
        if (content.size() != drain(content)) {
            throw failure(fixture, "after two inspections the Content no longer streams its whole " + content.size()
                    + "-byte body; the handle must be re-openable from byte zero, not consumed by a reader.");
        }
        return true;
    }

    private static void tierHonoured(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact artifact = fixture.oversized();
        long ceiling = fixture.readCeiling();
        // The declaration is reconciled with the code first: which tier an inspector is on is decided by whether it
        // overrides the spooled leg, and a ceiling is only meaningful once that is settled.
        if (overridesSpooledLeg(fixture) != (fixture.tier() == InspectorFixture.Tier.FULL_BODY)) {
            throw failure(fixture, "the declared " + fixture.tier() + " tier disagrees with the code: "
                    + (fixture.tier() == InspectorFixture.Tier.FULL_BODY
                            ? "a FULL_BODY inspector must override inspectArtifact(String, Content, Lookup); one that "
                                    + "does not is bridged through the prefix and cannot see past it."
                            : "this inspector overrides inspectArtifact(String, Content, Lookup), so it reads the "
                                    + "spooled body itself and owes the FULL_BODY tier's declared ceiling."));
        }
        if (fixture.tier() == InspectorFixture.Tier.PREFIX && ceiling != QualityInspector.PREFIX_INSPECTION_LIMIT) {
            throw failure(fixture, "a PREFIX-tier fixture declares a read ceiling of " + ceiling + "; the prefix tier "
                    + "is " + QualityInspector.PREFIX_INSPECTION_LIMIT + " and a fixture may not raise it - the SPI's "
                    + "own bridge caps the read, so a different number is a claim about a different inspector.");
        }
        if (fixture.tier() == InspectorFixture.Tier.FULL_BODY && ceiling <= QualityInspector.PREFIX_INSPECTION_LIMIT) {
            // The relationship between the two tiers, asserted rather than described. A full-body leg exists to read
            // PAST the bounded prefix; one whose ceiling is at or below the prefix tier buys bounded heap and no reach,
            // and it silently breaks the screens, which decide "did the inspectors see the whole body?" by comparing
            // the body's length against the prefix tier. An inspector reading less far than that answers empty over a
            // body the screen believes was seen whole, and an empty answer is ALLOWed as "understood, declares
            // nothing". That was live: the secret scanner's own 16 MiB budget was half the prefix tier.
            throw failure(fixture, "a FULL_BODY fixture declares a read ceiling of " + ceiling + ", which is not above "
                    + "the prefix tier (" + QualityInspector.PREFIX_INSPECTION_LIMIT + "). The spooled leg exists to "
                    + "read PAST the bounded prefix, so a ceiling at or below it buys bounded heap and nothing else - "
                    + "and the screens read an empty result over a body shorter than the prefix tier as a COMPLETE "
                    + "screen, so this inspector's misses would be ALLOWed rather than held. Raise the inspector's "
                    + "ceiling to the shared full-body tier (" + QualityInspector.FULL_BODY_INSPECTION_LIMIT + "), or "
                    + "declare Tier.PREFIX and drop the override.");
        }
        CountingContent content = content(artifact);
        if (content.size() <= QualityInspector.PREFIX_INSPECTION_LIMIT) {
            throw failure(fixture, "the oversized artifact is only " + content.size() + " bytes, which the prefix "
                    + "tier (" + QualityInspector.PREFIX_INSPECTION_LIMIT + ") never binds on. The check would pass "
                    + "without any bound existing at all; supply a body larger than the tier.");
        }
        try {
            spooled(fixture, artifact.path(), content);
        } catch (MalformedArtifactException _) {
            // An identity-bearing read refuses a bound-stopped artifact; the disposition is BOUND_DISPOSITION's leg.
            // What matters here is the meter, which is read either way.
        }
        if (content.consumed() > ceiling) {
            throw failure(fixture, "the fully-spooled leg pulled " + content.consumed() + " bytes off a "
                    + content.size() + "-byte body, past the declared " + fixture.tier() + " ceiling of " + ceiling
                    + ". Every read an inspector makes is bounded, and full-body does not mean unbounded: an "
                    + "attacker-shaped artifact would otherwise pin the publish thread.");
        }
        if (content.widest() >= content.size()) {
            // The WIDEST pass, not the total: an inspector that opens the body more than once - Maven reads
            // a jar up to three times, one rung each - spends more in total than the artifact holds while
            // every one of its passes was stopped by the bound. The total is checked against the declared
            // ceiling above, which is what bounds the work; this is what bounds the reach.
            throw failure(fixture, "the fully-spooled leg read one pass to the end of the " + content.size()
                    + "-byte body, so no bound bound. The check must fail before an unbounded read can pass it.");
        }
        if (content.consumed() == 0) {
            throw failure(fixture, "the fully-spooled leg read nothing at all from the oversized body, so the meter "
                    + "proves nothing. A vacuous tier check is worse than none.");
        }
    }

    private static void boundDisposition(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact artifact = fixture.oversized();
        switch (fixture.reading()) {
            case IDENTITY_IN_ARTIFACT -> {
                try {
                    List<ComplianceGate.Subject> subjects = parse(fixture, artifact);
                    throw failure(fixture, "the bound stopped the read of " + artifact.path() + " before its "
                            + "coordinate, and the inspector answered " + subjects + " anyway. This inspector "
                            + "declares IDENTITY_IN_ARTIFACT: its coordinate exists nowhere but inside the body, so "
                            + "degrading here publishes or serves a package that NOTHING screened. Fail closed, or "
                            + "declare OPTIONAL_BESIDE_COORDINATE and justify a path-derived fallback.");
                } catch (MalformedArtifactException expected) {
                    if (expected.getMessage() == null || expected.getMessage().isBlank()) {
                        throw failure(fixture, "the bound-stopped refusal of " + artifact.path() + " carries no "
                                + "message; the refusal must say what could not be read.");
                    }
                }
            }
            case OPTIONAL_BESIDE_COORDINATE, CONTENT_FINDINGS -> {
                List<ComplianceGate.Subject> subjects;
                try {
                    subjects = parse(fixture, artifact);
                } catch (MalformedArtifactException refused) {
                    throw failure(fixture, "the bound stopped the read of " + artifact.path() + " and the inspector "
                            + "refused the artifact: " + refused.getMessage() + ". This inspector declares "
                            + fixture.reading() + ", whose bounded read carries no identity - losing it can only "
                            + "under-declare - so reaching the bound must degrade rather than hold a publish that "
                            + "nothing is wrong with. Take ArchiveWalk.Found.orNull() here, or declare "
                            + "IDENTITY_IN_ARTIFACT.");
                }
                equal(fixture, "the degraded read of the oversized " + artifact.path(), subjects, artifact.subjects());
            }
            case IDENTITY_IN_PATH -> {
                // No read happened, so no bound can have bound: this leg proves the stronger claim the declaration
                // makes - that the subjects are a function of the PATH alone. The same path is driven over an empty
                // body, and a difference between the two answers means the body was consulted after all, which is the
                // one way this reading could be a mis-declaration rather than a description.
                List<ComplianceGate.Subject> overSized;
                try {
                    overSized = parse(fixture, artifact);
                } catch (MalformedArtifactException refused) {
                    throw failure(fixture, "the oversized " + artifact.path() + " was refused: "
                            + refused.getMessage() + ". This inspector declares IDENTITY_IN_PATH - its coordinate is "
                            + "the request path and it opens no body - so there is nothing here a bound could have "
                            + "stopped. An inspector that refuses a body it does not read belongs on one of the "
                            + "other readings.");
                }
                equal(fixture, "the path-derived read of the oversized " + artifact.path(),
                        overSized, artifact.subjects());
                equal(fixture, "the same path (" + artifact.path() + ") over an EMPTY body - if this differs from the "
                                + "oversized answer the body was read after all, and the declared IDENTITY_IN_PATH is "
                                + "a claim about a different inspector",
                        parse(fixture, new InspectorFixture.Artifact(artifact.path(), InputStream::nullInputStream)),
                        overSized);
            }
        }
    }

    // --- the legs ----------------------------------------------------------------------------------------------

    /** The publish leg, reached exactly as the screens reach it: the shared bounded bridge from the spooled body down
     *  to the {@code byte[]} prefix tier, so the kit's own read is the SPI's read and cannot be more generous. */
    private static List<ComplianceGate.Subject> publish(InspectorFixture fixture, InspectorFixture.Artifact artifact)
            throws IOException {
        return fixture.inspector().inspect(artifact.path(),
                BoundedBodyReader.readPrefix(content(artifact)), fixture.lookup());
    }

    /**
     * The fully-spooled leg says which kind of empty its empty list is. Over the fixture's OVERSIZED artifact -
     * bigger than the prefix tier, and bigger than any declared full-body ceiling the kit accepts - the read cannot
     * have finished, so it must report {@code complete() == false}; over the WELL-FORMED one, which every fixture
     * sizes to what its inspector really reads, it must report the read it actually completed. Both directions matter:
     * a leg hard-wired to either answer would pass one of them, and it is the pair that ties the report to the read.
     *
     * <p>For a bridged inspector this is a check on the SPI's own default, which computes the report from the body it
     * was handed - which is exactly right, since that IS the leg for a prefix-tier inspector, and it is why no mutant
     * can falsify this property for one (see {@link InspectorMutant}).
     */
    private static void boundStoppedIsReported(InspectorFixture fixture) throws Exception {
        InspectorFixture.Artifact oversized = fixture.oversized();
        QualityInspector.Inspection stopped;
        try {
            stopped = fixture.inspector().inspectArtifact(oversized.path(), content(oversized), fixture.lookup());
        } catch (MalformedArtifactException _) {
            // An identity-bearing read refuses a bound-stopped artifact outright (BOUND_DISPOSITION's leg): there is
            // no answer to carry a completeness report, and a refusal is the loudest possible form of the same fact.
            return;
        }
        if (stopped.complete()) {
            throw failure(fixture, "the fully-spooled leg reports a COMPLETE read of " + oversized.path()
                    + ", whose declaration sits past this inspector's tier - so the read cannot have reached it. A "
                    + "screen reads that report as 'the artifact was seen whole', and an empty subject list under it "
                    + "as 'understood, declares nothing', which is exactly how an artifact nobody finished screening "
                    + "is ALLOWed. Report the stop; the SPI's own bridge does it for a prefix-tier inspector, "
                    + "and a full-body one owes its engine's own answer.");
        }
        InspectorFixture.Artifact wellFormed = fixture.proxied();
        QualityInspector.Content body = content(wellFormed);
        QualityInspector.Inspection finished = fixture.inspector()
                .inspectArtifact(wellFormed.path(), body, fixture.lookup());
        if (!finished.complete() && body.size() <= QualityInspector.PREFIX_INSPECTION_LIMIT) {
            throw failure(fixture, "the fully-spooled leg reports a BOUND-STOPPED read of the well-formed "
                    + wellFormed.path() + ", which is " + body.size() + " bytes - inside every tier this inspector "
                    + "reads at. A leg that reports every read incomplete tells a screen nothing: the report is a "
                    + "measurement of the read, not a constant.");
        }
    }

    /** The fully-spooled leg's subjects. The leg answers a {@link QualityInspector.Inspection} - the subjects plus
     *  whether the read behind them ran to completion - and every check above is about the subjects, so the
     *  unwrapping lives here rather than in eight call sites. The completeness half has its own check. */
    private static List<ComplianceGate.Subject> spooled(InspectorFixture fixture, String path,
                                                        QualityInspector.Content body) throws IOException {
        return spooled(fixture, path, body, fixture.lookup());
    }

    /** The same leg over a lookup the caller chooses, for the one check that asks what an inspection
     *  does when nothing is published beside the artifact. */
    private static List<ComplianceGate.Subject> spooled(InspectorFixture fixture, String path,
                                                        QualityInspector.Content body,
                                                        QualityInspector.Lookup lookup)
            throws IOException {
        QualityInspector.Inspection inspection;
        try {
            inspection = fixture.inspector().inspectArtifact(path, body, lookup);
        } catch (NullPointerException absent) {
            // The absence sentinel, one layer down: an inspector that answers null from the byte[] leg is refused by
            // the Inspection the SPI's bridge wraps it in, so what reaches the kit is the refusal rather than a null
            // answer. It is still the same contract failure and it is reported as one, with the refusal as its cause.
            throw failure(fixture, "an inspection of " + path + " returned null (the spooled leg refused it: "
                    + absent.getMessage() + "); the absence sentinel is an empty List, because null is never a legal "
                    + "return.");
        }
        if (inspection == null) {
            throw failure(fixture, "the fully-spooled leg over " + path + " returned null; it answers an Inspection, "
                    + "whose subjects are the absence sentinel and whose completeness is never absent.");
        }
        return inspection.subjects();
    }

    /** The fixture's declared parsing leg, over one artifact. */
    private static List<ComplianceGate.Subject> parse(InspectorFixture fixture, InspectorFixture.Artifact artifact)
            throws IOException {
        return switch (fixture.parsingLeg()) {
            case PUBLISH -> publish(fixture, artifact);
            case SPOOLED -> spooled(fixture, artifact.path(), content(artifact));
        };
    }

    private static boolean overridesSpooledLeg(InspectorFixture fixture) {
        try {
            return !fixture.inspector().getClass()
                    .getMethod("inspectArtifact", String.class, QualityInspector.Content.class,
                            QualityInspector.Lookup.class)
                    .getDeclaringClass().equals(QualityInspector.class);
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("the spooled inspection leg is not resolvable on "
                    + fixture.inspector().getClass().getName(), cause);
        }
    }

    // --- the counting content ----------------------------------------------------------------------------------

    /** A {@link QualityInspector.Content} over a fixture body that meters every byte handed out and every re-open, so
     *  a tier bound is proven by what the inspector pulled rather than by what its source appears to say. */
    public static final class CountingContent implements QualityInspector.Content {

        private final InspectorFixture.Body body;
        private final long size;
        private final AtomicLong consumed = new AtomicLong();
        private final AtomicLong widest = new AtomicLong();
        private final AtomicInteger opened = new AtomicInteger();

        private CountingContent(InspectorFixture.Body body, long size) {
            this.body = body;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public InputStream open() throws IOException {
            opened.incrementAndGet();
            AtomicLong pass = new AtomicLong();
            return new FilterInputStream(body.open()) {
                /** Both meters move together: the total is what this inspection COST, the widest single
                 *  pass is how far into the artifact it REACHED, and an inspector that opens the body more
                 *  than once separates the two. */
                private void drew(long bytes) {
                    consumed.addAndGet(bytes);
                    widest.accumulateAndGet(pass.addAndGet(bytes), Math::max);
                }

                @Override
                public int read() throws IOException {
                    int read = super.read();
                    if (read >= 0) {
                        drew(1);
                    }
                    return read;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int read = super.read(bytes, offset, length);
                    if (read > 0) {
                        drew(read);
                    }
                    return read;
                }

                @Override
                public long skip(long count) throws IOException {
                    long skipped = super.skip(count);
                    if (skipped > 0) {
                        drew(skipped);                    // a skipped byte still spends the decompressor's work
                    }
                    return skipped;
                }
            };
        }

        /** How many bytes every stream this handle opened has handed out in total. */
        public long consumed() {
            return consumed.get();
        }

        /** The most any ONE stream this handle opened handed out - how far into the artifact the inspection
         *  REACHED, which is a different claim from what it cost in total and the one a "did a bound bind?"
         *  question is about. */
        public long widest() {
            return widest.get();
        }

        /** How often the handle was opened. */
        public int opened() {
            return opened.get();
        }
    }

    /** A metered handle over one fixture artifact. The size is measured by streaming the body once, so a fixture
     *  declares no length it could get wrong. */
    public static CountingContent content(InspectorFixture.Artifact artifact) throws IOException {
        long size;
        try (InputStream in = artifact.body().open()) {
            size = in.transferTo(OutputStream.nullOutputStream());
        }
        return new CountingContent(artifact.body(), size);
    }

    private static long drain(QualityInspector.Content content) throws IOException {
        try (InputStream in = content.open()) {
            return in.transferTo(OutputStream.nullOutputStream());
        }
    }

    private static List<InspectorFixture.Artifact> artifacts(InspectorFixture fixture) {
        return List.of(fixture.published(), fixture.proxied(), fixture.malformed(), fixture.nothingToAssess(),
                fixture.oversized());
    }

    private static void equal(InspectorFixture fixture, String what, List<ComplianceGate.Subject> actual,
                              List<ComplianceGate.Subject> expected) {
        if (actual == null) {
            throw failure(fixture, what + " returned null; the absence sentinel is an empty List.");
        }
        if (!actual.equals(expected)) {
            throw failure(fixture, what + " yielded\n    " + actual + "\n  but the contract expects\n    " + expected);
        }
    }

    private static AssertionError failure(InspectorFixture fixture, String message) {
        return new AssertionError(fixture.inspectorClass() + ": " + message);
    }
}
