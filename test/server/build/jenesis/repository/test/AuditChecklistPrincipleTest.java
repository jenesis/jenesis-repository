package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The principle checkup's residue, derived rather than listed - the core's half (SPI hardening plan T-304).</b>
 *
 * <p>The plan's triage ends with a Category-3 residue: a documented contract clause that no Phase-1 helper generified
 * away and no Phase-2 contract kit can assert, verified instead by the periodic {@code /principle-check} checkup. The
 * one thing that checklist must not be is hand-maintained - a written list of "the clauses nothing asserts" goes stale
 * the moment a kit gains a leg, silently omits a clause written last week, and fails at nothing when it is wrong.
 *
 * <p>So the residue is <b>computed</b>: the clause population is parsed out of every inventoried SPI's
 * {@code Contract} javadoc block ({@link SpiContractPrincipleTest#INVENTORY} is the one place the population is
 * declared), coverage is read from {@code @jenesis.covers <service> <clauses>} markers that live <em>at the
 * mechanism</em> that discharges the clause, and the difference is rendered into {@code docs/CHECKUP_RESIDUE.md} and
 * compared with the checked-in copy. A clause that gains a kit leg leaves the checklist by itself, because the kit
 * author extends the marker rather than editing a list somewhere else.
 *
 * <p>Only a {@code source/**} file may carry a marker. That is not arbitrary: the plan already requires a contract kit
 * to be a JUnit-free contract in a <em>source</em> module (a test module is a leaf and cannot be shared), and it keeps
 * every coverage claim inside one scanned tree.
 *
 * <h2>Two halves, one checkup</h2>
 * The downstream edition runs the same derivation over its own tree and emits its own half. Neither side reads the
 * other's sources - the other repository is not checked out beside this one in CI, and a conditional read is a check
 * that silently stops running. What the downstream half adds for a core SPI is the part only it knows: which
 * implementations of it live over there, so the per-implementation expansion of a free clause is derived on the side
 * that can derive it. The checkup reads both documents.
 *
 * <h2>Which rows a machine can answer, and which a human must</h2>
 * A clause is classified by its heading, because the plan's thirteen canonical clause names are the axis along which
 * mechanisability varies. A mechanical row names the build guard whose output answers it; a human row carries the
 * question a person answers by reading the implementation against the clause. A heading that is <em>not</em> one of
 * the canonical thirteen - {@code Upstream integrity}, {@code Output safety}, {@code Redirect policy},
 * {@code SSRF posture}, {@code An upstream-supplied name is as untrusted as a client-supplied one} - is a human row by
 * default and deliberately: a bespoke heading is the SPI saying this expectation is specific to it, which is the
 * definition of the Category-3 residue and is where T-304's five named themes live.
 */
class AuditChecklistPrincipleTest {

    /** The generated checklist, relative to the repository root. */
    private static final String CHECKLIST = "docs/CHECKUP_RESIDUE.md";

    /** One numbered clause of one SPI's {@code Contract} block. */
    record Clause(String service, int number, String heading) {
    }

    /** How the checkup answers a residue clause. */
    enum Answer {
        /** A build guard or shared primitive already produces the answer; the checkup runs it and reports. */
        MECHANICAL,
        /** A person reads the implementation against the clause; no mechanical form exists. */
        HUMAN
    }

    /** The answer kind for a clause heading, and the guard a mechanical one is answered by. */
    record Check(Answer answer, String guard, String note) {

        Check {
            Objects.requireNonNull(answer, "answer");
            if (note == null || note.isBlank()) {
                throw new IllegalArgumentException("every classification states why it is classified as it is");
            }
            if ((answer == Answer.MECHANICAL) == (guard == null)) {
                throw new IllegalArgumentException("a mechanical row names its guard; a human row names none");
            }
        }
    }

    /**
     * How a residue clause is answered, keyed by the leading words of the canonical clause heading its block uses, so
     * {@code Error visibility (&sect;9) - never degrade to a short list} classifies as {@code Error visibility}. A
     * heading matching nothing here is a {@link Answer#HUMAN} row, which is the load-bearing default.
     */
    private static final Map<String, Check> CLASSIFICATION = classification();

    private static Map<String, Check> classification() {
        Map<String, Check> checks = new LinkedHashMap<>();
        checks.put("Streaming", new Check(Answer.MECHANICAL, "StreamingPrincipleTest",
                "the whole-blob-read census over every upload, download and proxy path answers 'does this "
                        + "implementation materialise an artifact' by scanning it, per implementation."));
        checks.put("Read purity", new Check(Answer.HUMAN, null,
                "and it should not be. The downstream edition answers this row mechanically with a "
                        + "`ReadRenderPrincipleTest` census over its GET paths; this repository has no counterpart, "
                        + "although §10 is a shared principle and the ingress edge, every format's serve leg and the "
                        + "console all live here. Classifying it MECHANICAL and naming that guard is what this "
                        + "suite's own `every_mechanical_row_names_a_guard_that_exists` leg refused, which is how "
                        + "the gap was found; porting the census is the fix and D-218 carries it."));
        checks.put("Bounded work", new Check(Answer.MECHANICAL, "UnboundedListingPrincipleTest",
                "the unpaged-listing census, plus the PagedTreeWalk / BoundedChildren adoption it measures, answers "
                        + "'does this implementation enumerate without a cap' - the observable half of the clause."));
        checks.put("Traversal refusal", new Check(Answer.MECHANICAL, "FormatContractSuite",
                "the format kit drives the shared traversal vectors through every fixture, so a leg that stopped "
                        + "refusing them fails rather than being read for."));
        checks.put("Selection failure", new Check(Answer.MECHANICAL, "ProvidersTest",
                "T-101's Providers primitives own selection and are exhaustively driven there; the checkup reports "
                        + "which surfaces still resolve by hand rather than re-reading each one."));
        checks.put("Absence sentinel", new Check(Answer.MECHANICAL, "ProvidersTest",
                "the sentinel is the other half of the same resolution primitive: a surface on Providers cannot "
                        + "return null, and one off it is what the checkup reports."));
        checks.put("Idempotency / replay", new Check(Answer.HUMAN, null,
                "what a repeated call must produce is stated per SPI in its own terms - a re-published version, a "
                        + "re-delivered event, a re-run sweep - and no scan distinguishes an idempotent write from "
                        + "one that merely looks like it."));
        checks.put("Thread-safety", new Check(Answer.HUMAN, null,
                "a type's shape is a proxy for it and not the clause: a stateless class can still share a client "
                        + "that is not safe to call concurrently."));
        checks.put("Error visibility", new Check(Answer.HUMAN, null,
                "which failures may be swallowed, and the blast radius of a lost call, read per implementation. The "
                        + "host's half of the same question is the fan-out containment the host suites assert."));
        checks.put("Lifecycle / ownership", new Check(Answer.HUMAN, null,
                "who creates and closes an instance, and whether it may own a thread or a client, is a statement "
                        + "about the deployment rather than about the code shape."));
        checks.put("Ordering / determinism", new Check(Answer.HUMAN, null,
                "whether a result must be deterministic across discovery order is only observable by driving the "
                        + "implementation - which is what a kit would do, so an unkitted surface's ordering clause "
                        + "is exactly the residue."));
        checks.put("Ordering / concurrency", new Check(Answer.HUMAN, null,
                "the callback-ordering and re-entrancy half of the same clause, unobservable from a source scan."));
        checks.put("Ordering", new Check(Answer.HUMAN, null,
                "the bare spelling of the same clause, kept separate so a heading is never classified by accident."));
        checks.put("Staleness", new Check(Answer.HUMAN, null,
                "how an implementation surfaces 'when was this last refreshed' is a rendering question about its own "
                        + "surface; D-029 found signal contracts carrying no last-refreshed accessor at all."));
        checks.put("Tenant scoping", new Check(Answer.HUMAN, null,
                "which input carries the tenant, and that a cross-tenant read is impossible, is a behaviour of the "
                        + "implementation; the key-space census answers the prefix half of it in the downstream "
                        + "edition and there is no core counterpart to name here."));
        checks.put("Durability / delivery", new Check(Answer.HUMAN, null,
                "the exact commit point and delivery class is what T-107a had to settle by argument per seam; "
                        + "nothing mechanical distinguishes best-effort from at-least-once."));
        return Collections.unmodifiableMap(checks);
    }

    /** One of T-304's named residue themes, anchored on a clause when it has one and on a surface otherwise. */
    record Theme(String service, String heading, String note) {
    }

    /**
     * The residue themes T-304 names that this repository owns. {@link #every_named_residue_theme_is_live()} fails
     * when an anchor stops resolving - because a kit finally covered it (remove the theme here and from the checkup
     * skill in the same change) or because the clause was reworded (re-anchor it).
     *
     * <p>Two of them are anchored on a surface rather than a clause, and the {@code note} says why: index rendering
     * and the token-exchange protocol are not one clause each, they are what several of an SPI's
     * documented-only clauses <em>mean</em> for a format or a provider that has to answer an external protocol.
     */
    private static final List<Theme> THEMES = List.of(
            new Theme("build.jenesis.repository.format.FetcherProvider", "SSRF posture",
                    "the transport owns every redirect hop's screen; a format leg owns only the URLs it composes or "
                            + "follows out of an upstream document."),
            new Theme("build.jenesis.repository.format.FetcherProvider", "Redirect policy",
                    "the bound on the chain, the credential drop across origins and the method carried unchanged."),
            new Theme("build.jenesis.repository.format.ProxyFormat",
                    "An upstream-supplied name is as untrusted as a client-supplied one",
                    "the URL-rewrite half of T-304's first theme, stated by the SPI in its own words."),
            new Theme("build.jenesis.repository.format.RepositoryFormat", null,
                    "index-rendering semantics: whether a generated packument, simple index, Release file or "
                            + "registration blob is correct is defined by the ecosystem's protocol document, which "
                            + "no kit in this product holds. It is what this SPI's documented-only clauses mean per "
                            + "format, so the theme is anchored on the surface."),
            new Theme("build.jenesis.repository.server.spi.TokenExchangeProvider", null,
                    "token-exchange protocol handling: the OIDC/OAuth2 exchange is judged against the identity "
                            + "provider's own specification, so every clause of this surface is a checkup row."),
            new Theme("build.jenesis.repository.ui.Panel", "Output safety",
                    "console rendering: an artifact name is attacker-controlled and the fragment is dropped into the "
                            + "shell unescaped, so escaping is a per-panel obligation nothing asserts."));

    /** A {@code @jenesis.covers <service> <clauses>} claim, and the file that made it. */
    record Coverage(String service, Set<Integer> clauses, String mechanism, Path source) {
    }

    private static final Pattern COVERS = Pattern.compile(
            "@jenesis\\.covers[ \\t]+([\\w.$]+)[ \\t]+([0-9][0-9,\\t ]*)");
    private static final Pattern LEAD_IN = Pattern.compile("<b>(.*?)</b>", Pattern.DOTALL);

    @Test
    void the_clause_population_is_parsed_from_the_inventory_and_is_not_vacuous() throws IOException {
        List<Clause> clauses = clauses();

        assertThat(clauses)
                .as("no Contract block parsed at all - the population this checklist subtracts coverage from is "
                        + "empty, so every subsequent assertion would pass for the wrong reason")
                .hasSizeGreaterThan(150);
        assertThat(clauses.stream().map(Clause::service).distinct().count())
                .as("the inventory carries twenty-odd documented surfaces; a handful means the block parser stopped "
                        + "recognising the convention rather than that the SPIs went away")
                .isGreaterThan(15);
        assertThat(clauses.stream().filter(clause -> clause.heading().isBlank()).toList())
                .as("a clause with no heading is one the parser found but could not name, which would render as an "
                        + "unanswerable checklist row")
                .isEmpty();
    }

    @Test
    void every_documented_inventory_surface_reaches_the_checklist() throws IOException {
        Path sourceRoot = SpiContractPrincipleTest.sourceRoot();
        Map<String, Path> sources = SpiContractPrincipleTest.interfaceSources(sourceRoot);
        Set<String> parsed = clauses().stream().map(Clause::service)
                .collect(Collectors.toCollection(TreeSet::new));

        List<String> missing = new ArrayList<>();
        for (SpiContractPrincipleTest.Surface surface : SpiContractPrincipleTest.INVENTORY) {
            Path source = sources.get(surface.service());
            if (source == null || parsed.contains(surface.service())) {
                continue;
            }
            missing.add("  - " + surface.service() + "  (" + sourceRoot.getParent().relativize(source) + ")");
        }

        assertThat(missing)
                .as("these inventoried SPIs carry a Contract block that SpiContractPrincipleTest accepts but this "
                        + "parser produced no clauses for. The checklist would silently omit them, which is the "
                        + "hand-maintained-list failure by another route - fix the block's shape (a numbered <ol> of "
                        + "<li><b>Clause name.</b> items) or the parser.%n%s",
                        String.join(System.lineSeparator(), missing))
                .isEmpty();
    }

    @Test
    void every_coverage_claim_names_a_real_clause_of_a_real_surface() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        clauses().forEach(clause -> counts.merge(clause.service(), clause.number(), Math::max));

        List<String> broken = new ArrayList<>();
        for (Coverage coverage : coverage()) {
            Integer clauseCount = counts.get(coverage.service());
            if (clauseCount == null) {
                broken.add("  - " + coverage.mechanism() + " claims " + coverage.service()
                        + ", which is not an inventoried surface with a parsed Contract block");
                continue;
            }
            for (int clause : coverage.clauses()) {
                if (clause < 1 || clause > clauseCount) {
                    broken.add("  - " + coverage.mechanism() + " claims " + coverage.service() + " clause " + clause
                            + ", but that block has " + clauseCount + " clauses");
                }
            }
        }

        assertThat(broken)
                .as("a coverage marker naming a clause that does not exist is a claim nothing can check: the "
                        + "checklist would drop a row for a clause the mechanism never asserted. Markers are "
                        + "`@jenesis.covers <fully.qualified.Service> <clause numbers>` and they are subtracted from "
                        + "the parsed block, so they must agree with it.%n%s",
                        String.join(System.lineSeparator(), broken))
                .isEmpty();
    }

    @Test
    void every_mechanical_row_names_a_guard_that_exists() throws IOException {
        Path tests = SpiContractPrincipleTest.sourceRoot().getParent().resolve("test");
        Set<String> guards = residue().stream().map(clause -> classify(clause.heading()).guard())
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));

        List<String> missing = new ArrayList<>();
        for (String guard : guards) {
            if (!exists(tests, guard)) {
                missing.add("  - " + guard);
            }
        }

        assertThat(missing)
                .as("a checklist row saying 'the checkup answers this by running X' when X is not in the tree is a "
                        + "row recorded as answerable by nobody - worse than an honest human row. Rename the guard "
                        + "in CLASSIFICATION or reclassify the clause as HUMAN with the argument.%n%s",
                        String.join(System.lineSeparator(), missing))
                .isEmpty();
        assertThat(guards).as("no mechanical row at all means CLASSIFICATION stopped matching any heading, so every "
                + "row silently became a human one").isNotEmpty();
    }

    @Test
    void every_named_residue_theme_is_live() throws IOException {
        List<Clause> residue = residue();

        List<String> dead = new ArrayList<>();
        for (Theme theme : THEMES) {
            boolean live = residue.stream().anyMatch(clause -> clause.service().equals(theme.service())
                    && (theme.heading() == null || clause.heading().startsWith(theme.heading())));
            if (!live) {
                dead.add("  - " + theme.service()
                        + (theme.heading() == null ? " (no residue clause left at all)"
                                : " \"" + theme.heading() + "\" (no residue clause of that name)"));
            }
        }

        assertThat(dead)
                .as("T-304 names these residue themes and the checkup skill points at them by name. A theme whose "
                        + "anchor is gone is either finished - remove it here and from the skill in the same "
                        + "change - or the clause was reworded and the skill now points at nothing.%n%s",
                        String.join(System.lineSeparator(), dead))
                .isEmpty();
    }

    @Test
    void the_generated_checklist_matches_the_tree() throws IOException {
        Path checklist = SpiContractPrincipleTest.sourceRoot().getParent().resolve(CHECKLIST);
        String rendered = render();
        String stored = Files.exists(checklist) ? Files.readString(checklist) : "";

        if (!rendered.equals(stored)) {
            Files.createDirectories(checklist.getParent());
            Files.writeString(checklist, rendered);
        }

        assertThat(stored)
                .as("%s is generated from the tree, and it did not match. It has just been rewritten - review the "
                        + "diff and commit it. A row that appeared is a documented clause nothing asserts; a row "
                        + "that vanished is a clause a helper or kit now covers, which is the burn-down working.",
                        CHECKLIST)
                .isEqualTo(rendered);
    }

    // --- the derivation -------------------------------------------------------------------------------------------

    /**
     * The tree is read once per JVM, not once per question. Every derivation below is a full walk of {@code source/}
     * reading several thousand files, and {@link #render()} alone asks five of them - so without this the suite spends
     * minutes re-reading an unchanged tree. A test JVM is forked per module and the sources cannot change under it, so
     * the cache is safe as well as necessary.
     */
    private static final Map<String, Object> MEMO = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static <T> T memoised(String key, Callable<T> derivation) throws IOException {
        Object cached = MEMO.get(key);
        if (cached == null) {
            try {
                cached = derivation.call();
            } catch (IOException | RuntimeException direct) {
                throw direct instanceof IOException reading ? reading : (RuntimeException) direct;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
            MEMO.put(key, cached);
        }
        return (T) cached;
    }

    /** Every numbered clause of every inventoried surface, in inventory then clause order. */
    private static List<Clause> clauses() throws IOException {
        return memoised("clauses", AuditChecklistPrincipleTest::readClauses);
    }

    private static List<Clause> readClauses() throws IOException {
        Path sourceRoot = SpiContractPrincipleTest.sourceRoot();
        Map<String, Path> sources = SpiContractPrincipleTest.interfaceSources(sourceRoot);
        List<Clause> clauses = new ArrayList<>();
        for (SpiContractPrincipleTest.Surface surface : SpiContractPrincipleTest.INVENTORY) {
            Path source = sources.get(surface.service());
            if (source == null) {
                continue;
            }
            List<String> headings = headings(Files.readString(source), surface.simpleName());
            for (int index = 0; index < headings.size(); index++) {
                clauses.add(new Clause(surface.service(), index + 1, headings.get(index)));
            }
        }
        return List.copyOf(clauses);
    }

    /** The clauses no {@code @jenesis.covers} marker claims - the checkup's checklist. */
    private static List<Clause> residue() throws IOException {
        return memoised("residue", AuditChecklistPrincipleTest::readResidue);
    }

    private static List<Clause> readResidue() throws IOException {
        Map<String, Set<Integer>> covered = new TreeMap<>();
        for (Coverage coverage : coverage()) {
            covered.computeIfAbsent(coverage.service(), _ -> new TreeSet<>()).addAll(coverage.clauses());
        }
        return clauses().stream()
                .filter(clause -> !covered.getOrDefault(clause.service(), Set.of()).contains(clause.number()))
                .toList();
    }

    /** Every coverage claim under {@code source/}, in file order. */
    private static List<Coverage> coverage() throws IOException {
        return memoised("coverage", AuditChecklistPrincipleTest::readCoverage);
    }

    private static List<Coverage> readCoverage() throws IOException {
        Path sourceRoot = SpiContractPrincipleTest.sourceRoot();
        List<Coverage> claims = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(SpiContractPrincipleTest::isJava).sorted()::iterator) {
                Matcher matcher = COVERS.matcher(Files.readString(file));
                while (matcher.find()) {
                    Set<Integer> numbers = new TreeSet<>();
                    for (String number : matcher.group(2).split("[,\\s]+")) {
                        if (!number.isBlank()) {
                            numbers.add(Integer.parseInt(number));
                        }
                    }
                    String name = file.getFileName().toString();
                    claims.add(new Coverage(matcher.group(1), numbers,
                            name.substring(0, name.length() - ".java".length()), file));
                }
            }
        }
        return List.copyOf(claims);
    }

    /** The provider classes of every inventoried surface - the per-implementation axis. */
    private static Map<String, Set<String>> implementations() throws IOException {
        return memoised("implementations", AuditChecklistPrincipleTest::readImplementations);
    }

    private static Map<String, Set<String>> readImplementations() throws IOException {
        Path sourceRoot = SpiContractPrincipleTest.sourceRoot();
        SpiContractPrincipleTest.Graph graph = SpiContractPrincipleTest.graph(sourceRoot);
        Map<String, Path> sources = SpiContractPrincipleTest.interfaceSources(sourceRoot);
        Map<String, Set<String>> roles = SpiContractPrincipleTest.rolesByProvider(graph, sources);

        Map<String, Set<String>> implementations = new TreeMap<>();
        for (SpiContractPrincipleTest.Surface surface : SpiContractPrincipleTest.INVENTORY) {
            Set<String> providers = new TreeSet<>();
            if (surface.base() == null) {
                graph.declarations().getOrDefault(surface.service(), List.of())
                        .forEach(declaration -> providers.add(declaration.provider()));
            } else {
                providers.addAll(roles.getOrDefault(surface.service(), Set.of()));
            }
            implementations.put(surface.service(), Collections.unmodifiableSet(providers));
        }
        return Collections.unmodifiableMap(implementations);
    }

    /** Types under {@code source/} carrying a Contract block that no inventory surface names - visible, not waived. */
    private static List<String> documentedButNotInventoried() throws IOException {
        return memoised("outside", AuditChecklistPrincipleTest::readDocumentedButNotInventoried);
    }

    private static List<String> readDocumentedButNotInventoried() throws IOException {
        Path sourceRoot = SpiContractPrincipleTest.sourceRoot();
        Set<String> inventoried = SpiContractPrincipleTest.INVENTORY.stream()
                .map(SpiContractPrincipleTest.Surface::service)
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> outside = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(SpiContractPrincipleTest::isJava).sorted()::iterator) {
                String body = Files.readString(file);
                if (!body.contains("<h2>Contract</h2>")) {
                    continue;
                }
                Matcher packageName = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(body);
                if (!packageName.find()) {
                    continue;
                }
                String simple = file.getFileName().toString();
                String qualified = packageName.group(1) + "."
                        + simple.substring(0, simple.length() - ".java".length());
                if (!inventoried.contains(qualified)) {
                    outside.add(qualified);
                }
            }
        }
        return List.copyOf(outside);
    }

    /** The classification of a clause heading: the first entry whose key the heading starts with, else HUMAN. */
    private static Check classify(String heading) {
        for (Map.Entry<String, Check> entry : CLASSIFICATION.entrySet()) {
            if (heading.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return new Check(Answer.HUMAN, null,
                "a clause the SPI stated in its own words rather than in the plan's thirteen: its reference is the "
                        + "protocol, the vendor API or the rendered surface, which is what makes it Category 3.");
    }

    /**
     * The {@code <li><b>...</b>} lead-ins of the first top-level {@code <ol>} inside the {@code Contract} block of
     * {@code simpleName}'s type javadoc. Nesting is tracked, so a clause carrying its own sub-list (the delivery
     * classes, the crash windows, the redirect rules) contributes one heading rather than one per sub-item, and a
     * preamble {@code <ul>} before the list (the enforcement key {@code RepositoryFormat} and {@code ArtifactStore}
     * carry) is skipped rather than counted.
     */
    static List<String> headings(String body, String simpleName) {
        Matcher declaration = Pattern.compile(
                        "(?m)^\\s*(?:public\\s+)?(?:sealed\\s+|non-sealed\\s+)?interface\\s+"
                                + Pattern.quote(simpleName) + "\\b")
                .matcher(body);
        if (!declaration.find()) {
            return List.of();
        }
        int close = body.lastIndexOf("*/", declaration.start());
        int open = close < 0 ? -1 : body.lastIndexOf("/**", close);
        if (open < 0) {
            return List.of();
        }
        String javadoc = body.substring(open, close);
        int contract = javadoc.indexOf("<h2>Contract</h2>");
        if (contract < 0) {
            contract = javadoc.indexOf("Contract:");
        }
        if (contract < 0) {
            return List.of();
        }
        String block = javadoc.substring(contract);
        int start = block.indexOf("<ol>");
        if (start < 0) {
            return List.of();
        }

        String tail = block.substring(start);
        int depth = 0;
        List<Integer> tops = new ArrayList<>();
        Matcher tokens = Pattern.compile("<ol>|</ol>|<ul>|</ul>|<li>").matcher(tail);
        while (tokens.find()) {
            switch (tokens.group()) {
                case "<ol>", "<ul>" -> depth++;
                case "</ol>", "</ul>" -> depth--;
                default -> {
                    if (depth == 1) {
                        tops.add(tokens.end());
                    }
                }
            }
            if (depth == 0) {
                break;   // the first top-level list has closed; anything after it is prose, not clauses
            }
        }
        List<String> headings = new ArrayList<>();
        for (int index = 0; index < tops.size(); index++) {
            int from = tops.get(index);
            int to = index + 1 < tops.size() ? tops.get(index + 1) : tail.length();
            Matcher leadIn = LEAD_IN.matcher(tail.substring(from, to));
            headings.add(leadIn.find() ? text(leadIn.group(1)) : "");
        }
        return List.copyOf(headings);
    }

    /** Javadoc markup reduced to the plain text a checklist row reads as. */
    private static String text(String markup) {
        String plain = markup.replaceAll("\\{@\\w+\\s+([^}]*)}", "$1")
                .replaceAll("<[^>]*>", "")
                .replace("&sect;", "§")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .strip();
        return plain.endsWith(".") ? plain.substring(0, plain.length() - 1) : plain;
    }

    /** Whether a suite of that simple name exists anywhere under {@code tests}. */
    private static boolean exists(Path tests, String simpleName) {
        try (Stream<Path> walk = Files.walk(tests)) {
            return walk.anyMatch(path -> path.getFileName().toString().equals(simpleName + ".java"));
        } catch (IOException reading) {
            throw new AssertionError("could not read the test sources under " + tests, reading);
        }
    }

    // --- the rendered checklist -----------------------------------------------------------------------------------

    /** The checklist document, deterministic in inventory then clause order. */
    private static String render() throws IOException {
        List<Clause> clauses = clauses();
        List<Clause> residue = residue();
        List<Coverage> coverage = coverage();
        Map<String, Set<String>> implementations = implementations();
        Map<String, List<Clause>> byService = residue.stream()
                .collect(Collectors.groupingBy(Clause::service, TreeMap::new, Collectors.toList()));
        Map<String, List<Coverage>> claimsByService = coverage.stream()
                .collect(Collectors.groupingBy(Coverage::service, TreeMap::new, Collectors.toList()));

        StringBuilder out = new StringBuilder();
        out.append("""
                # Principle-checkup residue - the core's half

                **Generated by `AuditChecklistPrincipleTest` (SPI hardening plan T-304). Do not edit by hand:** the
                suite rewrites this file and fails when it differs from the tree, so an edit here is reverted on the
                next run. To take a row off this list, make a Phase-1 helper or a Phase-2 kit discharge the clause and
                say so at the mechanism with a `@jenesis.covers <service> <clause numbers>` marker; the row then drops
                off by itself.

                Every row below is a documented contract clause of an inventoried SPI that **no helper and no kit
                claims**. The downstream edition emits the matching half over its own SPIs, and - for the core
                SPIs it implements - the per-implementation list only that side knows. The checkup reads both.

                """);

        out.append("## Named residue themes\n\n");
        for (Theme theme : THEMES) {
            out.append("- `").append(theme.service()).append('`');
            if (theme.heading() != null) {
                out.append(" clause **").append(theme.heading()).append("**");
            }
            out.append(" - ").append(theme.note()).append('\n');
        }

        out.append("\n## Coverage claimed so far\n\n");
        if (coverage.isEmpty()) {
            out.append("_No `@jenesis.covers` marker exists yet: every documented clause below is residue._\n");
        } else {
            claimsByService.forEach((service, claims) -> {
                Set<Integer> numbers = new TreeSet<>();
                Set<String> mechanisms = new TreeSet<>();
                claims.forEach(claim -> {
                    numbers.addAll(claim.clauses());
                    mechanisms.add(claim.mechanism());
                });
                out.append("- `").append(service).append("` clauses ").append(numbers)
                        .append(" - claimed by ").append(mechanisms).append('\n');
            });
        }

        List<String> outside = documentedButNotInventoried();
        out.append("\n## Documented types outside the inventory\n\n")
                .append(outside.size()).append(" types under `source/` carry a `Contract` block but are named by no ")
                .append("`uses`/`provides` clause, so they are outside `SpiContractPrincipleTest`'s inventory and ")
                .append("therefore outside this checklist. They are listed rather than waived, because an ")
                .append("unlisted one is a documented expectation nobody can see.\n\n");
        outside.forEach(type -> out.append("- `").append(type).append("`\n"));

        out.append("\n## The residue, by surface\n\n")
                .append(residue.size()).append(" of ").append(clauses.size())
                .append(" documented clauses across ").append(byService.size()).append(" surfaces.\n");
        byService.forEach((service, rows) -> {
            Set<String> providers = implementations.getOrDefault(service, Set.of());
            out.append("\n### `").append(service).append("`\n\n")
                    .append("Answer each row for: ")
                    .append(providers.isEmpty() ? "_no implementation declared in this tree_"
                            : providers.stream().map(AuditChecklistPrincipleTest::simpleName)
                                    .collect(Collectors.joining(", ")))
                    .append(" (plus the downstream edition's, listed in its own half)\n\n");
            for (Clause row : rows) {
                Check check = classify(row.heading());
                out.append("- [ ] clause ").append(row.number()).append(" **").append(row.heading()).append("** - ")
                        .append(check.answer() == Answer.MECHANICAL
                                ? "MECHANICAL, report `" + check.guard() + "`"
                                : "HUMAN")
                        .append('\n');
            }
        });
        return out.toString();
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }
}
