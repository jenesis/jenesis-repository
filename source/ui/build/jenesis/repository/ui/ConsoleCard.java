package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * A card on this console's overview page: how that one page is composed, discovered with {@code ServiceLoader} and
 * bridged into Spring by {@link UiConfig}. A card names the Thymeleaf {@linkplain #fragment fragment} its body is
 * rendered by and prepares the {@linkplain #model(ArtifactStore) value} that fragment renders against, reading the
 * repository through the scoped {@link ArtifactStore}.
 *
 * <p><b>A card contributes a fragment and a value, never markup.</b> Its predecessor returned the body as an HTML
 * {@code String}, which put four templates in Java text blocks and a hand-written escape helper in each, and made
 * "escape every repository-derived value you interpolate" an obligation on the implementor that nothing checked.
 * That is the shape which produces stored cross-site scripting on an admin surface exactly once. Rendering through a
 * template makes the escaping the engine's job, so a namespace named {@code <script>} is text on the page by
 * construction rather than by an implementor having remembered.
 *
 * <p><b>This is not how the GUI is extended.</b> {@link ConsoleModuleProvider} is, and it is the only seam for it:
 * a module contributed through it registers its own screens and menu entries and renders through the shared layout,
 * so it looks and behaves the same in every console this product ships. A card does not travel that way - it needs
 * a host page to be a card on, and only this console has one - so a contribution written as a card would work in
 * one edition and not the other, which is the boundary the extension seam exists to not have. Cards therefore stay
 * what they are: this console's own page, extended deliberately and from close range - which is also why they
 * remain {@code ServiceLoader}-discovered, since a test that proves a failing card is contained has to be able to
 * inject one.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@code UiConfig} discovers the cards once and the {@code ConsoleController} bean holds
 *       the list for the context's life, so one instance serves every console request and {@link #model} may run
 *       concurrently for several viewers. A card is effectively immutable - configuration is taken in the constructor
 *       and frozen - and keeps no per-render state in fields. The value it answers is read by the template and then
 *       discarded, so it must not be shared mutable state either.</li>
 *   <li><b>Idempotency / replay.</b> {@link #model} is called once per {@code GET /console}, not once per boot, and
 *       must be side-effect free: two renders over unchanged state produce the same value, and rendering the console
 *       any number of times changes nothing an operator can observe. {@link #id()}, {@link #title()} and
 *       {@link #fragment()} are stable declarations - {@code id()} is the navigation key and the in-page anchor, so
 *       changing it across releases breaks a bookmarked deep link.</li>
 *   <li><b>Absence sentinel.</b> A card that has nothing to show renders a friendly empty state through its fragment,
 *       never a blank body and never an error page - {@link #id()}, {@link #title()} and {@link #fragment()} all
 *       answer a value, and a {@code null} from any of them is treated as a failure of the card (clause 6) rather
 *       than as an empty card, because a hole in the console that reads as "nothing to report" is the one outcome
 *       this SPI must never produce. {@link #model} may answer {@code null}, and that is not an absence sentinel: it
 *       is how a card whose body needs no value says so. A capability that is not installed contributes no card at
 *       all rather than a card that renders a failure (&sect;3), which is why absence is expressed by the module not
 *       being on the graph.</li>
 *   <li><b>Selection failure.</b> There is nothing to select: the policy is additive, every discovered card is
 *       rendered, and no configuration key names one. Discovery is a plain {@code ServiceLoader.load(ConsoleCard.class)}
 *       in {@code UiConfig} rather than the shared {@code Providers.all} primitive - a card declares no
 *       {@code name()} - so there is no {@code jenreg.<name>=false} toggle and no <em>runtime</em> duplicate-id
 *       refusal: two cards declaring the same {@link #id()} would both render, producing two navigation entries and
 *       two identically-anchored bodies. The refusal is a <b>build-time census</b> instead: it compares the
 *       statically declared providers, the cards the runtime graph discovers and the set {@code UiConfig} actually
 *       renders, and fails the build on a duplicate id. That is deliberate - a packaging mistake must be caught where
 *       it is introduced, and refusing at render time would let one badly-packaged card take the console down, which
 *       is exactly what clause 6 forbids. A census covers the repository it runs in, so a distribution that adds
 *       cards of its own runs its own census over its own source tree; choose an id that names the concept and
 *       prefix it where a collision with another distribution is plausible.</li>
 *   <li><b>Tenant scoping (&sect;6).</b> The {@link ArtifactStore} handed to {@link #model} is already scoped, and a
 *       card reads the repository <em>only</em> through it - it must never resolve a store of its own, because doing
 *       so would escape the scope the console selected. A console view is always a tenant view even where the tenancy
 *       is implicit because there is one tenant, so a card must not prepare another tenant's names.</li>
 *   <li><b>Error visibility (&sect;9).</b> A throw from {@link #model}, {@link #id()}, {@link #title()} or
 *       {@link #fragment()} is <b>contained to this card</b>: {@code ConsoleController} renders through
 *       {@code Contributions}, so a card that throws keeps its navigation entry (marked as failed) and its
 *       {@code #id} anchor, its body is replaced by a visible failure notice naming this class and the exception
 *       <em>type</em>, every other card renders, and the failure is logged once with this class's name. A card that
 *       cannot even declare its {@link #id()} is filed under a class-derived anchor rather than dropped. Containment
 *       is a floor, not a licence: the notice can say only "this card failed", so a card still catches its own read
 *       and parse failures and prepares a degraded value naming <em>what</em> it could not read - the same "disabled
 *       or absent contributes nothing" rule the observation and posture seams follow. Nothing may be silently
 *       swallowed into a body that <em>looks</em> healthy: a degraded card says so, and a failed one is never
 *       rendered as an empty one. An {@link Error} is <em>not</em> contained (a {@link LinkageError} from a
 *       half-installed plugin is a broken module graph, not a card failing to render).</li>
 *   <li><b>Read purity (&sect;10).</b> {@link #model} answers a GET and reads durably stored state only: no external
 *       fetch, no scan, no store write and no refresh on the read path, so the console still stands when a source a
 *       card describes is down. Live data that is not store state is fetched by the <em>browser</em> from the
 *       key-gated JSON API after the fragment is delivered (the logs, consistency and credentials cards are the
 *       pattern - their fragments carry a {@code data-jenesis-card} marker and {@code /js/cards.js} binds them),
 *       never by {@link #model} itself.</li>
 *   <li><b>Staleness.</b> A card over a periodically-refreshed or externally-sourced view shows when that view was
 *       last refreshed, so an empty card is never ambiguous between "clean" and "never collected" - a task status
 *       carries its {@code lastRun}, a report its collection instant. A card that only reads live process state has
 *       no staleness to declare.</li>
 *   <li><b>Lifecycle / ownership.</b> The console owns the lifecycle: instances are {@link java.util.ServiceLoader}-created
 *       from a public no-arg constructor by {@code UiConfig}'s {@code cards} bean and held for the application
 *       context's life; there is no close hook, so a card owns no thread, client or connection. A card that needs
 *       deployment configuration or a collaborator is contributed as a bean instead of being discovered.</li>
 *   <li><b>Ordering / concurrency.</b> Cards are rendered in {@code ServiceLoader} discovery order, which is
 *       <em>not</em> stable across module-path arrangements, with any bean-contributed card appended last; nothing
 *       sorts them. A card must therefore not depend on appearing before or after another, and must not depend on
 *       another card having rendered - each body is self-contained.</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #model} is on the console request path and is given no
 *       cancellation signal, so it must not block and must be bounded by construction: a store-reading card pages and
 *       caps its read ({@link BrowseCard} lists at most a fixed number of top-level namespaces and never opens a
 *       blob) rather than walking the pointer tree, and a bound that is reached is stated in the body rather than
 *       silently truncating the view.</li>
 *   <li><b>Output safety.</b> The value {@link #model} answers is rendered by a Thymeleaf fragment, which escapes
 *       what it interpolates, so a card does not escape anything and must not pre-escape: an escaped string reaching
 *       {@code th:text} renders as {@code &amp;lt;} on the page. The exception is markup a card genuinely produces -
 *       {@link BrowseCard}'s format marks are inline SVG built from module constants and geometry, never from a
 *       repository-derived name - which the fragment renders with {@code th:utext} and which is why that stays the
 *       one unescaped interpolation on the page. A card must never route a repository-, signal- or
 *       configuration-derived string that way: an artifact name is attacker-controlled input, and an unescaped one is
 *       stored cross-site scripting on an admin surface.</li>
 * </ol>
 */
public interface ConsoleCard {

    /** A stable id used in the navigation and as the in-page anchor. */
    String id();

    /** The navigation title. */
    String title();

    /**
     * The Thymeleaf fragment this card's body is rendered by, as {@code "template :: fragment"} - the bundled cards
     * answer {@code "console/cards :: browse"} and its siblings.
     *
     * <p>The fragment takes exactly one parameter, the value {@link #model} answers, and the console passes it
     * positionally. A fragment that needs no value still declares the parameter and ignores it, so that every card
     * is rendered by one expression on the overview page rather than by a branch per card.
     */
    String fragment();

    /**
     * The value {@link #fragment()} renders against, read from the repository through the scoped {@code store}.
     *
     * <p>It is deliberately untyped: the cards on one page are heterogeneous and each fragment knows the shape it
     * declared, so a common supertype would say nothing. Answer a record - the fragment reads it by property name -
     * or {@code null} for a body that needs no value.
     */
    Object model(ArtifactStore store) throws IOException;
}
