package build.jenesis.repository.definitions;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.settings.PrivateHostGuard;
import build.jenesis.repository.store.ArtifactDescriptor;

/**
 * A repository's shape: whether it accepts uploads into its <b>own</b> store ({@code writable}) and an ordered list
 * of {@link Fallback}s consulted, first-hit-wins, when the repository does not itself hold the requested artifact.
 * One clause grammar spells it - {@code writable} and {@code fallback <source> [options]} - and {@link #parse}
 * accepts nothing else. A repository that only accepts uploads is {@code writable}; a caching proxy is
 * {@code fallback <url>}; a grouped view is {@code fallback a fallback b}; and the same grammar expresses the
 * writable repository with fallbacks, a per-fallback cache and a per-fallback screening strength.
 *
 * <p>{@code RepositoryRouter#resolve} walks this record directly, and a write lands in a repository's own store iff
 * it is {@code writable}: writability is a repository's own property, so no definition delegates its writes to
 * another. {@link #harden()} is the one derived view, read by the console badge and the re-screen sweeps.
 *
 * <p><b>Its own module, because it is data every repository screen reads.</b> The record, its parser and the
 * two parse-time module switches ({@link #redirectHandlerInstalled(boolean)},
 * {@link #dnsDirectoryInstalled(boolean)}) lived as a nested type of the router until 2026-09-20, so a console
 * screen that only rendered a definition required the router, and with it the gate, the compliance SPI, the
 * inventory, the metadata store and the maintenance seam. What the model itself needs is the outbound-target
 * rule ({@code blobs}), the cleartext rule ({@code settings}) and the artifact descriptor a {@code match=}
 * predicate reads ({@code store}); the router consumes the record through {@code RepositoryRouter#resolve} and
 * stays where the serving is.
 */
public record RepositoryDefinition(boolean writable, List<Fallback> fallbacks) {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryDefinition.class);

    /** One ordered fallback: an external upstream URL or another repository, with this repository's per-fallback
     *  copy ({@code store}) and {@code screening} policy for content fetched from it. {@code store} and
     *  {@code screening} are meaningful <b>only</b> on an {@link Source.Upstream} fallback - a
     *  {@link Source.Repository} fallback is a view whose inner repository owns its own bytes and policy,
     *  so a {@code nocache}/{@code harden}/{@code unscreened} option on it is refused at parse.
     *
     * <p>Two further axes are orthogonal to those: an optional {@link Match} coordinate predicate ({@code match=} -
     * the fallback applies only to a request whose derived {@code ecosystem:coordinate} matches, so the walk becomes
     * MISS-composable over a coordinate-partitioned upstream set) and a {@link Serve} policy ({@code redirect} -
     * emit a {@code 307} to the upstream through the injected redirect handler instead of fetch-screen-serving it).
     * Both default to no-match / {@link Serve#PROXY}, which is a caching-or-not proxy fallback. */
    public record Fallback(Source source, boolean store, Screening screening, Match match, Serve serve) {
        public Fallback {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(screening, "screening");
            Objects.requireNonNull(serve, "serve");
        }

        /** A fallback with no coordinate predicate and the default {@link Serve#PROXY} policy - what a
         *  {@code fallback <source>} clause without {@code match=} or {@code redirect} parses to. */
        public Fallback(Source source, boolean store, Screening screening) {
            this(source, store, screening, null, Serve.PROXY);
        }

        /** Whether this fallback's coordinate predicate admits {@code descriptor} - {@code true} when there is no
         *  predicate ({@code match == null}), otherwise the predicate over the parsed coordinate. */
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

    /** How a matched {@link Source.Upstream} fallback is served: {@link #PROXY} fetch-screen-serves it
     *  through the pull-through walk as today (the default for every fallback); {@link #REDIRECT} delegates to the
     *  injected redirect handler ({@code redirect-directory} module) to emit a {@code 307} to the upstream rather
     *  than moving its bytes through the JVM - a screened-floor redirect by default, an {@code unscreened} bookmark
     *  redirect when the fallback also carries {@link Screening#UNSCREENED} (the loudly-warned opt-out). */
    public enum Serve { PROXY, REDIRECT }

    /** A coordinate predicate on a fallback ({@code match=<ecosystem>:<glob>}): the fallback applies only to a
     *  request whose format-derived {@link ArtifactDescriptor} carries a coordinate in {@code ecosystem} (matched
     *  case-insensitively, so a rule's {@code maven} matches the descriptor's OSV {@code Maven}) whose value the
     *  {@code glob} matches. A descriptor with no coordinate (a checksum root, generated metadata) is never matched
     *  by the predicate - it is left to the walk's configured order, so a coordinate-less sibling is never
     *  partitioned away from the leg its artifact took. The glob is anchored and treats every character literally
     *  except {@code *} (any run, including none). */
    public record Match(String ecosystem, String glob) {
        public Match {
            Objects.requireNonNull(ecosystem, "ecosystem");
            Objects.requireNonNull(glob, "glob");
        }

        /** Whether this predicate routes {@code descriptor}: a coordinate-carrying descriptor whose ecosystem
         *  matches (case-insensitively) and whose coordinate the glob matches. */
        public boolean matches(ArtifactDescriptor descriptor) {
            return descriptor != null && descriptor.coordinate() != null
                    && ecosystem.equalsIgnoreCase(descriptor.ecosystem())
                    && pattern().matcher(descriptor.coordinate()).matches();
        }

        /** Compile the glob to an anchored regex: every character literal except {@code *} (any run). Not cached -
         *  the fallback set is tiny and the match runs once per fallback per request. */
        private Pattern pattern() {
            StringBuilder regex = new StringBuilder();
            int start = 0;
            for (int i = 0; i < glob.length(); i++) {
                if (glob.charAt(i) == '*') {
                    if (i > start) {
                        regex.append(Pattern.quote(glob.substring(start, i)));
                    }
                    regex.append(".*");
                    start = i + 1;
                }
            }
            if (start < glob.length()) {
                regex.append(Pattern.quote(glob.substring(start)));
            }
            return Pattern.compile("^" + regex + "$");
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

    /** Where a fallback's content comes from: an external upstream fetched via {@code ProxyFormat.Fetcher},
     *  another repository resolved by recursion, or the DNS directory whose upstream is resolved per request by the
     *  DNS walk. */
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

        /** The DNS directory: a {@code fallback dns redirect} leg whose upstream is not a clause literal but resolved
         *  per request by the {@code redirect-dns} module's DNS walk ({@code DnsDirectory.locate}), through the same
         *  {@code RepositoryRouter.RedirectHandler} seam an {@link Upstream} redirect uses. The reserved source keyword
         *  {@code dns} spells it (the keyword takes precedence over a repository literally named {@code dns}); it is
         *  served only as a {@link Serve#REDIRECT}, so it carries no per-fallback upstream URL of its own. A
         *  singleton-shaped marker record - every DNS-directory leg is identical, the routing lives in the walk. */
        record DnsDirectory() implements Source {
        }
    }

    /** Per-fallback screening strength for fetched content. {@code DEFAULT} = the serving tenant's gate (a prefix
     *  screen; none if the tenant is ungated); {@code HARDEN} = a full-body, fail-closed screen before anything is
     *  served; {@code UNSCREENED} = an explicit, loudly-warned no-screen opt-out (never silent, §9). */
    public enum Screening { DEFAULT, HARDEN, UNSCREENED }

    /** Defensively copy the fallback list into an unmodifiable list and reject the one shape that could never
     *  serve anything - not writable and with no fallbacks (fail-loud, §9). Every clause-grammar parse yields a
     *  serveable shape, so this guards only malformed direct construction. */
    public RepositoryDefinition {
        fallbacks = List.copyOf(fallbacks);
        if (!writable && fallbacks.isEmpty()) {
            throw new IllegalArgumentException("A repository definition that is not 'writable' and has no "
                    + "fallbacks can never serve anything: declare it 'writable' (it then accepts uploads into "
                    + "its own store), or give it at least one 'fallback <source>'.");
        }
    }

    /**
     * Parse a definition string in the clause grammar {@code ( "writable" | "fallback" <source> <option>* )*}, an
     * option being {@code nocache}, {@code harden}, {@code unscreened}, {@code redirect} or
     * {@code match=<ecosystem>:<glob>}, into the record. An option binds to the nearest preceding {@code fallback},
     * and {@code writable} appears at most once, position-free. <b>Cache policy defaults to store:</b> a bare
     * {@code fallback <url>} caches its fetched bytes ({@code store=true}); {@code nocache} is the explicit opt-out to
     * a discard-after-serve pass-through. A definition that does not start with a clause, an option with no preceding
     * fallback, an unknown option, or a store/screening option on a repository-name fallback is refused (fail-loud,
     * §9). The words {@code hosted}, {@code proxy} and {@code group} are refused like any other leading token, with
     * the clause that says the same thing named in the message.
     */
    public static RepositoryDefinition parse(String specification) {
        String[] parts = specification.trim().split("\\s+");
        return switch (parts[0]) {
            case "writable", "fallback" -> parseClauses(parts, specification);
            default -> throw new IllegalArgumentException("A repository definition is written in clauses - "
                    + "'writable' and/or 'fallback <source> [options]' - and cannot start with '" + parts[0] + "'"
                    + instead(parts) + ": " + specification);
        };
    }

    /** The clause that says what a word outside the grammar meant, for the three words that name a repository kind
     *  rather than a clause - or nothing, for any other token. */
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

    /** Parse the clause grammar: {@code ( "writable" | "fallback" <source> <option>* )*} where an
     *  {@code <option>} ({@code nocache}/{@code harden}/{@code unscreened}) binds to the nearest preceding
     *  {@code fallback}, and {@code writable} appears at most once, position-free. A {@code fallback <url>} defaults
     *  to {@code store=true} (the caching-proxy default); {@code nocache} is the explicit opt-out. */
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
                    // store defaults to caching on an Upstream fallback; on a Repository or DnsDirectory fallback
                    // store is meaningless - the inner repository / DNS-designated target owns its own bytes, the
                    // outer stores nothing for the view - so it is canonicalized to false.
                    boolean store = source instanceof Source.Upstream;
                    fallbacks.add(new Fallback(source, store, Screening.DEFAULT));
                    current = fallbacks.size() - 1;
                }
                default -> {
                    // Every other token is an option binding to the nearest preceding `fallback` - the store /
                    // screening tokens (nocache/harden/unscreened), the `match=<ecosystem>:<glob>` coordinate
                    // predicate, and the `redirect` serve policy. An option with no preceding fallback is refused.
                    if (current < 0) {
                        throw new IllegalArgumentException("Option '" + token + "' must follow a 'fallback' "
                                + "clause: " + specification);
                    }
                    fallbacks.set(current, applyOption(token, fallbacks.get(current), specification));
                }
            }
        }
        // A DNS-directory leg is served ONLY as a redirect - it has no clause-literal upstream
        // to fetch-screen-serve, its target is resolved per request by the DNS walk. A `fallback dns` without the
        // `redirect` serve is the reserved-keyword collision: refuse it as a fail-loud rename ask (the keyword `dns`
        // takes precedence over a repository literally named `dns`, so such a repository must be renamed), rather
        // than construct a DnsDirectory leg the walk could never serve.
        for (Fallback fallback : fallbacks) {
            if (fallback.source() instanceof Source.DnsDirectory && fallback.serve() != Serve.REDIRECT) {
                throw new IllegalArgumentException("The source keyword 'dns' is reserved for the DNS directory "
                        + "(the 'redirect-dns' module), which is served only as a redirect: write 'fallback dns "
                        + "redirect'. A repository literally named 'dns' collides with this reserved keyword and "
                        + "can never be addressed as a fallback source - rename that repository: " + specification);
            }
        }
        if (mixedStrength(fallbacks)) {
            // Warned, not refused: a `harden` upstream beside a weaker
            // (DEFAULT/UNSCREENED) upstream means a weaker fallback ordered first can serve before the strong
            // screen runs. Allowed (ordering is operator expressiveness) but flagged loudly.
            LOGGER.warn("Mixed-strength fallback list in '" + specification + "': a "
                    + "'harden' upstream sits beside a non-hardened (DEFAULT/UNSCREENED) upstream, so a weaker "
                    + "fallback ordered before a hardened one can serve first-hit before the strong screen runs"
                    + ". This is allowed but flagged; reorder so the strongest screen leads, or 'harden' "
                    + "the weaker fallback too.");
        }
        return new RepositoryDefinition(writable, fallbacks);
    }

    /** Apply one option token to the nearest preceding fallback, returning the updated fallback. The
     *  store/screening tokens ({@code nocache}/{@code harden}/{@code unscreened}) and the {@code redirect} serve
     *  policy are refused on a {@link Source.Repository} fallback (the inner repository owns its own store, policy
     *  and serving); the {@code match=} coordinate predicate is a pure walk filter allowed on either source. A
     *  {@code redirect} with no {@code redirect-directory} module installed is a fail-loud parse refusal naming the
     *  missing module rather than a silent proxy (the {@code ArtifactStoreProvider.resolve} precedent). */
    private static Fallback applyOption(String token, Fallback fallback, String specification) {
        if (token.startsWith("match=")) {
            // A coordinate predicate is a pure walk filter (MISS-composable) meaningful on any source, so it is
            // allowed on a repository-name fallback too - it partitions which requests consult the member.
            return fallback.withMatch(Match.parse(token.substring("match=".length()), specification));
        }
        if (token.equals("redirect")) {
            if (fallback.source() instanceof Source.Repository repository) {
                throw new IllegalArgumentException("Option 'redirect' is not allowed on the repository-name "
                        + "fallback '" + repository.name() + "': a 'redirect' serve policy emits a 307 to an "
                        + "upstream URL, which a repository-name view does not have. " + specification);
            }
            if (fallback.source() instanceof Source.DnsDirectory) {
                // A DNS-directory leg: the redirect is served by the `redirect-dns` module's handler,
                // not the static `redirect-directory` one, and the `dns` source keyword already gated on that module
                // being installed at parse time (see parseSource). So `redirect` here needs no further module check -
                // it is the mandatory serve policy for the DNS directory.
                return fallback.withServe(Serve.REDIRECT);
            }
            if (!redirectHandlerInstalled) {
                // Fail-loud when the behavior is unavailable (as a store backend without its module is refused in
                // ArtifactStoreProvider.resolve): a `redirect` serve policy is served by the
                // `redirect-directory` module's injected handler, so with that module absent the token is refused
                // at every write site rather than silently degrading to fetch-screen-serve (which would move the
                // very bytes the operator asked to redirect).
                throw new IllegalArgumentException("A 'fallback <url> redirect' clause needs the "
                        + "'redirect-directory' module installed to emit the 307, but it is not present on this "
                        + "deployment; the 'redirect' serve policy is refused rather than silently proxied. Install "
                        + "the 'redirect-directory' module, or drop 'redirect' from the definition: " + specification);
            }
            return fallback.withServe(Serve.REDIRECT);
        }
        // The store / screening tokens: not allowed on a repository-name fallback (the inner repository owns its
        // own store and screening policy).
        if (fallback.source() instanceof Source.Repository repository) {
            throw new IllegalArgumentException("Option '" + token + "' is not allowed on the "
                    + "repository-name fallback '" + repository.name() + "': the fallback repository "
                    + "owns its own store and screening policy. " + specification);
        }
        // Nor on a DNS-directory leg: it emits a 307 redirect served by the redirect-dns handler and owns no store
        // or screening policy, so a store/screening token is refused loudly here rather than reaching the
        // Source.Upstream cast below (which would otherwise throw a raw ClassCastException at parse time).
        if (fallback.source() instanceof Source.DnsDirectory) {
            throw new IllegalArgumentException("Option '" + token + "' is not allowed on a 'dns' fallback: a "
                    + "DNS-directory leg emits a 307 redirect and owns no store or screening policy. "
                    + specification);
        }
        return switch (token) {
            case "nocache" -> fallback.withStore(false);
            case "harden" -> fallback.withScreening(Screening.HARDEN);
            case "unscreened" -> {
                // §9: an explicit no-screen opt-out is never silent. On a `redirect` fallback this
                // is the loudly-warned bookmark-redirect opt-out; on a proxy fallback it is the no-screen proxy.
                LOGGER.warn("SECURITY: fallback '"
                        + ((Source.Upstream) fallback.source()).url() + "' is declared 'unscreened' - its "
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

    /** Whether an ordered fallback list mixes screening strength - a {@code HARDEN} upstream beside a non-hardened
     *  ({@code DEFAULT}/{@code UNSCREENED}) upstream - the weakest-member hazard flagged (not refused) at
     *  parse. Only {@link Source.Upstream} fallbacks carry a screening strength statically; a
     *  {@link Source.Repository} fallback's effective strength is its own resolved definition, evaluated by the
     *  resolution engine which has the definition graph this parse layer does not. Exposed like
     *  {@link #plaintextUpstream} so the classifier is testable without capturing the log. */
    public static boolean mixedStrength(List<Fallback> fallbacks) {
        boolean hardened = fallbacks.stream().anyMatch(fallback ->
                fallback.source() instanceof Source.Upstream && fallback.screening() == Screening.HARDEN);
        boolean weaker = fallbacks.stream().anyMatch(fallback ->
                fallback.source() instanceof Source.Upstream && fallback.screening() != Screening.HARDEN);
        return hardened && weaker;
    }

    /** Whether this repository serves any untrusted-upstream hardening leg: it has at least one
     *  {@link Source.Upstream} fallback whose {@code screening == HARDEN}, whatever else the definition holds - a
     *  {@code writable} repository with a hardened upstream fallback, or a list of several fallbacks carrying one,
     *  is equally a hardening proxy whose cached upstream bytes must be re-verified per hit and are never
     *  redirect-safe. Keying this on anything narrower than the hardened-upstream fact would let such a definition
     *  skip the per-hit re-screen and the redirect exclusion and serve retroactively-refused cached bytes. A
     *  {@link Source.Repository} member's effective strength is its own resolved definition (evaluated recursively
     *  by the resolution engine, which then hardens it in turn), so only {@code Upstream} screening is inspected
     *  statically here - the same rule {@link #mixedStrength} uses. */
    public boolean harden() {
        return fallbacks.stream().anyMatch(fallback ->
                fallback.source() instanceof Source.Upstream && fallback.screening() == Screening.HARDEN);
    }

    /** Resolve a {@code fallback} source token to its {@link Source}: an
     *  {@code "://"}-bearing token is an {@link Source.Upstream}; the reserved keyword {@code dns} is the
     *  {@link Source.DnsDirectory} (keyword precedence over a repository literally named {@code dns}); every other
     *  token is a {@link Source.Repository}. The {@code dns} keyword needs the {@code redirect-dns} module to resolve
     *  the upstream by DNS walk, so with that module absent it is a fail-loud parse refusal naming the missing module
     *  rather than a silent reinterpretation as a repository name (mirroring the {@code redirect}-token missing-module
     *  precedent and the {@code ArtifactStoreProvider.resolve} store-without-module refusal). */
    private static Source parseSource(String token, String specification) {
        if (token.contains("://")) {
            return new Source.Upstream(upstreamUri(token, specification));
        }
        if (token.equals("dns")) {
            if (!dnsDirectoryInstalled) {
                throw new IllegalArgumentException("A 'fallback dns redirect' clause needs the 'redirect-dns' module "
                        + "installed to resolve the upstream by DNS walk, but it is not present on this deployment; "
                        + "the 'dns' source keyword is refused rather than silently taken as a repository named "
                        + "'dns'. Install the 'redirect-dns' module, or drop 'dns' from the definition: "
                        + specification);
            }
            return new Source.DnsDirectory();
        }
        return new Source.Repository(token);
    }

    /** Resolve a source token to an upstream {@link URI}, refusing a malformed one with a clear error. */
    private static URI upstreamUri(String token, String specification) {
        try {
            return URI.create(token);
        } catch (IllegalArgumentException cause) {
            throw new IllegalArgumentException(
                    "Invalid upstream URL '" + token + "': " + specification, cause);
        }
    }

    /** The one proxy outbound dial, named here so a surface that screens a configured upstream reads the key by
     *  its declared constant rather than respelling the literal - it IS {@link ProxyLeg#ALLOW_INTERNAL}, the same
     *  dial the proxy legs read for upstream-advertised URLs, shared deliberately. */
    public static final String ALLOW_INTERNAL_SETTING = ProxyLeg.ALLOW_INTERNAL;

    /** Warn loudly that an upstream travels in cleartext. Since this is only reachable when the deployment
     *  has taken the {@code proxy-allow-internal} opt-out - the write surfaces and the boot sweep refuse a
     *  plaintext upstream otherwise - so it is the standing reminder about an accepted risk rather than the whole
     *  response to an unaccepted one (§9: an insecure configuration is loud, not silent). */
    private static void warnPlaintext(URI upstream) {
        LOGGER.warn(
                "SECURITY: upstream '" + upstream + "' is not https - artifacts and any per-host upstream "
                        + "credential sent to it travel in cleartext. Use an https upstream; this plaintext "
                        + "upstream is served as configured because " + ProxyLeg.ALLOW_INTERNAL + " is set.");
    }

    /** Whether a proxy upstream travels in cleartext - any scheme other than {@code https}, over which the
     *  deployment's per-host upstream credential would be sent in the clear. One line, delegating to the shared
     *  {@link PrivateHostGuard#cleartextRefusal} rule: the product used to answer "is this upstream's
     *  transport acceptable" in two implementations - this one and the importer's
     *  {@code RepositoryAutoConfiguration.isInsecureUpstream} - only one of which held the reasoning. The rule and
     *  its wording now live where every other outbound leg reads them. */
    public static boolean plaintextUpstream(URI upstream) {
        return upstream != null && PrivateHostGuard.cleartextRefusal(upstream) != null;
    }

    /**
     * The reason an <b>operator-configured</b> proxy upstream must be refused, or {@code null} when it may be
     * used. It is refused like every other operator-configured outbound target - the webhook endpoint, the
     * forwarding target, the emulator target, the redirect directory, the import guard - each of which runs
     * {@code PrivateHostGuard.refusalReason} and declines. It carries a per-host upstream credential, so it is the
     * same credential-in-cleartext hazard on the same class of URL, and a <em>transport</em> is judgeable and
     * therefore refusable.
     *
     * <p><b>The transport half only, and deliberately so.</b> Unlike a webhook callback, this URL is the
     * operator's own choice of where to pull from, and an internal, privately-addressed mirror is a legitimate and
     * common deployment. It is also read on a <em>render</em> path (the console renders a repository's shape from
     * its stored definition), and resolving a host there would be an external lookup on a GET - the §10 breach
     * {@link PrivateHostGuard#cleartextRefusal} exists to avoid. So the host half is not applied here, and that
     * divergence is stated at the seam rather than left to be rediscovered.
     *
     * <p><b>The dial is {@link ProxyLeg#ALLOW_INTERNAL}</b>, shared with the proxy legs' screen on
     * upstream-advertised URLs: a deployment that pulls from a plaintext internal mirror has to admit its
     * advertised downloads too, so two dials could only ever disagree with each other.
     *
     * <p>The rule itself lives in {@link OutboundTargets#configuredRefusal}, because this is not the only
     * operator-configured outbound root - {@code jenreg.go.sumdb} is the other, and the two would otherwise be two
     * spellings of one decision. This method is where the decision is <em>applied</em> to a
     * repository definition; the decision itself is stated once.
     */
    public static String upstreamRefusal(URI upstream, boolean allowInternal) {
        return OutboundTargets.configuredRefusal(upstream, allowInternal);
    }

    /** The reason any upstream in {@code definition} must be refused, or {@code null} when every one may be used -
     *  {@link #upstreamRefusal(URI, boolean)} over the parsed fallback list, so a multi-fallback definition is
     *  screened leg by leg. */
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

    /** The operator-facing remedy appended to every refusal of a configured upstream, so the message names both
     *  the fix and the deliberate opt-out rather than only the rule. */
    public static String upstreamRemedy() {
        return " A proxy upstream is fetched with this deployment's per-host upstream credential and its answer is"
                + " cached and re-served, so a plaintext hop hands that credential to any observer and lets an"
                + " active intermediary choose what this repository serves. Point the upstream at an https URL,"
                + " or set " + ProxyLeg.ALLOW_INTERNAL + " to permit an internal or http target for this"
                + " deployment.";
    }

    /** Whether a {@code RepositoryRouter.RedirectHandler} is installed (the {@code redirect-directory} module registers itself here at
     *  configuration time). It gates the parse of a {@code redirect} serve token: absent, the token is a fail-loud
     *  parse refusal naming the missing module rather than a silent proxy (see {@code applyOption}). It
     *  defaults {@code false} so a deployment without the module refuses a {@code redirect} definition at every write
     *  site. Volatile because it is read on the request-parse path and written once at boot from the config thread. */
    private static volatile boolean redirectHandlerInstalled = false;

    /** Register (or clear) the presence of a redirect serve handler, so the {@code redirect} serve token parses (the
     *  {@code redirect-directory} module calls this at configuration time; clearing it restores the module-absent
     *  parse refusal). Injecting the handler into a router instance is a separate, explicit step
     *  ({@code RepositoryRouter#redirecting}) - this only flips the parse-time availability. */
    public static void redirectHandlerInstalled(boolean installed) {
        redirectHandlerInstalled = installed;
    }

    /** Whether the {@code redirect} serve token currently parses (a redirect handler is installed). */
    public static boolean redirectHandlerInstalled() {
        return redirectHandlerInstalled;
    }

    /** Whether the {@code redirect-dns} module is installed. It gates the parse of the reserved
     *  {@code dns} source keyword: absent, {@code fallback dns} is a fail-loud parse refusal naming the missing
     *  {@code redirect-dns} module rather than a silent reinterpretation as a repository named {@code dns} (see
     *  {@code parseSource}). It defaults {@code false} so a deployment without the module refuses a
     *  {@code dns} definition at every write site. Volatile because it is read on the request-parse path and written
     *  once at boot from the config thread - the exact discipline of {@link #redirectHandlerInstalled}. */
    private static volatile boolean dnsDirectoryInstalled = false;

    /** Register (or clear) the presence of the {@code redirect-dns} module, so the {@code dns} source keyword parses
     *  (the module's boot wiring calls this at configuration time; clearing it restores the module-absent parse
     *  refusal). Injecting the DNS-capable {@code RepositoryRouter.RedirectHandler} into a router instance is the separate, explicit
     *  {@code RepositoryRouter#redirecting} step - this only flips the parse-time availability of the keyword. */
    public static void dnsDirectoryInstalled(boolean installed) {
        dnsDirectoryInstalled = installed;
    }

    /** Whether the {@code dns} source keyword currently parses (the {@code redirect-dns} module is installed). */
    public static boolean dnsDirectoryInstalled() {
        return dnsDirectoryInstalled;
    }
}
