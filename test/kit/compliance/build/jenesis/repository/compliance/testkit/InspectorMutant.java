package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MalformedArtifactException;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * One deliberately broken substitution for the inspector an {@link InspectorContract.Property} is about - the
 * falsification half of the inspector kit (carrying the mutation mechanism to the one kit in this family that had
 * none).
 *
 * <p><b>Why the kit needs one at all.</b> Every check in {@link InspectorContract} states what an inspector must do;
 * nothing states that the check <em>could have said otherwise</em>. That gap was measured before it was closed: an
 * inspector that claims every artifact and reports no subject about any of them passed a third of the kit. The
 * survivors were counted and printed but not argued, so it was not knowable which of them were legitimate - a check
 * whose whole expectation is "the answer is empty" is satisfied by an inert inspector by construction - and which were
 * checks proven over nothing.
 *
 * <p><b>A mutant preserves the inspector's shape and removes one behaviour.</b> That distinction is load-bearing here
 * in a way it is not in the sibling kits, because two of this SPI's properties are decided by the inspector's
 * <em>shape</em> rather than by its answers: {@link InspectorContract.Property#TIER_HONOURED} reconciles the fixture's
 * declared tier against whether the class really overrides
 * {@link QualityInspector#inspectArtifact(String, QualityInspector.Content, QualityInspector.Lookup)}, and
 * {@link InspectorContract.Property#CONTENT_REOPENS} measures who opens the spooled handle. A substitution that
 * silently changed which of the two spooled legs it overrides would fail both of those for a reason that has nothing
 * to do with the mutation - a red that reads as a bite and is not one. So {@link #substitute} answers a decorator of
 * the <em>same</em> shape as the inspector it wraps: bridged for a {@link InspectorFixture.Tier#PREFIX} inspector,
 * spooled-overriding for a {@link InspectorFixture.Tier#FULL_BODY} one.
 *
 * <p><b>The consequence, stated rather than left to be discovered.</b> For a bridged inspector the spooled leg
 * <em>is</em> the SPI's own default bridge, so no substitution for the inspector can change what that leg does with
 * the handle. {@code CONTENT_REOPENS} and {@code TIER_HONOURED} are therefore properties of the bridge, not of the
 * inspector, for every {@code PREFIX}-tier fixture - which is why the two mutants that would falsify them declare
 * themselves inapplicable there and the census argues those survivors on exactly that ground.
 *
 * <p>{@link #NONE} is the identity: the unmutated inspector every fixture's ordinary leg drives.
 */
public enum InspectorMutant {

    /** Nothing is removed - the deployment's own inspector, which is what the contract's ordinary leg drives. */
    NONE("nothing"),

    /**
     * Everything the inspector reports. It still claims exactly the paths it claimed - {@code handles} is delegated
     * rather than widened, so a bite here is about the inspection and never about the predicate - and it answers the
     * empty sentinel to every leg without opening a body. The general vacuity probe: an inspector whose whole output
     * is what it reports, reporting nothing, is indistinguishable at the wire from a clean artifact.
     */
    AN_INSPECTOR_THAT_REPORTS_NOTHING("everything the inspector reports - it still claims exactly the paths it "
            + "claimed, and answers the empty sentinel to every leg without opening a body"),

    /** The precision of {@code handles}: the inspector now claims every path, the fixture's deliberately unclaimed
     *  one included. A claim that wide gates another format's artifacts and makes every positive claim vacuous. */
    A_WIDER_CLAIM("the precision of handles() - the inspector claims every path, including one served by nothing"),

    /**
     * The positive content of an answer: one subject the artifact does not carry is appended to every inspection. It
     * is the smallest divergence a comparison against a declared expectation can see, and it is the only thing that
     * separates "understood, declares nothing" from "screened a coordinate off a document that declares none" -
     * opposite claims (clause 3 versus clause 7) that a check comparing against an <em>empty</em> expectation cannot
     * tell apart, which is exactly what an inert inspector exploits.
     */
    A_SUBJECT_THE_ARTIFACT_DOES_NOT_CARRY("the positive content of an answer - one subject the artifact does not "
            + "carry is appended to every inspection"),

    /** Repeatability. The same path and the same bytes answer differently on the second inspection, which is the
     *  smallest form of an inspector that carries state between screenings - and the only thing an idempotency
     *  comparison exists to catch. */
    A_DRIFTING_ANSWER("repeatability - the second inspection of the same bytes answers differently from the first"),

    /**
     * The re-open. The spooled handle is opened once, the answer remembered against that handle, and every later
     * inspection served from memory - an inspector holding what it read rather than re-streaming it. Declared only for
     * a {@link InspectorFixture.Tier#FULL_BODY} inspector: a bridged one does not own the spooled leg at all, so there
     * is nothing of its own to memoise.
     */
    A_HANDLE_OPENED_ONCE_AND_REMEMBERED("the re-open - the spooled handle is opened once and every later inspection "
            + "is served from what was remembered"),

    /**
     * The bound. The whole body is drained before the real inspector is asked, so the meter shows an unbounded read
     * over an oversized artifact. Declared only for a {@link InspectorFixture.Tier#FULL_BODY} inspector, because a
     * bridged one's reads are capped by the SPI's bridge and no substitution for the inspector can spend more.
     */
    AN_UNBOUNDED_READ("the bound - the whole body is drained before the inspector is asked, so an oversized artifact "
            + "is read end to end"),

    /**
     * The fail-closed refusal. A {@link MalformedArtifactException} the inspector would have raised is caught and
     * answered as "declares nothing" - the exact confusion clause 7 forbids, and the one that publishes a body that is
     * not the artifact it claims to be.
     */
    A_SWALLOWED_REFUSAL("the fail-closed refusal - a MalformedArtifactException is caught and answered as 'declares "
            + "nothing', which is the one answer it must never be confused with"),

    /**
     * The mirror: a read that should degrade refuses instead. An inspector whose bounded read carries no identity may
     * only under-declare when the bound stops it, so refusing there holds a publish that nothing is wrong with.
     */
    A_REFUSAL_WHERE_A_DEGRADE_IS_OWED("the degrade - a bound-stopped read that should have answered the path-derived "
            + "coordinate refuses the artifact instead"),

    /**
     * The completeness report, and nothing else: the subjects stay exactly what the inspector really answered, and a
     * read a bound stopped is reported as one that ran to completion. It is the smallest possible break of -
     * every other answer is unchanged, so only a check that reads {@link QualityInspector.Inspection#complete()} can
     * see it - and it is the exact lie that lets a screen turn "I could not look" into "there is nothing there".
     * Declared only for a {@link InspectorFixture.Tier#FULL_BODY} inspector: a bridged one's completeness is computed
     * by the SPI's own bridge from the body it was handed, so no substitution for the inspector can misreport it.
     */
    A_BOUND_STOPPED_READ_REPORTED_COMPLETE("the completeness report - a read a bound stopped is reported as one that "
            + "ran to completion, so an empty answer over the part that was never read passes as a clean screen");

    private final String removes;

    InspectorMutant(String removes) {
        this.removes = removes;
    }

    /** What this mutant takes away, for the failure message of a check that survived it. */
    public String removes() {
        return removes;
    }

    /** Whether this mutant can be expressed at all for an inspector of {@code fixture}'s shape - see the class
     *  documentation on the bridged spooled leg. */
    public boolean appliesTo(InspectorFixture fixture) {
        return switch (this) {
            case A_HANDLE_OPENED_ONCE_AND_REMEMBERED, AN_UNBOUNDED_READ, A_BOUND_STOPPED_READ_REPORTED_COMPLETE ->
                    fixture.tier() == InspectorFixture.Tier.FULL_BODY;
            default -> true;
        };
    }

    /**
     * {@code fixture} with its inspector replaced by one of the <em>same shape</em> carrying this mutant's defect.
     *
     * <p>It is a reflective proxy over the fixture rather than a hand-written decorator on purpose:
     * {@link InspectorFixture} has more than twenty members, and a decorator that forwarded them by hand would
     * silently stop forwarding the next one somebody adds - the same "a guard quietly stopped covering something"
     * defect this whole family is about, one level down. The proxy answers <em>one</em> inspector instance for the
     * life of the substitution, because a mutant that remembers something across calls has to be the same object the
     * check asks twice.
     */
    public InspectorFixture decorate(InspectorFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        if (this == NONE) {
            return fixture;
        }
        if (!appliesTo(fixture)) {
            throw new AssertionError(fixture.inspectorClass() + ": " + this + " cannot be expressed for a "
                    + fixture.tier() + "-tier inspector, so decorating with it would produce a red that is about the "
                    + "substitution rather than about the property");
        }
        QualityInspector mutated = substitute(fixture.inspector());
        return (InspectorFixture) java.lang.reflect.Proxy.newProxyInstance(InspectorFixture.class.getClassLoader(),
                new Class<?>[] {InspectorFixture.class},
                (_, method, args) -> {
                    if (method.getName().equals("inspector") && method.getParameterCount() == 0) {
                        return mutated;
                    }
                    try {
                        return method.invoke(fixture, args);
                    } catch (InvocationTargetException raised) {
                        throw raised.getCause();
                    }
                });
    }

    /** {@code real} with this mutant's behaviour removed, in a decorator that overrides the spooled leg exactly when
     *  {@code real} does - see the class documentation on why the shape has to be preserved. */
    QualityInspector substitute(QualityInspector real) {
        Objects.requireNonNull(real, "real");
        return overridesSpooledLeg(real) ? new Spooled(real, this) : new Bridged(real, this);
    }

    /** Whether {@code inspector} owns the fully-spooled leg or inherits the SPI's bounded bridge - the same derivation
     *  {@link InspectorContract} reconciles a fixture's declared tier against. */
    static boolean overridesSpooledLeg(QualityInspector inspector) {
        try {
            return !inspector.getClass()
                    .getMethod("inspectArtifact", String.class, QualityInspector.Content.class,
                            QualityInspector.Lookup.class)
                    .getDeclaringClass().equals(QualityInspector.class);
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("the spooled inspection leg is not resolvable on "
                    + inspector.getClass().getName(), cause);
        }
    }

    /** The bridged shape: it does <em>not</em> override the spooled leg, so the SPI's own bounded bridge caps its
     *  reads exactly as it caps the inspector this stands in for. */
    private static class Bridged implements QualityInspector {

        final QualityInspector real;
        final InspectorMutant mutant;
        private final AtomicInteger inspections = new AtomicInteger();

        private Bridged(QualityInspector real, InspectorMutant mutant) {
            this.real = real;
            this.mutant = mutant;
        }

        @Override
        public boolean handles(String path) {
            return mutant == A_WIDER_CLAIM || real.handles(path);
        }

        @Override
        public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) throws IOException {
            return mutate(path, () -> real.inspect(path, content, lookup));
        }

        @Override
        public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup)
                throws IOException {
            return mutate(path, () -> real.inspectArtifact(path, content, lookup));
        }

        /** One inspection, with the mutant's defect applied to its outcome. */
        final List<ComplianceGate.Subject> mutate(String path, Leg leg) throws IOException {
            List<ComplianceGate.Subject> answered;
            try {
                answered = leg.run();
            } catch (MalformedArtifactException refused) {
                if (mutant == A_SWALLOWED_REFUSAL) {
                    return List.of();
                }
                throw refused;
            }
            return switch (mutant) {
                case AN_INSPECTOR_THAT_REPORTS_NOTHING -> List.of();
                case A_SUBJECT_THE_ARTIFACT_DOES_NOT_CARRY ->
                        Stream.concat(answered.stream(), Stream.of(invented(path))).toList();
                case A_DRIFTING_ANSWER -> inspections.getAndIncrement() % 2 == 0
                        ? answered
                        : Stream.concat(answered.stream(), Stream.of(invented(path))).toList();
                case A_REFUSAL_WHERE_A_DEGRADE_IS_OWED -> throw new MalformedArtifactException(
                        "the mutated inspector refuses " + path + " rather than degrading to its path-derived "
                                + "coordinate");
                default -> answered;
            };
        }

        /** A subject no real inspection of this artifact produces, keyed to the path so it is legible in a diff. */
        private static ComplianceGate.Subject invented(String path) {
            return new ComplianceGate.Subject("Mutant", "mutant:" + path, "0.0.0", List.of());
        }
    }

    /** The spooled shape: it overrides the fully-spooled leg exactly as the inspector it stands in for does, so the
     *  tier reconciliation and the re-open meter are measuring the substitution rather than the SPI's bridge. */
    private static final class Spooled extends Bridged {

        /** The answers already given per {@link QualityInspector.Content} handle - the memory
         *  {@link #A_HANDLE_OPENED_ONCE_AND_REMEMBERED} keeps, and nothing else reads. Identity-keyed because the
         *  claim is about one handle asked twice. */
        private final Map<Content, Inspection> remembered =
                Collections.synchronizedMap(new IdentityHashMap<>());

        private Spooled(QualityInspector real, InspectorMutant mutant) {
            super(real, mutant);
        }

        @Override
        public Inspection inspectArtifact(String path, Content body, Lookup lookup) throws IOException {
            if (mutant == AN_INSPECTOR_THAT_REPORTS_NOTHING) {
                // An inert inspector opens nothing at all - and reports the read it never made as a complete one,
                // which is what makes it indistinguishable at the wire from a clean artifact.
                return Inspection.complete(List.of());
            }
            if (mutant == A_HANDLE_OPENED_ONCE_AND_REMEMBERED) {
                Inspection known = remembered.get(body);
                if (known != null) {
                    return known;                  // served from what was held, without touching the handle again
                }
                Inspection answered = real.inspectArtifact(path, body, lookup);
                remembered.put(body, answered);
                return answered;
            }
            if (mutant == AN_UNBOUNDED_READ) {
                try (InputStream whole = body.open()) {
                    whole.transferTo(OutputStream.nullOutputStream());
                }
            }
            Inspection[] answered = new Inspection[1];
            List<ComplianceGate.Subject> subjects = mutate(path, () -> {
                answered[0] = real.inspectArtifact(path, body, lookup);
                return answered[0].subjects();
            });
            // A_BOUND_STOPPED_READ_REPORTED_COMPLETE removes exactly one thing: the report that the read stopped. The
            // subjects it answers are the real ones, so nothing but the completeness check can see the difference.
            return new Inspection(subjects, mutant == A_BOUND_STOPPED_READ_REPORTED_COMPLETE
                    || answered[0] == null                     // a refusal the mutant swallowed: nothing was read
                    || answered[0].complete());
        }
    }

    /** One inspection leg, so {@link Bridged#mutate} states the defect once for all three. */
    @FunctionalInterface
    private interface Leg {
        List<ComplianceGate.Subject> run() throws IOException;
    }
}
