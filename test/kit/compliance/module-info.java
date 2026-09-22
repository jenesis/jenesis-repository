/**
 * The executable contracts of the compliance SPIs and the fixture SPIs an implementation registers with, as shared,
 * JUnit-free test support.
 *
 * <p>{@code InspectorContract}: sixteen inspectors read sixteen artifact layouts, but they owe the gate the
 * <em>same</em> handful of promises - claim a path and mean it, refuse an artifact you claimed but cannot parse,
 * return the same subjects for the same bytes, stay inside your declared inspection tier, and take the side of the
 * identity-versus-optional criterion you declared - and those promises were being re-asserted, format by format, in
 * sixteen hand-written suites that could each interpret them slightly differently. It states them once and drives them
 * per implementation through an {@code InspectorFixture}.
 *
 * <p>{@code SignalContract}: twelve signal sources over eleven vendors owe the gate the same promises about a feed -
 * decline cleanly when unconfigured, take your declared fail mode on an outage, fail a bound by name rather than
 * serving a plausible partial, spell an ecosystem the way your vendor spells it, and say where a query's data came
 * from. It drives them per vendor through a {@code SignalFixture} and a {@code RecordedFeed}: the vendor's own payload
 * played back from the loopback address, so the source under contract is the one a publish request meets rather than a
 * seam-injected stand-in that never runs the status branch.
 *
 * <p>{@code NoEgressResolver} is the mechanism behind both kits' hermetic-run claim - a JDK
 * {@code InetAddressResolverProvider} base class that refuses and records every non-local name resolution. It lives
 * here rather than in one suite because a JDK service implementation must belong to the module declaring it, so the
 * provider class cannot be shared - but the refusal, the delegation rule and the record can be, and copying them
 * instead is how twelve subtly different {@code Requirement}s came about.
 *
 * <p><strong>Test support, never runtime.</strong> It lives under {@code source/} for one reason only - a JUnit test
 * module is a leaf, so a shared fixture kit cannot live in one - exactly as {@code source/ecosystem-testkit} and the
 * {@code source/store/testkit} does. Nothing here provides a service, so the module is inert on any graph it
 * is dragged onto, and no {@code source/**} runtime module may require it; {@code InspectorTestkitGraphTest} in
 * {@code test/compliance/contract} fails the build on either. The checks throw plain {@link AssertionError}, so the
 * module reads the compliance and store SPIs, the licence marker and the JDK's own server and nothing else - no
 * JUnit, no assertion library - and the {@code @Test} bodies stay under {@code test/**}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.testkit {
    // Test support only; nothing here ships.
    // The subjects a check compares and the SPI a fixture hands over are this module's API, exactly as the free store
    // testkit re-exports the store SPI it drives.
    requires transitive build.jenesis.repository.compliance;
    // The store contract SnapshotSpace implements - the deployment root a signal contract check binds as the space a
    // mirroring feed commits its catalogue and staleness stamp into. Not transitive: it is the kit's own plumbing.
    requires build.jenesis.repository.store;
    // The recorded endpoint RecordedFeed plays a vendor's payload back from - the JDK's own server, so the kit adds no
    // dependency a test module then has to alias and pin. It is never a client: the feeds bring their own.
    requires jdk.httpserver;
    exports build.jenesis.repository.compliance.testkit;
}
