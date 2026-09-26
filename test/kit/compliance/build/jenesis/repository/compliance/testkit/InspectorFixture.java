package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * How one {@link QualityInspector} registers with the shared {@link InspectorContract} suite: a fixture hands the kit
 * artifacts of its own format - a well-formed one per leg, one it claims but cannot parse, one that carries nothing
 * to assess, and one whose declaration sits past the prefix inspection tier - names the provider class it stands for,
 * and <em>declares the two things the contract cannot infer</em>: which inspection tier the inspector reads at, and
 * which side of the identity-versus-optional criterion its bounded read is on. The kit then drives every contract
 * property against it.
 * An inspector is covered by writing a fixture, never by copying assertions - which is exactly how sixteen
 * hand-written inspector suites came to assert sixteen slightly different things about one contract.
 *
 * <h2>The two declarations that carry the fixture's honesty</h2>
 * Both are machine-checked rather than trusted, because a declaration nothing verifies is a comment:
 * <ul>
 *   <li>{@link #tier()} is checked against a <em>counting</em> {@link QualityInspector.Content} over an oversized
 *       body - not against the source - so a {@link Tier#PREFIX} inspector that quietly grew a whole-body read fails
 *       here rather than being caught by eye at some later audit; and it is cross-checked against whether the
 *       inspector actually overrides the spooled leg, so the declaration cannot drift from the code either.</li>
 *   <li>{@link #reading()} is checked by driving the oversized artifact through the parsing leg and asserting the
 *       disposition that side of the criterion owes: an {@link Reading#IDENTITY_IN_ARTIFACT} inspector must fail
 *       closed on a bound-stopped read, an {@link Reading#OPTIONAL_BESIDE_COORDINATE} one must degrade to its
 *       path-derived coordinate, a {@link Reading#CONTENT_FINDINGS} one must never fail a publish at all, and an
 *       {@link Reading#IDENTITY_IN_PATH} one must answer exactly what it answers over an empty body, because it never
 *       opened the body at all. That assertion is the point of the kit: the criterion was derived once and is
 *       now impossible for the next inspector to re-derive differently by accident.</li>
 * </ul>
 *
 * <p>A fixture owns whatever it builds. {@link #start()} runs once per suite, {@link #close()} once after it, and the
 * artifacts are valid only in between. Constructing a fixture must allocate nothing expensive: the census instantiates
 * every fixture purely to read {@link #inspectorClass()}.
 */
public interface InspectorFixture extends AutoCloseable {

    /**
     * How much of an artifact this inspector reads - the tier its bounded reads are budgeted against.
     */
    enum Tier {

        /**
         * The inspector reads its declaration out of the {@link QualityInspector#PREFIX_INSPECTION_LIMIT} front
         * prefix and never past it: it does not override the spooled leg, so the SPI's default bridge caps every
         * read for it. The overwhelming majority of inspectors, because a format's manifest sits at the front of the
         * artifact by the format's own nature.
         */
        PREFIX,

        /**
         * The inspector overrides {@link QualityInspector#inspectArtifact(String, QualityInspector.Content,
         * QualityInspector.Lookup)} and streams past the prefix tier - the content scanners, whose finding may sit
         * anywhere in the body. Full-body is <em>not</em> unbounded: such an inspector owes its own ceiling, which it
         * declares here through {@link #readCeiling()} and which the kit holds it to.
         */
        FULL_BODY
    }

    /**
     * What the inspector's bounded read carries, which is what decides whether reaching a bound may degrade or must
     * fail closed. The criterion is the read's ROLE, never the format - see {@code BoundedArchive}'s class
     * documentation and {@link QualityInspector}'s error-visibility clause.
     */
    enum Reading {

        /**
         * The artifact's identity - a coordinate that exists nowhere but inside the body (a NuGet {@code .nuspec}, a
         * RubyGems gemspec), so the request path yields no fallback. A body that cannot be parsed and a read the bound
         * stopped are <b>both</b> refused, because degrading either would publish or serve a package that nothing
         * screened at all.
         */
        IDENTITY_IN_ARTIFACT,

        /**
         * An optional declaration - a licence beside a coordinate the request path already yields (Composer,
         * CocoaPods, Conda, Maven). A read the bound stopped <b>degrades</b> to "declares nothing", because losing a
         * licence can only under-declare and never hide a coordinate; a body that is not the artifact it claims to be
         * is still refused, because that is a could-not-parse of the claimed artifact rather than a short read.
         */
        OPTIONAL_BESIDE_COORDINATE,

        /**
         * No coordinate at all: the body is scanned for findings stamped onto a content-scan subject (embedded
         * secrets, an inbound attestation). Neither an unreadable body nor a reached ceiling fails the publish - a
         * missed finding can only under-declare, and refusing every artifact a scanner could not fully read would
         * hold the whole repository closed. The kit asserts exactly that, so the fail-open is a declared, reviewed
         * position rather than an accident.
         */
        CONTENT_FINDINGS,

        /**
         * <b>Nothing at all - the bounded read does not happen.</b> The whole subject is derived from the request path
         * (Conan's {@code name}/{@code version} revision route, a Go module path and version, a Hugging Face
         * {@code repo_id} and revision): the client addresses the artifact by its coordinate, so the body carries no
         * fact the inspector needs and is never opened.
         *
         * <p>That makes this the one reading for which <em>no bound can bind</em>, and the kit states the consequences
         * rather than leaving them to be inferred:
         * <ul>
         *   <li>such an inspector can never raise {@link build.jenesis.repository.compliance.MalformedArtifactException}
         *       - there is no parse to fail - so the malformed leg asserts the <em>declared</em> subjects instead of a
         *       refusal, and would fail on an inspector that started refusing bodies it does not read;</li>
         *   <li>a bound-stopped read neither refuses nor degrades, because the coordinate was never in the bytes: the
         *       oversized artifact must answer exactly what a well-formed one at that path answers. The kit proves that
         *       positively, by re-driving the same path over an <em>empty</em> body and requiring the same subjects -
         *       so this value cannot be used to opt out of the other two legs, only to state a stronger property than
         *       either of them.</li>
         * </ul>
         * Declaring this side is therefore a claim the kit can falsify: an inspector that reads its coordinate out of
         * the body, or that refuses a body it could not parse, fails here and belongs on one of the readings above.
         */
        IDENTITY_IN_PATH
    }

    /** Which of the two inspection legs a fixture's parsing/bounded read happens on. */
    enum Leg {

        /** {@link QualityInspector#inspect(String, byte[], QualityInspector.Lookup)} - the publish leg, where a
         *  format inspector cracks the pushed artifact for its manifest. */
        PUBLISH,

        /** {@link QualityInspector#inspectArtifact(String, QualityInspector.Content, QualityInspector.Lookup)} - the
         *  fully-spooled leg the hardened proxy screens through, where a full-body inspector reads the whole body. */
        SPOOLED
    }

    /** An artifact's bytes, opened afresh on every read. The kit never holds a body as a {@code byte[]}: it streams it
     *  through the same bounded bridge the screens use, so an oversized fixture artifact costs nothing but the
     *  inspector's own declared budget. */
    @FunctionalInterface
    interface Body {

        /** A fresh stream over the whole body from its first byte; the caller closes it. */
        InputStream open() throws IOException;
    }

    /**
     * One artifact a fixture supplies: the request path it is published or fetched at, its bytes, and the subjects the
     * inspector must answer with when it is screened. The expectation is part of the artifact rather than a separate
     * method so a fixture cannot grow an artifact nobody states an outcome for.
     */
    record Artifact(String path, Body body, List<ComplianceGate.Subject> subjects) {

        public Artifact {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(body, "body");
            subjects = List.copyOf(subjects);
        }

        /** An artifact whose screening must yield no subject at all - a declares-nothing index document, or a
         *  content scan that found nothing. */
        public Artifact(String path, Body body) {
            this(path, body, List.of());
        }
    }

    /** The fully qualified {@link QualityInspector} implementation class this fixture covers, as the census parses it
     *  out of the inspector module's {@code provides ... with ...} clause. */
    String inspectorClass();

    /**
     * The inspector under test: the instance {@code ServiceLoader} constructed, looked up by the class this fixture
     * claims. A fixture does not - and cannot - instantiate it: an inspector module {@code provides} its
     * implementation and {@code exports} nothing, so the only handle onto it is the discovered one, which is also the
     * only instance a publish or proxy request ever reaches. The lookup therefore doubles as a check that the
     * fixture's module really roots the inspector's module.
     */
    default QualityInspector inspector() {
        return QualityInspector.all().stream()
                .filter(inspector -> inspector.getClass().getName().equals(inspectorClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(inspectorClass() + " is not discoverable in this module graph; "
                        + "the fixture's test module must require the module that provides it."));
    }

    /** The inspection tier this inspector reads at. Proven, not trusted: see {@link Tier}. */
    Tier tier();

    /** What this inspector's bounded read carries, and therefore what reaching a bound must do. Proven, not trusted:
     *  see {@link Reading}. */
    Reading reading();

    /** The leg the parsing and bounded reads happen on - the leg the malformed and bound-stopped checks drive.
     *  {@link Leg#PUBLISH} for a format inspector that cracks the pushed artifact; {@link Leg#SPOOLED} for a
     *  full-body content scanner. */
    default Leg parsingLeg() {
        return Leg.PUBLISH;
    }

    /**
     * The most bytes this inspector may consume from a fully-spooled body - the ceiling the counting
     * {@link QualityInspector.Content} holds it to. A {@link Tier#PREFIX} inspector may not raise it: its reads are
     * capped by the SPI's own bridge, so the kit rejects any value but
     * {@link QualityInspector#PREFIX_INSPECTION_LIMIT}. A {@link Tier#FULL_BODY} inspector declares what its own
     * engine really stops at (the shared {@link QualityInspector#FULL_BODY_INSPECTION_LIMIT full-body tier}, plus
     * whatever read-ahead its decoder spends past the last counted byte), because full-body does not mean unbounded.
     *
     * <p>It may not declare a ceiling at or below the prefix tier, and the kit refuses one: the spooled leg exists to
     * read PAST the bounded prefix, so a lower ceiling would buy bounded heap and no reach - and it would quietly
     * falsify the screens, which decide whether the inspectors saw the whole artifact by comparing the body's length
     * against the prefix tier. That is not hypothetical: it is what this fixture recorded before, when the secret
     * scanner's private 16 MiB budget was half the prefix tier and a credential past it was missed on both legs.
     */
    default long readCeiling() {
        return QualityInspector.PREFIX_INSPECTION_LIMIT;
    }

    /** Build whatever the fixture owns (an oversized artifact on a temporary file). Called once, before any check
     *  runs; a failure here is a test failure, never a skip. */
    default void start() throws Exception {
    }

    /** A well-formed artifact of this format at a publish path, and the subjects the publish leg must answer with. */
    Artifact published();

    /** A well-formed artifact at a download path, and the subjects the fully-spooled leg must answer with. Defaults to
     *  {@link #published()} for an inspector that screens both legs from the same path; a format whose proxy leg
     *  reads a different route (an immutable download path rather than the publish route) overrides it. */
    default Artifact proxied() {
        return published();
    }

    /**
     * An artifact this inspector <em>claims</em> but cannot parse - a body that is not the archive it says it is. What
     * that must produce is decided by {@link #reading()} and {@link #malformedIsRefused()}: a refusal for the two
     * format readings, and the declared subjects for {@link Reading#CONTENT_FINDINGS} (which must never fail a publish
     * over bytes it could not interpret) and for {@link Reading#IDENTITY_IN_PATH} (which never looked at the bytes).
     */
    Artifact malformed();

    /** An artifact this inspector claims that carries nothing to assess - a generated index document, a checksum
     *  sibling, a clean body. Its screening must yield an empty list, which is a positive claim ("understood, declares
     *  nothing") and never {@code null}. */
    Artifact nothingToAssess();

    /**
     * Whether the publish leg screens only the bodies pushed to this format's <em>own</em> publish route, answering
     * the empty sentinel for anything else it claims. Almost every inspector does: npm, NuGet, Composer, PyPI and the
     * rest all re-derive the publish route from the path and return nothing for a path that is not one, so a document
     * that merely lives under their prefix is understood-and-declares-nothing rather than a failed parse.
     *
     * <p>A fixture returning {@code false} declares that its inspector parses <em>any</em> claimed body on the publish
     * leg, so {@link #nothingToAssess()} is asserted on the fully-spooled leg only. That is a real cross-format
     * divergence (&sect;13), not a fixture convenience: it is declared here so it shows up in a
     * fixture review and in the census rather than being quietly dropped from the suite.
     */
    default boolean publishLegIsRouteGated() {
        return true;
    }

    /**
     * Whether a body this inspector <em>claims</em> but cannot parse is refused. Every format inspector does, which is
     * why this is the default and why {@link #malformed()} exists: "this is not the archive it says it is" is a fact
     * about the artifact worth holding a publish on, whatever the inspector was reading it for. It is consulted only
     * for the two format readings - {@link Reading#CONTENT_FINDINGS} and {@link Reading#IDENTITY_IN_PATH} already
     * settle the question, and a fixture cannot flip it for them.
     *
     * <p>A fixture returning {@code false} declares that its inspector <em>degrades</em> an unparseable claimed body to
     * its path-derived coordinate, and the kit then asserts that degradation instead - it does not skip the leg. That
     * is a real cross-format divergence (&sect;13) between inspectors on the same side of the
     * identity-versus-optional criterion: Composer, CocoaPods and Conda crack an archive for an optional licence and
     * still refuse a body that is not that archive, so an inspector that admits one is diverging from its own peers,
     * not expressing a different criterion. Declared here, and listed with its reason in the census, so it shows up in
     * a fixture review rather than being quietly dropped from the suite.
     */
    default boolean malformedIsRefused() {
        return true;
    }

    /**
     * An artifact whose declaration sits <em>past</em> this inspector's tier: bigger than
     * {@link QualityInspector#PREFIX_INSPECTION_LIMIT}, with the manifest behind a payload the bound cuts off. It
     * carries two properties at once - the counting {@link QualityInspector.Content} proves the tier really binds on
     * it, and driving it through {@link #parsingLeg()} proves the {@link #reading()} disposition. Its
     * {@link Artifact#subjects()} are what the degrading readings must still answer with; for
     * {@link Reading#IDENTITY_IN_ARTIFACT} the kit expects a refusal instead and the field is unused.
     *
     * <p>An {@link Reading#IDENTITY_IN_PATH} inspector reads no body, so nothing here can be "past its tier" in the
     * archive sense - what the oversized artifact proves for it is that the coordinate really is the path: the bridge
     * still meters a bounded read, and the subjects must equal the ones the same path yields over an empty body.
     */
    Artifact oversized();

    /** A request path no artifact of this inspector's format is ever served at, so {@link QualityInspector#handles}
     *  must refuse it. The default is deliberately shapeless; a fixture overrides it only if its inspector legitimately
     *  claims that shape. */
    default String unclaimed() {
        return "/kit/inspector-contract/claimed-by-nothing.unknown";
    }

    /** The already-published siblings this inspector may read while screening (a jar's sibling POM). {@link
     *  QualityInspector.Lookup#none()} by default: an inspector that reads no sibling must not need one, and the SPI's
     *  own no-siblings lookup states that positively on both legs rather than each fixture re-deriving it. A fixture
     *  whose inspector DOES read a companion overrides this with a lookup that really bounds its bounded leg - the SPI
     *  no longer defaults that leg, so there is nothing to inherit. */
    default QualityInspector.Lookup lookup() {
        return QualityInspector.Lookup.none();
    }

    @Override
    default void close() throws Exception {
    }
}
