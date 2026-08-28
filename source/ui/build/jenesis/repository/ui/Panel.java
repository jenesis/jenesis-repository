package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * A card on this console's overview page: how that one page is composed, discovered with {@code ServiceLoader} and
 * bridged into Spring by {@link UiConfig}. Each panel renders its body against the repository's
 * {@link ArtifactStore}, so a panel stays free of any Spring dependency while still reading real repository data.
 * The rendered body is a trusted HTML fragment the console drops into its Thymeleaf shell, so an implementation
 * escapes any repository-derived text it includes.
 *
 * <p><b>This is not how the GUI is extended.</b> {@link ConsoleModuleProvider} is, and it is the only seam for it:
 * a module contributed through it registers its own screens and menu entries and renders through the shared layout,
 * so it looks and behaves the same in every console this product ships. A panel does not travel that way - it needs
 * a host page to be a card on, and only this console has one - so a contribution written as a panel would work in
 * one edition and not the other, which is the boundary the extension seam exists to not have.
 *
 * <p>The two are also not equally safe to hand to a contributor. A panel returns raw HTML as a {@code String}, so
 * escaping is the implementor's job with only {@link #escape} to help, and it is handed the deployment's whole
 * artifact store to draw a card. A module declares the beans it wants and renders through a template. Panels
 * therefore stay what they are: this console's own page, extended deliberately and from close range - which is also
 * why they remain {@code ServiceLoader}-discovered, since a test that proves a failing panel is contained has to be
 * able to inject one.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@code UiConfig} discovers the panels once and the {@code ConsoleController} bean holds
 *       the list for the context's life, so one instance serves every console request and {@link #render} may run
 *       concurrently for several viewers. A panel is effectively immutable - configuration is taken in the constructor
 *       and frozen ({@code PosturePanel}'s configuration lookup is the worked example) - and keeps no per-render state
 *       in fields.</li>
 *   <li><b>Idempotency / replay.</b> {@link #render} is called once per {@code GET /console}, not once per boot, and
 *       must be side-effect free: two renders over unchanged state produce the same body, and rendering the console any
 *       number of times changes nothing an operator can observe. {@link #id()} and {@link #title()} are stable
 *       declarations - {@code id()} is the navigation key and the in-page anchor, so changing it across releases breaks
 *       a bookmarked deep link.</li>
 *   <li><b>Absence sentinel.</b> A panel that has nothing to show renders a friendly empty state, never {@code null},
 *       never a blank body and never an error page - {@link #id()}, {@link #title()} and {@link #render} all answer a
 *       value, and a {@code null} answer is treated as a failure of the panel (clause 6) rather than as an empty card,
 *       because a hole in the console that reads as "nothing to report" is the one outcome this SPI must never
 *       produce. A capability that is not installed contributes no panel at all rather than a panel that renders a
 *       failure (&sect;3), which is why absence is expressed by the module not being on the graph.</li>
 *   <li><b>Selection failure.</b> There is nothing to select: the policy is additive, every discovered panel is
 *       rendered, and no configuration key names one. Discovery is a plain {@code ServiceLoader.load(Panel.class)} in
 *       {@code UiConfig} rather than the shared {@code Providers.all} primitive - a {@code Panel} declares no
 *       {@code name()} - so there is no {@code jenreg.<name>=false} toggle and no <em>runtime</em>
 *       duplicate-id refusal: two panels declaring the same {@link #id()} would both render, producing two navigation
 *       entries and two identically-anchored bodies. The refusal is a <b>build-time census</b> instead
 *       (the panel contract): it compares the statically declared providers, the panels the runtime graph
 *       discovers and the set {@code UiConfig} actually renders (the bean-contributed {@code PosturePanel} included),
 *       and fails the build on a duplicate id. That is deliberate - a packaging mistake must be caught where it is
 *       introduced, and refusing at render time would let one badly-packaged panel take the console down, which is
 *       exactly what clause 6 forbids. A census covers the repository it runs in, so a distribution that adds panels
 *       of its own runs its own census over its own source tree; choose an id that names the concept and prefix it
 *       where a collision with another distribution is plausible.</li>
 *   <li><b>Tenant scoping (&sect;6).</b> The {@link ArtifactStore} handed to {@link #render} is already scoped, and a
 *       panel reads the repository <em>only</em> through it - it must never resolve a store of its own, because doing so
 *       would escape the scope the console selected. A console view is always a tenant view even where the tenancy is
 *       implicit because there is one tenant, so a panel must not render another tenant's names, and a panel with
 *       deployment-global content scopes it explicitly (as {@code PosturePanel} does through the advisory
 *       {@code Scope}).</li>
 *   <li><b>Error visibility (&sect;9).</b> A throw from {@link #render}, {@link #id()} or {@link #title()} is
 *       <b>contained to this panel</b>: {@code ConsoleController} renders through {@code Contributions}, so a panel
 *       that throws keeps its navigation entry (marked as failed) and its {@code #id} anchor, its body is replaced by
 *       a visible failure notice naming this class and the exception <em>type</em>, every other panel renders, and the
 *       failure is logged once with this class's name. A panel that cannot even declare its {@link #id()} is filed
 *       under a class-derived anchor rather than dropped. Containment is a floor, not a licence: the notice can say
 *       only "this panel failed", so a panel still catches its own read and parse failures and renders a degraded body
 *       naming <em>what</em> it could not read - the same "disabled or absent contributes nothing" rule the
 *       observation and posture seams follow. Nothing may be silently swallowed into a body that <em>looks</em>
 *       healthy: a degraded panel says so, and a failed one is never rendered as an empty one. An {@link Error} is
 *       <em>not</em> contained (a {@link LinkageError} from a half-installed plugin is a broken module graph, not a
 *       panel failing to render).</li>
 *   <li><b>Read purity (&sect;10).</b> {@link #render} answers a GET and renders durably stored state only: no external
 *       fetch, no scan, no store write and no refresh on the read path, so the console still stands when a source a
 *       panel describes is down. Live data that is not store state is fetched by the <em>browser</em> from the
 *       key-gated JSON API after the fragment is delivered ({@code LogPanel} and {@code ConsistencyPanel} are the
 *       pattern), never by {@link #render} itself.</li>
 *   <li><b>Staleness.</b> A panel over a periodically-refreshed or externally-sourced view shows when that view was
 *       last refreshed, so an empty panel is never ambiguous between "clean" and "never collected" - a task status
 *       carries its {@code lastRun}, a report its collection instant. A panel that only reads live process state
 *       ({@code SpiCatalogPanel} over the module graph) has no staleness to declare.</li>
 *   <li><b>Lifecycle / ownership.</b> The console owns the lifecycle: instances are {@link java.util.ServiceLoader}-created
 *       from a public no-arg constructor by {@code UiConfig}'s {@code panels} bean and held for the application
 *       context's life; there is no close hook, so a panel owns no thread, client or connection. A panel that needs
 *       deployment configuration or a collaborator is contributed as a bean instead of being discovered, which is how
 *       {@code PosturePanel} reads the same effective configuration the header badge counts.</li>
 *   <li><b>Ordering / concurrency.</b> Panels are rendered in {@code ServiceLoader} discovery order, which is
 *       <em>not</em> stable across module-path arrangements, with the bean-contributed {@code PosturePanel} appended
 *       last; nothing sorts them. A panel must therefore not depend on appearing before or after another, and must not
 *       depend on another panel having rendered - each body is self-contained.</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #render} is on the console request path and is given no cancellation
 *       signal, so it must not block and must be bounded by construction: a store-reading panel pages and caps its read
 *       ({@code BrowsePanel} lists at most a fixed number of top-level namespaces and never opens a blob) rather than
 *       walking the pointer tree, and a bound that is reached is stated in the body rather than silently truncating the
 *       view.</li>
 *   <li><b>Output safety.</b> The returned fragment is dropped into the Thymeleaf shell <b>unescaped</b>, so a panel
 *       escapes every repository-derived, signal-derived or configuration-derived string it interpolates before it
 *       reaches the body, and escapes API-derived text in JavaScript before it reaches the DOM. An artifact name is
 *       attacker-controlled input; an unescaped one is stored cross-site scripting on an admin surface.</li>
 * </ol>
 */
public interface Panel {

    /** A stable id used in the navigation and as the in-page anchor. */
    String id();

    /** The navigation title. */
    String title();

    /** Render the panel body as an HTML fragment, reading the repository through the scoped {@code store}. */
    String render(ArtifactStore store) throws IOException;

    /**
     * Escape {@code value} for interpolation into a panel fragment.
     *
     * <p>Clause 12 says a fragment is dropped into the shell <em>unescaped</em>, so every repository-, signal- and
     * configuration-derived string a panel interpolates has to be escaped by the panel. That obligation had five
     * byte-identical private implementations behind it - four panels here and one downstream report - which is the
     * shape that drifts, and it guarded the one surface whose contract clause is called "Output safety". It lives
     * here, on the interface that creates the obligation, because that is where the next panel author looks.
     *
     * <p>Five entities, and the ampersand first so an escaped entity is not escaped again. {@code null} answers
     * empty rather than the string "null": a panel interpolating an absent value wants nothing, and the downstream
     * copy already had that guard while the four here did not.
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
