package build.jenesis.repository.definitions;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.settings.PrivateHostGuard;
import build.jenesis.repository.store.ArtifactDescriptor;

/**
 * A repository's shape in the generalized model (EPIC 25): whether it accepts uploads into its <b>own</b>
 * store ({@code writable}) and an ordered list of {@link Fallback}s consulted, first-hit-wins, when the repository
 * does not itself hold the requested artifact. The three historical types are exactly three points in this space
 * and stay as parse-time spelling sugar: {@code hosted} =
 * writable with no fallbacks; {@code proxy <url>} = non-writable with one {@link Source.Upstream} fallback;
 * {@code group a,b} = non-writable with {@link Source.Repository} fallbacks in order. The clause grammar
 * ({@code writable} / {@code fallback <source> [nocache] [harden] [unscreened]}) additionally expresses what the
 * old model could not - the writable-and-fallbacked host+proxy hybrid, per-fallback cache and per-fallback
 * screening strength - and {@link #parse} produces this one record for both the old and the new spelling.
 *
 * <p><b>rewired resolution to walk this record directly</b> ({@code RepositoryRouter#resolve} consumes
 * {@code (writable, fallbacks)} through the typed-outcome channel), and <b>deleted the vestigial legacy
 * {@code Type} enum and its derived {@code type()} accessor</b> - the cutover close, once its last consumer (the
 * migration re-screen sweep's hardened-proxy filter) moved onto the {@code (writable, fallbacks)} model directly.
 * {@code RepositoryRouter#writeTarget} is {@code writable ? repository : null} and {@code group … push=…} is a
 * <b>hard parse refusal</b> (§2.3) - the write-delegation carry-over is gone. A few non-throwing derived views over
 * the orthogonal axes remain for the console/{@code Repositories#hardened} badge - {@link #upstream()},
 * {@link #cache()}, {@link #harden()}, {@link #members()} - each returning a safe default for a generalized shape the
 * old model could not name (a writable repo with fallbacks, a multi-upstream list); they are off the resolution path.
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
     *  {@link Source.Repository} fallback is a view whose inner repository owns its own bytes and policy (§1.1),
     *  so a {@code nocache}/{@code harden}/{@code unscreened} option on it is refused at parse.
     *
     * <p><b>EPIC 29 RD-5 (stage 2)</b> adds two orthogonal axes the clause grammar can set on an
     * {@link Source.Upstream} fallback (§1.2): an optional {@link Match} coordinate predicate ({@code match=} - the
     * fallback applies only to a request whose derived {@code ecosystem:coordinate} matches, so the walk becomes
     * MISS-composable over a coordinate-partitioned upstream set) and a {@link Serve} policy ({@code redirect} -
     * emit a {@code 307} to the upstream through the injected redirect handler instead of fetch-screen-serving it).
     * Both default to no-match / {@link Serve#PROXY}, so every legacy and non-redirect construction stays exactly a
     * caching-or-not proxy fallback and the existing {@code equals} semantics are unchanged for those. */
    public record Fallback(Source source, boolean store, Screening screening, Match match, Serve serve) {
        public Fallback {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(screening, "screening");
            Objects.requireNonNull(serve, "serve");
        }

        /** A plain proxy fallback with no coordinate predicate and the default {@link Serve#PROXY} policy - the
         *  legacy shape every existing construction and desugar path yields, kept as a secondary constructor so
         *  those call sites (and the {@code equals}-based parse tests) are byte-for-byte unchanged. */
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

    /** How a matched {@link Source.Upstream} fallback is served (§1.2): {@link #PROXY} fetch-screen-serves it
     *  through the pull-through walk as today (the default for every fallback); {@link #REDIRECT} delegates to the
     *  injected redirect handler ({@code redirect-directory} module) to emit a {@code 307} to the upstream rather
     *  than moving its bytes through the JVM - a screened-floor redirect by default, an {@code unscreened} bookmark
     *  redirect when the fallback also carries {@link Screening#UNSCREENED} (the loudly-warned opt-out). */
    public enum Serve { PROXY, REDIRECT }

    /** A coordinate predicate on a fallback ({@code match=<ecosystem>:<glob>}): the fallback applies only to a
     *  request whose format-derived {@link ArtifactDescriptor} carries a coordinate in {@code ecosystem} (matched
     *  case-insensitively, so a rule's {@code maven} matches the descriptor's OSV {@code Maven}) whose value the
     *  {@code glob} matches. A descriptor with no coordinate (a checksum root, generated metadata) is never matched
     *  by the predicate - it is left to the walk's configured order (§1.2, "empty-coordinate ⇒ configured order"),
     *  so a coordinate-less sibling is never partitioned away from the leg its artifact took. The glob is anchored
     *  and treats every character literally except {@code *} (any run, including none), identical to the
     *  {@code redirect-directory} rule-table semantics this stage migrates rules from. */
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
     *  DNS walk (EPIC 30 DF-6). */
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

        /** The DNS directory (EPIC 30 DF-6, design §7.2): a {@code fallback dns redirect} leg whose upstream is not
         *  a clause literal but resolved per request by the {@code redirect-dns} module's DNS walk
         *  ({@code DnsDirectory.locate}), through the same {@code RepositoryRouter.RedirectHandler} seam an {@link Upstream} redirect
         *  uses. The reserved source keyword {@code dns} spells it (keyword precedence over a repository literally
         *  named {@code dns}, §7.2); it is served only as a {@link Serve#REDIRECT}, so it carries no per-fallback
         *  upstream URL of its own. A singleton-shaped marker record - every DNS-directory leg is identical, the
         *  routing lives in the walk. */
        record DnsDirectory() implements Source {
        }
    }

    /** Per-fallback screening strength for fetched content (§1.1). {@code DEFAULT} = the serving tenant's gate as
     *  today (prefix screen; none if the tenant is ungated); {@code HARDEN} = EPIC 23 full-body fail-closed;
     *  {@code UNSCREENED} = an explicit, loudly-warned no-screen opt-out (never silent, §9 secure-defaults). */
    public enum Screening { DEFAULT, HARDEN, UNSCREENED }

    /** Defensively copy the fallback list into an unmodifiable list and reject the one shape that could never
     *  serve anything - not writable and with no fallbacks (§1.1, fail-loud §9). Every legacy and clause-grammar
     *  construction path yields a serveable shape, so this guards only genuinely malformed direct construction. */
    public RepositoryDefinition {
        fallbacks = List.copyOf(fallbacks);
        if (!writable && fallbacks.isEmpty()) {
            throw new IllegalArgumentException("A repository definition that is not 'writable' and has no "
                    + "fallbacks can never serve anything: declare it 'writable' (it then accepts uploads into "
                    + "its own store), or give it at least one 'fallback <source>'.");
        }
    }

    /**
     * Parse a definition string into the generalized record, for both the old and the new spelling. The old
     * spellings desugar (§2.1): {@code hosted} (and any unconfigured name) -> writable, no fallbacks;
     * {@code proxy <url> [nocache] [harden]} -> non-writable with one {@link Source.Upstream} fallback whose
     * {@code store}/{@code screening} carry the mode tokens; {@code group a,b,c} -> non-writable with
     * {@link Source.Repository} fallbacks in order. The new clause grammar is
     * {@code ( "writable" | "fallback" <source> ("nocache"|"harden"|"unscreened")* )*} with options binding to the
     * nearest preceding {@code fallback} and {@code writable} appearing at most once, position-free. <b>Cache
     * policy defaults to store:</b> a bare {@code fallback <url>} (equivalently a bare {@code proxy <url>}) caches
     * its fetched bytes ({@code store=true}) - the caching-proxy default; {@code nocache} is the explicit opt-out
     * to a discard-after-serve pass-through. An unknown leading token, an unknown proxy mode, an option with no
     * preceding fallback, or an option on a repository-name fallback is refused (fail-loud, PRINCIPLES §9).
     */
    public static RepositoryDefinition parse(String specification) {
        String[] parts = specification.trim().split("\\s+");
        return switch (parts[0]) {
            case "hosted" -> hosted();
            case "proxy" -> parseProxy(parts, specification);
            case "group" -> parseGroup(parts, specification);
            case "writable", "fallback" -> parseClauses(parts, specification);
            default -> throw new IllegalArgumentException("Unknown repository type: " + specification);
        };
    }

    /** Desugar the legacy {@code proxy <url> [nocache] [harden]} spelling (§2.1). Behavior-preserving: the mode
     *  tokens map onto the single upstream fallback's {@code store} (nocache -> no-store) and {@code screening}
     *  (harden -> HARDEN), with the same order-independence and the same fail-loud on an unknown token as before. */
    private static RepositoryDefinition parseProxy(String[] parts, String specification) {
        if (parts.length < 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("A proxy repository needs an upstream URL: " + specification);
        }
        URI upstream = upstreamUri(parts[1], specification);
        if (plaintextUpstream(upstream)) {
            warnPlaintext(upstream);
        }
        // Mode tokens after the URL, order-independent: `nocache` (do not store the fetched bytes) and/or `harden`
        // (untrusted-upstream full screening). Plain `harden` stays store-on-pass; `harden nocache` fully screens
        // every fetch yet stores nothing durably. An unknown token is refused (fail-loud, PRINCIPLES §9).
        boolean cache = true;
        boolean harden = false;
        for (int index = 2; index < parts.length; index++) {
            if (parts[index].isBlank()) {
                continue;
            }
            switch (parts[index]) {
                case "nocache" -> cache = false;
                case "harden" -> harden = true;
                default -> throw new IllegalArgumentException("Unknown proxy mode '" + parts[index]
                        + "' (expected 'nocache', 'harden', or 'harden nocache'): " + specification);
            }
        }
        return proxy(upstream, cache, harden);
    }

    /** Desugar the legacy {@code group a,b,c} spelling (§2.1) into non-writable {@link Source.Repository} fallbacks
     *  in order. §2.3: {@code group … push=…} is a <b>hard parse refusal</b> in - the write-delegation it
     *  named has no honest desugar now that writability is the repository's own property, so a silent rewrite would
     *  move where uploaded bytes land (a §9 violation). The refusal names both remedies. */
    private static RepositoryDefinition parseGroup(String[] parts, String specification) {
        // Every token after "group" is either a push= directive or a comma-separated member list; scan them all so
        // a lone push= token (e.g. "group push=releases") is recognised as a directive rather than mistaken for a
        // member, leaving no members and failing with the clear error.
        String push = null;
        List<String> members = new ArrayList<>();
        for (int index = 1; index < parts.length; index++) {
            if (parts[index].startsWith("push=")) {
                push = parts[index].substring("push=".length());
            } else {
                for (String member : parts[index].split(",")) {
                    if (!member.isBlank()) {
                        members.add(member.trim());
                    }
                }
            }
        }
        if (members.isEmpty()) {
            throw new IllegalArgumentException("A group repository needs at least one member: " + specification);
        }
        if (push != null) {
            // §2.3 hard cutover: `push=` delegated a write on the group into member `push`'s store. The generalized
            // model makes writability the repository's OWN property, so this spelling has no honest desugar - a
            // silent rewrite would move where uploaded bytes land (into the front repo's store, not the member's),
            // a §9 fail-loud violation. Refuse at parse, naming both remedies.
            throw new IllegalArgumentException("'group … push=" + push + "' is no longer supported (EPIC 25 §2.3): "
                    + "writability is now a repository's own property, so delegating a write into member '" + push
                    + "' has no honest meaning. Declare the front repository 'writable' (uploads then land in its "
                    + "own store, served local-first), or point publishers directly at '" + push + "': "
                    + specification);
        }
        return group(members);
    }

    /** Parse the new clause grammar (§1.2): {@code ( "writable" | "fallback" <source> <option>* )*} where an
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
                    // store defaults to caching on an Upstream fallback (today's proxy default); on a Repository or
                    // DnsDirectory fallback store is meaningless - the inner repository / DNS-designated target owns
                    // its own bytes, the outer stores nothing for the view (§1.1) - so it is canonicalized to false,
                    // matching the group() desugar and the redirect-only DNS leg.
                    boolean store = source instanceof Source.Upstream;
                    fallbacks.add(new Fallback(source, store, Screening.DEFAULT));
                    current = fallbacks.size() - 1;
                }
                default -> {
                    // Every other token is an option binding to the nearest preceding `fallback` - the store /
                    // screening tokens (nocache/harden/unscreened), the RD-5 `match=<ecosystem>:<glob>` coordinate
                    // predicate, and the RD-5 `redirect` serve policy. An option with no preceding fallback is
                    // refused exactly as before.
                    if (current < 0) {
                        throw new IllegalArgumentException("Option '" + token + "' must follow a 'fallback' "
                                + "clause: " + specification);
                    }
                    fallbacks.set(current, applyOption(token, fallbacks.get(current), specification));
                }
            }
        }
        // EPIC 30 DF-6 (§7.2): a DNS-directory leg is served ONLY as a redirect - it has no clause-literal upstream
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
            // (⚑ owner-decided: warn, not refuse): a `harden` upstream beside a weaker
            // (DEFAULT/UNSCREENED) upstream means a weaker fallback ordered first can serve before the strong
            // screen runs. Allowed (ordering is operator expressiveness) but flagged loudly.
            LOGGER.warn("Mixed-strength fallback list in '" + specification + "': a "
                    + "'harden' upstream sits beside a non-hardened (DEFAULT/UNSCREENED) upstream, so a weaker "
                    + "fallback ordered before a hardened one can serve first-hit before the strong screen runs "
                    + ". This is allowed but flagged; reorder so the strongest screen leads, or 'harden' "
                    + "the weaker fallback too.");
        }
        return new RepositoryDefinition(writable, fallbacks);
    }

    /** Apply one option token to the nearest preceding fallback, returning the updated fallback (§1.2, RD-5). The
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
                // A DNS-directory leg (EPIC 30 DF-6): the redirect is served by the `redirect-dns` module's handler,
                // not the static `redirect-directory` one, and the `dns` source keyword already gated on that module
                // being installed at parse time (see parseSource). So `redirect` here needs no further module check -
                // it is the mandatory serve policy for the DNS directory (design §7.2).
                return fallback.withServe(Serve.REDIRECT);
            }
            if (!redirectHandlerInstalled) {
                // Fail-loud when the behavior is unavailable (the store=s3-without-module precedent,
                // ArtifactStoreProvider.resolve:47-53): a `redirect` serve policy is served by the
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
        // own store and screening policy), exactly as before.
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
                // §9 secure-defaults: an explicit no-screen opt-out is never silent. On a `redirect` fallback this
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

    public static RepositoryDefinition hosted() {
        return new RepositoryDefinition(true, List.of());
    }

    public static RepositoryDefinition proxy(URI upstream, boolean cache) {
        return proxy(upstream, cache, false);
    }

    /** A proxy of {@code upstream}: {@code cache} stores the fetched bytes, {@code harden} makes it an
     *  untrusted-upstream hardening proxy (full-screen-then-serve). In the generalized model this is a
     *  non-writable repository with a single {@link Source.Upstream} fallback whose {@code store} is {@code cache}
     *  and whose {@code screening} is {@code HARDEN} when {@code harden}, else {@code DEFAULT}. */
    public static RepositoryDefinition proxy(URI upstream, boolean cache, boolean harden) {
        Fallback fallback = new Fallback(new Source.Upstream(upstream), cache,
                harden ? Screening.HARDEN : Screening.DEFAULT);
        return new RepositoryDefinition(false, List.of(fallback));
    }

    /** A group of {@code members} in order: a non-writable repository whose fallbacks are those members as
     *  {@link Source.Repository} sources. Write-delegation ({@code push=}) is gone (§2.3): a group is read-only,
     *  and a front door that should accept uploads is declared {@code writable}. */
    public static RepositoryDefinition group(List<String> members) {
        List<Fallback> fallbacks = members.stream()
                .map(member -> new Fallback(new Source.Repository(member), false, Screening.DEFAULT))
                .toList();
        return new RepositoryDefinition(false, fallbacks);
    }

    /** The single upstream URL of a legacy proxy shape, or {@code null} for hosted/group (as today). */
    public URI upstream() {
        return isProxyShape() ? ((Source.Upstream) fallbacks.getFirst().source()).url() : null;
    }

    /** Whether the legacy proxy shape caches fetched bytes (its single upstream fallback's {@code store});
     *  {@code false} for hosted/group (as today). */
    public boolean cache() {
        return isProxyShape() && fallbacks.getFirst().store();
    }

    /** Whether this repository serves any untrusted-upstream hardening leg: it has at least one
     *  {@link Source.Upstream} fallback whose {@code screening == HARDEN}. This is deliberately NOT limited to the
     *  legacy single-upstream proxy shape - a hardened upstream expressed in the generalized model (a
     *  {@code writable} repo with a hardened upstream fallback, or a multi-fallback list carrying a hardened
     *  upstream) is equally a hardening proxy whose cached upstream bytes must be re-verified per hit and are never
     *  redirect-safe. Restricting this to {@code isProxyShape()} let those generalized shapes skip the per-hit
     *  re-screen and the redirect exclusion (§4.2), serving retroactively-refused cached bytes; keying it
     *  on the hardened-upstream fact closes that. A {@link Source.Repository} member's effective strength is its own
     *  resolved definition (evaluated recursively by the resolution engine, which then hardens it in turn), so only
     *  {@code Upstream} screening is inspected statically here - the same rule {@link #mixedStrength} uses. */
    public boolean harden() {
        return fallbacks.stream().anyMatch(fallback ->
                fallback.source() instanceof Source.Upstream && fallback.screening() == Screening.HARDEN);
    }

    /** The member repository names of a legacy group shape, or an empty list for hosted/proxy (as today). */
    public List<String> members() {
        return isGroupShape()
                ? fallbacks.stream().map(fallback -> ((Source.Repository) fallback.source()).name()).toList()
                : List.of();
    }

    /** The legacy proxy shape: non-writable, exactly one upstream fallback. */
    private boolean isProxyShape() {
        return !writable && fallbacks.size() == 1 && fallbacks.getFirst().source() instanceof Source.Upstream;
    }

    /** The legacy group shape: non-writable, one or more fallbacks, every one a repository-name source. */
    private boolean isGroupShape() {
        return !writable && !fallbacks.isEmpty()
                && fallbacks.stream().allMatch(fallback -> fallback.source() instanceof Source.Repository);
    }

    /** Resolve a {@code fallback} source token to its {@link Source} (§1.2, EPIC 30 DF-6 §7.2): an
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
     *  dial the proxy legs read for upstream-advertised URLs (/share it deliberately). */
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
     * used. This is the proxy upstream was the one operator-configured outbound target in the product that
     * was <em>warned about</em> rather than refused, while every peer - the webhook endpoint, the forwarding
     * target, the emulator target, the redirect directory, the import guard - runs
     * {@code PrivateHostGuard.refusalReason} and declines. It carries a per-host upstream credential (the old
     * warning text said so in as many words), so it is the same credential-in-cleartext hazard on the same class
     * of URL, and the earlier ruling is that a <em>transport</em> is judgeable and therefore refusable.
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
     * <p>The rule itself moved to {@link OutboundTargets#configuredRefusal} in, because this is not the only
     * operator-configured outbound root in the edition - {@code jenreg.go.sumdb} is the other, and the two
     * were about to be two spellings of one decision. This method is where the decision is <em>applied</em> to a
     * repository definition; the decision itself is stated once.
     */
    public static String upstreamRefusal(URI upstream, boolean allowInternal) {
        return OutboundTargets.configuredRefusal(upstream, allowInternal);
    }

    /** The reason any upstream in {@code definition} must be refused, or {@code null} when every one may be used -
     *  {@link #upstreamRefusal(URI, boolean)} over the parsed fallback list, so a multi-fallback definition is
     *  screened leg by leg rather than only in its legacy single-upstream spelling. */
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

    /** Whether the {@code redirect-dns} module is installed (EPIC 30 DF-6, §7.2). It gates the parse of the reserved
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
