package build.jenesis.repository.definitions;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.settings.PrivateHostGuard;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Globs;

/**
 * A repository's shape: whether it accepts uploads into its own store ({@code writable}) and an ordered list of
 * {@link Fallback}s consulted, first hit wins, for what it does not hold. One clause grammar spells it -
 * {@code writable} and {@code fallback <source> [options]} - and {@link #parse} accepts nothing else: an upload-only
 * repository is {@code writable}, a caching proxy {@code fallback <url>}, a group {@code fallback a fallback b}, and
 * the same grammar adds per-fallback caching and screening strength.
 *
 * <p>{@code RepositoryRouter#resolve} walks this record, and a write lands in a repository's own store iff it is
 * {@code writable}; no definition delegates its writes. {@link #harden()} is the one derived view, read by the console
 * badge and the re-screen sweeps.
 *
 * <p>It is a module apart from the router so a screen that only renders a definition does not require the router and
 * everything behind it. The model needs only the outbound-target rule ({@code blobs}), the cleartext rule
 * ({@code settings}) and the artifact descriptor a {@code match=} predicate reads ({@code store}).
 */
public record RepositoryDefinition(boolean writable, List<Fallback> fallbacks) {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryDefinition.class);

    /**
     * One ordered fallback: an external upstream URL or another repository, with this repository's per-fallback copy
     * ({@code store}) and {@code screening} for content fetched from it. Both are meaningful only on a
     * {@link Source.Upstream} fallback - a {@link Source.Repository} is a view whose inner repository owns its bytes
     * and policy - so {@code nocache}/{@code harden}/{@code unscreened} on one is refused at parse.
     *
     * <p>Two orthogonal axes: an optional {@link Match} predicate ({@code match=}, so the fallback applies only to
     * requests whose {@code ecosystem:coordinate} matches) and a {@link Serve} policy ({@code redirect}, a {@code 307}
     * to the upstream instead of fetching it). The defaults are no predicate and {@link Serve#PROXY}.
     */
    public record Fallback(Source source, boolean store, Screening screening, Match match, Serve serve) {
        public Fallback {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(screening, "screening");
            Objects.requireNonNull(serve, "serve");
        }

        /** A fallback with no predicate and {@link Serve#PROXY} - a {@code fallback <source>} clause without
         *  {@code match=} or {@code redirect}. */
        public Fallback(Source source, boolean store, Screening screening) {
            this(source, store, screening, null, Serve.PROXY);
        }

        /** Whether this fallback's predicate admits {@code descriptor}; {@code true} when there is none. */
        public boolean matches(ArtifactDescriptor descriptor) {
            return match == null || match.matches(descriptor);
        }

        Fallback withStore(boolean store) {
            return new Fallback(source, store, screening, match, serve);
        }

        Fallback withScreening(Screening screening) {
            return new Fallback(source, store, screening, match, serve);
        }

        Fallback withMatch(Match match) {
            return new Fallback(source, store, screening, match, serve);
        }

        Fallback withServe(Serve serve) {
            return new Fallback(source, store, screening, match, serve);
        }
    }

    /** How a matched {@link Source.Upstream} fallback is served: {@link #PROXY} through the pull-through walk (the
     *  default); {@link #REDIRECT} through the injected redirect handler ({@code redirect-directory} module), a
     *  {@code 307} to the upstream - screened by default, a bookmark redirect when the fallback is also
     *  {@link Screening#UNSCREENED}. */
    public enum Serve { PROXY, REDIRECT }

    /** A coordinate predicate on a fallback ({@code match=<ecosystem>:<glob>}): the fallback applies only to a request
     *  whose {@link ArtifactDescriptor} carries a coordinate in {@code ecosystem} (case-insensitively, so {@code maven}
     *  matches {@code Maven}) that the {@code glob} matches. A descriptor with no coordinate (a checksum, generated
     *  metadata) is never matched, so it follows the walk's configured order rather than being partitioned away from
     *  its artifact. The glob is anchored and literal except {@code *} (any run). */
    public record Match(String ecosystem, String glob) {
        public Match {
            Objects.requireNonNull(ecosystem, "ecosystem");
            Objects.requireNonNull(glob, "glob");
        }

        /** Whether this predicate routes {@code descriptor}: a coordinate in a matching ecosystem that the glob
         *  matches. */
        public boolean matches(ArtifactDescriptor descriptor) {
            return descriptor != null && descriptor.coordinate() != null
                    && ecosystem.equalsIgnoreCase(descriptor.ecosystem())
                    && Globs.compile(glob).matcher(descriptor.coordinate()).matches();
        }

        /** Parse a {@code match=} argument ({@code <ecosystem>:<glob>}), refusing a malformed one at parse. */
        static Match parse(String argument, String specification) {
            int colon = argument.indexOf(':');
            if (colon <= 0 || colon == argument.length() - 1) {
                throw new IllegalArgumentException("A 'match=' predicate must be '<ecosystem>:<glob>' (e.g. "
                        + "'match=maven:com.foo.*'): " + specification);
            }
            return new Match(argument.substring(0, colon), argument.substring(colon + 1));
        }
    }

    /** Where a fallback's content comes from: an external upstream, another repository resolved by recursion, or the
     *  location the coordinate's own domain names in its discovery file. */
    public sealed interface Source {
        /** An external upstream URL (the source token contains a scheme, {@code "://"}). */
        record Upstream(URI url) implements Source {
            public Upstream {
                Objects.requireNonNull(url, "url");
            }
        }

        /** Another repository, by name, resolved by recursion into its own walk. */
        record Repository(String name) implements Source {
            public Repository {
                Objects.requireNonNull(name, "name");
            }
        }

        /** Discovered: each request's upstream is where the domain its coordinate's name reverses into says, in its
         *  {@code /.well-known/java-repository.properties}. Written {@code fallback discovered}, the keyword taking
         *  precedence over a repository of that name; fetched, screened and kept as an {@link Upstream} leg is, or
         *  {@code redirect}ed - the one way it serves a format with no proxy leg. */
        record Discovered() implements Source {
        }
    }

    /** Per-fallback screening strength: {@code DEFAULT} is the serving tenant's gate (a prefix screen, none if
     *  ungated); {@code HARDEN} a full-body, fail-closed screen before anything serves; {@code UNSCREENED} an explicit,
     *  loudly warned opt-out. */
    public enum Screening { DEFAULT, HARDEN, UNSCREENED }

    /** Copy the fallbacks unmodifiably and refuse the one shape that could serve nothing - not writable with no
     *  fallbacks. Every parse yields a serveable shape, so this guards direct construction. */
    public RepositoryDefinition {
        fallbacks = List.copyOf(fallbacks);
        if (!writable && fallbacks.isEmpty()) {
            throw new IllegalArgumentException("A repository definition that is not 'writable' and has no "
                    + "fallbacks can never serve anything: declare it 'writable' (it then accepts uploads into "
                    + "its own store), or give it at least one 'fallback <source>'.");
        }
    }

    /** Parse a definition in the clause grammar {@code ( "writable" | "fallback" <source> <option>* )*}, an option
     *  being {@code nocache}, {@code harden}, {@code unscreened}, {@code redirect} or {@code match=<ecosystem>:<glob>}.
     *  An option binds to the nearest preceding {@code fallback}; {@code writable} appears at most once, anywhere. <b>A
     *  fallback caches by default</b>; {@code nocache} opts out to pass-through. A definition not starting with a
     *  clause, an option with no fallback, an unknown option, or a store or screening option on a repository fallback
     *  is refused. {@code hosted}, {@code proxy} and {@code group} are refused like any leading token, the message
     *  naming the clause that says the same. */
    public static RepositoryDefinition parse(String specification) {
        String[] parts = specification.trim().split("\\s+");
        return switch (parts[0]) {
            case "writable", "fallback" -> parseClauses(parts, specification);
            default -> throw new IllegalArgumentException("A repository definition is written in clauses - "
                    + "'writable' and/or 'fallback <source> [options]' - and cannot start with '" + parts[0] + "'"
                    + instead(parts) + ": " + specification);
        };
    }

    /** The clause that says what a repository-kind word ({@code hosted}, {@code proxy}, {@code group}) meant, or
     *  nothing for any other token. */
    private static String instead(String[] parts) {
        String rest = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)).trim();
        return switch (parts[0]) {
            case "hosted" -> "; write 'writable'";
            case "proxy" -> "; write 'fallback " + (rest.isEmpty() ? "<url>" : rest) + "'";
            case "group" -> {
                String clauses = Arrays.stream(rest.split("[,\\s]+"))
                        .filter(member -> !member.isBlank() && !member.contains("="))
                        .map(member -> "fallback " + member)
                        .collect(Collectors.joining(" "));
                yield "; write one 'fallback <repository>' clause per member, in order: '"
                        + (clauses.isEmpty() ? "fallback a fallback b" : clauses) + "'";
            }
            default -> "";
        };
    }

    /** Parse the clause grammar; see {@link #parse}. */
    private static RepositoryDefinition parseClauses(String[] parts, String specification) {
        boolean writable = false;
        boolean writableSeen = false;
        List<Fallback> fallbacks = new ArrayList<>();
        int current = -1;   // index of the fallback options bind to, or -1 before the first `fallback`
        for (int index = 0; index < parts.length; index++) {
            String token = parts[index];
            if (token.isBlank()) {
                continue;
            }
            switch (token) {
                case "writable" -> {
                    if (writableSeen) {
                        throw new IllegalArgumentException("'writable' may appear at most once: " + specification);
                    }
                    writableSeen = true;
                    writable = true;
                }
                case "fallback" -> {
                    if (index + 1 >= parts.length || parts[index + 1].isBlank()) {
                        throw new IllegalArgumentException("A 'fallback' clause needs a source (an upstream URL "
                                + "or a repository name): " + specification);
                    }
                    String sourceToken = parts[++index];
                    Source source = parseSource(sourceToken, specification);
                    if (source instanceof Source.Upstream upstream && plaintextUpstream(upstream.url())) {
                        warnPlaintext(upstream.url());
                    }
                    // Caching applies to a fetching fallback; a Repository target owns its own bytes, so false there.
                    boolean store = fetching(source);
                    fallbacks.add(new Fallback(source, store, Screening.DEFAULT));
                    current = fallbacks.size() - 1;
                }
                default -> {
                    // Every other token is an option binding to the nearest preceding fallback; one with none is
                    // refused.
                    if (current < 0) {
                        throw new IllegalArgumentException("Option '" + token + "' must follow a 'fallback' "
                                + "clause: " + specification);
                    }
                    fallbacks.set(current, applyOption(token, fallbacks.get(current), specification));
                }
            }
        }
        if (mixedStrength(fallbacks)) {
            // Warned, not refused: a weaker fallback ordered before a `harden` one can serve before the strong screen
            // runs.
            LOGGER.warn("Mixed-strength fallback list in '" + specification + "': a "
                    + "'harden' upstream sits beside a non-hardened (DEFAULT/UNSCREENED) upstream, so a weaker "
                    + "fallback ordered before a hardened one can serve first-hit before the strong screen runs"
                    + ". This is allowed but flagged; reorder so the strongest screen leads, or 'harden' "
                    + "the weaker fallback too.");
        }
        return new RepositoryDefinition(writable, fallbacks);
    }

    /** Apply one option to the nearest preceding fallback. The store/screening tokens and {@code redirect} are refused
     *  on a {@link Source.Repository} fallback; {@code match=} is a walk filter allowed on any source. {@code redirect}
     *  without the {@code redirect-directory} module installed is refused at parse, naming the module, rather than
     *  silently proxying. */
    private static Fallback applyOption(String token, Fallback fallback, String specification) {
        if (token.startsWith("match=")) {
            // A coordinate predicate filters the walk, so it is meaningful on any source.
            return fallback.withMatch(Match.parse(token.substring("match=".length()), specification));
        }
        if (token.equals("redirect")) {
            if (fallback.source() instanceof Source.Repository repository) {
                throw new IllegalArgumentException("Option 'redirect' is not allowed on the repository-name "
                        + "fallback '" + repository.name() + "': a 'redirect' serve policy emits a 307 to an "
                        + "upstream URL, which a repository-name view does not have. " + specification);
            }
            if (!redirectHandlerInstalled) {
                // Without the redirect-directory module a redirect clause is refused at every write site rather than
                // degrading to fetching the very bytes the operator asked to redirect.
                throw new IllegalArgumentException("A 'fallback <url> redirect' clause needs the "
                        + "'redirect-directory' module installed to emit the 307, but it is not present on this "
                        + "deployment; the 'redirect' serve policy is refused rather than silently proxied. Install "
                        + "the 'redirect-directory' module, or drop 'redirect' from the definition: " + specification);
            }
            return fallback.withServe(Serve.REDIRECT);
        }
        // The inner repository owns its own store and screening policy.
        if (fallback.source() instanceof Source.Repository repository) {
            throw new IllegalArgumentException("Option '" + token + "' is not allowed on the "
                    + "repository-name fallback '" + repository.name() + "': the fallback repository "
                    + "owns its own store and screening policy. " + specification);
        }
        return switch (token) {
            case "nocache" -> fallback.withStore(false);
            case "harden" -> fallback.withScreening(Screening.HARDEN);
            case "unscreened" -> {
                // An explicit no-screen opt-out is never silent - a bookmark redirect on a redirect fallback, a
                // no-screen proxy otherwise.
                LOGGER.warn("SECURITY: fallback '"
                        + (fallback.source() instanceof Source.Upstream upstream ? upstream.url() : "discovered")
                        + "' is declared 'unscreened' - its "
                        + "fetched artifacts are served with NO compliance screening. Remove 'unscreened' "
                        + "or use 'harden' to full-body screen; served unscreened as configured: "
                        + specification);
                yield fallback.withScreening(Screening.UNSCREENED);
            }
            default -> throw new IllegalArgumentException("Unknown definition token '" + token + "' (expected "
                    + "'writable', 'fallback <source>', or an option 'nocache'/'harden'/'unscreened'/'redirect'/"
                    + "'match=<ecosystem>:<glob>'): " + specification);
        };
    }

    /** Whether an ordered fallback list mixes screening strength - a {@code HARDEN} upstream beside a weaker one - the
     *  hazard warned of (not refused) at parse. Only a {@link #fetching} fallback carries a strength statically; a
     *  repository member's is its own resolved definition, which the router evaluates. */
    public static boolean mixedStrength(List<Fallback> fallbacks) {
        boolean hardened = fallbacks.stream().anyMatch(fallback ->
                fetching(fallback.source()) && fallback.screening() == Screening.HARDEN);
        boolean weaker = fallbacks.stream().anyMatch(fallback ->
                fetching(fallback.source()) && fallback.screening() != Screening.HARDEN);
        return hardened && weaker;
    }

    /** Whether this repository has any hardened upstream leg: a {@link #fetching} fallback with
     *  {@code screening == HARDEN}, whatever else the definition holds. Such a repository's cached upstream bytes must
     *  be re-screened per hit and are never redirect-safe, so keying this on anything narrower would let a definition
     *  skip both and serve retroactively refused bytes. Repository members are hardened recursively by the router. */
    public boolean harden() {
        return fallbacks.stream().anyMatch(fallback ->
                fetching(fallback.source()) && fallback.screening() == Screening.HARDEN);
    }

    /** Whether a fallback of {@code source} fetches from outside - an {@link Source.Upstream} or a
     *  {@link Source.Discovered} one - and so carries its own store and screening policy. */
    public static boolean fetching(Source source) {
        return source instanceof Source.Upstream || source instanceof Source.Discovered;
    }

    /** Resolve a {@code fallback} source token: a {@code "://"}-bearing token is a {@link Source.Upstream}, the keyword
     *  {@code discovered} a {@link Source.Discovered}, anything else a {@link Source.Repository}. */
    private static Source parseSource(String token, String specification) {
        if (token.contains("://")) {
            return new Source.Upstream(upstreamUri(token, specification));
        }
        if (token.equals("discovered")) {
            return new Source.Discovered();
        }
        return new Source.Repository(token);
    }

    /** Resolve a source token to an upstream {@link URI}, refusing a malformed one. */
    private static URI upstreamUri(String token, String specification) {
        try {
            return URI.create(token);
        } catch (IllegalArgumentException cause) {
            throw new IllegalArgumentException(
                    "Invalid upstream URL '" + token + "': " + specification, cause);
        }
    }

    /** The proxy outbound dial, by its declared constant: it is {@link ProxyLeg#ALLOW_INTERNAL}, the same dial the
     *  proxy legs read for upstream-advertised URLs. */
    public static final String ALLOW_INTERNAL_SETTING = ProxyLeg.ALLOW_INTERNAL;

    /** Warn that an upstream travels in cleartext. Reachable only once the deployment has taken the
     *  {@code proxy-allow-internal} opt-out - the write surfaces and the boot sweep refuse a plaintext upstream
     *  otherwise - so this is the standing reminder of an accepted risk. */
    private static void warnPlaintext(URI upstream) {
        LOGGER.warn(
                "SECURITY: upstream '" + upstream + "' is not https - artifacts and any per-host upstream "
                        + "credential sent to it travel in cleartext. Use an https upstream; this plaintext "
                        + "upstream is served as configured because " + ProxyLeg.ALLOW_INTERNAL + " is set.");
    }

    /** Whether a proxy upstream travels in cleartext - any scheme but {@code https}, which would send the per-host
     *  upstream credential in the clear - by the shared {@link PrivateHostGuard#cleartextRefusal} rule. */
    public static boolean plaintextUpstream(URI upstream) {
        return upstream != null && PrivateHostGuard.cleartextRefusal(upstream) != null;
    }

    /**
     * The reason an <b>operator-configured</b> proxy upstream must be refused, or {@code null} when it may be used. It
     * carries a per-host upstream credential, so a cleartext transport is refused as for every other configured
     * outbound target.
     *
     * <p><b>The transport half only.</b> An internal, privately addressed mirror is a legitimate operator choice, and
     * this is read on a render path, where resolving a host would be an external lookup on a GET - the read-purity
     * breach {@link PrivateHostGuard#cleartextRefusal} avoids. So the host half is not applied.
     *
     * <p>The dial is {@link ProxyLeg#ALLOW_INTERNAL}, shared with the proxy legs: a deployment pulling from a plaintext
     * internal mirror must admit its advertised downloads too. The rule itself is
     * {@link OutboundTargets#configuredRefusal}, shared with the other configured outbound root
     * ({@code jenrepo.go.sumdb}); this applies it to a definition.
     */
    public static String upstreamRefusal(URI upstream, boolean allowInternal) {
        return OutboundTargets.configuredRefusal(upstream, allowInternal);
    }

    /** The reason any upstream in {@code definition} must be refused, or {@code null} -
     *  {@link #upstreamRefusal(URI, boolean)} leg by leg. */
    public static String upstreamRefusal(RepositoryDefinition definition, boolean allowInternal) {
        for (Fallback fallback : definition.fallbacks()) {
            if (fallback.source() instanceof Source.Upstream upstream) {
                String refusal = upstreamRefusal(upstream.url(), allowInternal);
                if (refusal != null) {
                    return "upstream '" + upstream.url() + "' is refused: " + refusal;
                }
            }
        }
        return null;
    }

    /** The remedy appended to every refusal of a configured upstream, naming both the fix and the deliberate
     *  opt-out. */
    public static String upstreamRemedy() {
        return " A proxy upstream is fetched with this deployment's per-host upstream credential and its answer is"
                + " cached and re-served, so a plaintext hop hands that credential to any observer and lets an"
                + " active intermediary choose what this repository serves. Point the upstream at an https URL,"
                + " or set " + ProxyLeg.ALLOW_INTERNAL + " to permit an internal or http target for this"
                + " deployment.";
    }

    /** Whether a {@code RepositoryRouter.RedirectHandler} is installed - the {@code redirect-directory} module
     *  registers itself at configuration time. It gates the {@code redirect} token at parse ({@code applyOption});
     *  {@code false} by default, so a deployment without the module refuses such a definition at every write site.
     *  Volatile: written once at boot, read on the parse path. Process-wide, since whether a module is installed is a
     *  fact of the module path. */
    private static volatile boolean redirectHandlerInstalled = false;

    /** Register or clear a redirect serve handler's presence, so the {@code redirect} token parses. Injecting a handler
     *  into a router is the separate {@code RepositoryRouter#redirecting} step. */
    public static void redirectHandlerInstalled(boolean installed) {
        redirectHandlerInstalled = installed;
    }

    /** Whether the {@code redirect} serve token currently parses (a redirect handler is installed). */
    public static boolean redirectHandlerInstalled() {
        return redirectHandlerInstalled;
    }
}
