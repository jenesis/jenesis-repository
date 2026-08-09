package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core's <b>executable SPI inventory</b> and its Contract-block doc ratchet (SPI hardening plan T-002).
 *
 * <p>Every extension point of this repository is declared twice in the module graph: a consumer module writes
 * {@code uses <service>} and a plugin module writes {@code provides <service> with <impl>, <impl>}. This check parses
 * <em>both</em> clause kinds out of every source {@code module-info.java} - a {@code uses}-only scan would lose a
 * provider-only extension point, and a {@code provides}-only scan would lose an SPI nothing implements yet - and
 * confronts them with the hand-maintained {@link #INVENTORY} below. The inventory is the machine-readable census the
 * rest of the plan keys off:
 *
 * <ul>
 *   <li>the <b>service interface</b> and the <b>module that owns its source</b> (derived, never hand-typed);</li>
 *   <li>its <b>selection policy</b> ({@link Policy}) - the resolution semantics T-101's {@code Providers} primitives
 *       are keyed by, so a helper can never silently invent semantics the SPI never declared;</li>
 *   <li>its <b>provider classes</b>, parsed from the {@code provides ... with ...} lists (multiline included), which
 *       is the static half of the T-001 {@code ContractCensus} (the runtime half is a separate assertion by
 *       design - {@code ServiceLoader} cannot see a provider module a test forgot to require);</li>
 *   <li>its <b>role sub-interfaces</b> as <em>distinct contract surfaces</em>. A clause-level scan alone is not
 *       enough: {@code PublishInterceptor extends PublicationObserver} rides the single
 *       {@code uses PublicationObserver} clause (the dispatcher splits the discovered list by {@code instanceof}),
 *       and {@code ProxyFormat} / {@code ArtifactLayout} / {@code RepositoryImporter} are optional capabilities a
 *       format picks up beside the single {@code uses RepositoryFormat} clause. Their failure semantics are
 *       <em>opposite</em> (a contained after-commit observer versus a propagating pre-commit screen), so a census
 *       that flattened them into one family would drive them through the wrong contract kit. Every provider is
 *       therefore keyed by the role interface it actually implements.</li>
 * </ul>
 *
 * <h2>The doc ratchet</h2>
 * Each surface's owning interface source is located and required to carry a dedicated final javadoc block titled
 * <b>Contract</b> ({@code <h2>Contract</h2>}, or a {@code Contract:} heading), documenting whichever of the plan's
 * thirteen clauses apply - thread-safety, idempotency/replay, absence sentinel, selection failure, streaming, tenant
 * scoping, error visibility, read purity, staleness, lifecycle/ownership, ordering/concurrency, bounded
 * work/cancellation, durability/delivery. A surface without such a block needs a seeded, reason-bearing entry in
 * {@link #UNDOCUMENTED}; Phase 3 (T-301) burns that list down to empty. The ratchet bites in both directions: a new
 * {@code uses}/{@code provides} clause with no inventory metadata fails, a documented block that is removed fails,
 * and an allowlist entry that has gone stale - because the block landed, or because the SPI is gone - fails too, so
 * the waiver list can never quietly outlive what it waives.
 *
 * <h2>Honest limitations</h2>
 * This is a deterministic source scan (the {@code ConfigPrincipleTest} mould), not a compiler. It reads declarations,
 * so an SPI reached by reflection rather than a {@code provides} clause is invisible to it, and role keying reads a
 * provider's {@code implements} list and its {@code signals()} class literals rather than resolving the type
 * hierarchy - which is why {@link #every_role_split_provider_resolves_to_its_source()} refuses to let that keying be
 * vacuous. The runtime counterpart (a bundle-backed discovery graph) is T-001's job and is deliberately a separate
 * assertion.
 */
class SpiContractPrincipleTest {

    /** Which repository owns an SPI's interface source. The core owns every SPI its own clauses name; the
     *  constant exists so this inventory speaks the same vocabulary as the downstream mirror, where a downstream
     *  {@code uses}/{@code provides} of a core SPI is attributed here rather than duplicated downstream. */
    enum Home {
        FREE_CORE, DOWNSTREAM
    }

    /**
     * How a consumer selects among discovered providers - the metadata T-101's resolution primitives are keyed by.
     *
     * The four names match the {@code Providers} primitive each policy resolves through one-for-one (T-101), so the
     * metadata recorded here is what a migrated resolver actually calls rather than a parallel vocabulary:
     *
     * <ul>
     *   <li>{@link #ALL} - additive fan-out: every discovered provider participates (formats, observers, panels,
     *       importers, walk consumers), or - the degenerate additive case - the SPI is a pure presence signal whose
     *       providers are only ever counted ({@code ImportEdgeProvider}). There is nothing to select and nothing to
     *       fail. Resolves through {@code Providers.all} / {@code Providers.installedNames}.</li>
     *   <li>{@link #OPTIONAL_UNIQUE} - a singleton capability that may legitimately be absent: <em>exactly one</em>
     *       enabled provider resolves, unselected absence degrades to the SPI's declared sentinel
     *       ({@code NONE}, {@code Optional.empty()}, the fixed tenant directory), and more than one enabled provider
     *       is a configuration error rather than a discovery-order winner. An <em>explicitly</em> selected
     *       {@code jenesis.repository.<spi>=<name>} that no provider answers to fails at resolution (PRINCIPLES §9);
     *       the selection is optional, not the implementation. Resolves through {@code Providers.optionalUnique}.</li>
     *   <li>{@link #NAMED_UNIQUE} - the selection is <em>mandatory</em>: there is no unselected outcome and no
     *       sentinel at all, so both a missing selection and a selected miss fail. No core SPI is this today.
     *       Resolves through {@code Providers.namedUnique}.</li>
     *   <li>{@link #EXCLUSIVE_WITH_DEFAULT} - exactly one implementation always resolves: an unselected deployment
     *       gets the provider answering to a named built-in default (the {@code filesystem} artifact store) and the
     *       chosen provider's required configuration is validated before it is built. There is no sentinel; a
     *       selected miss refuses to fall back. Resolves through {@code Providers.exclusiveWithDefault}.</li>
     * </ul>
     *
     * <p>The line between {@link #OPTIONAL_UNIQUE} and {@link #EXCLUSIVE_WITH_DEFAULT} is whether the unselected
     * default is a <em>discovered provider</em> or a sentinel the SPI builds itself: {@code TenantsProvider} falls
     * back to {@code Tenants.fixed(tenant)}, which no provider declares, so it is optional-unique with a sentinel and
     * not exclusive-with-default.
     */
    enum Policy {
        ALL, OPTIONAL_UNIQUE, NAMED_UNIQUE, EXCLUSIVE_WITH_DEFAULT
    }

    /**
     * One contract surface. A <em>root</em> surface is a service a {@code uses}/{@code provides} clause names
     * directly ({@code base} is {@code null}); a <em>role</em> surface is a sub-interface or optional capability that
     * rides its base's single clause and carries its own, often opposite, contract.
     *
     * @param service the interface's fully qualified name
     * @param base    for a role surface, the service whose clause it rides; {@code null} for a root surface
     * @param home    the repository owning the interface source
     * @param policy  how a consumer selects among providers of this surface
     */
    record Surface(String service, String base, Home home, Policy policy) {

        Surface {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(policy, "policy");
        }

        static Surface root(String service, Policy policy) {
            return new Surface(service, null, Home.FREE_CORE, policy);
        }

        static Surface role(String service, String base, Policy policy) {
            return new Surface(service, Objects.requireNonNull(base, "base"), Home.FREE_CORE, policy);
        }

        String simpleName() {
            return service.substring(service.lastIndexOf('.') + 1);
        }
    }

    /** One parsed {@code provides <service> with <provider>} entry, with the descriptor it was read from. */
    record Declaration(String service, String provider, String module, String descriptor) {
    }

    /** The parsed module graph: {@code uses} consumers and {@code provides} declarations per service. */
    record Graph(Map<String, Set<String>> consumers, Map<String, List<Declaration>> declarations) {

        Set<String> services() {
            Set<String> services = new TreeSet<>(consumers.keySet());
            services.addAll(declarations.keySet());
            return services;
        }
    }

    /**
     * The executable inventory: every SPI surface of the core, its owner, and its selection policy. A service a
     * {@code uses} or {@code provides} clause names that is missing here fails
     * {@link #every_declared_service_carries_inventory_metadata()}; an entry no clause names any more fails
     * {@link #every_inventory_surface_is_live()}.
     */
    private static final List<Surface> INVENTORY = inventory();

    private static List<Surface> inventory() {
        List<Surface> surfaces = new ArrayList<>();

        // --- store -------------------------------------------------------------------------------------------
        // The exemplar exclusive SPI: `store=<name>` selects a backend, the bundled filesystem backend is the
        // unselected default, and an explicitly selected miss throws rather than serving the local disk (§9).
        surfaces.add(Surface.root("build.jenesis.repository.store.ArtifactStoreProvider",
                Policy.EXCLUSIVE_WITH_DEFAULT));
        // `jenesis.repository.tenants=<name>` selects a directory when one is installed; with none installed the
        // sentinel is Tenants.fixed(tenant), which the SPI builds itself rather than discovering - so this is
        // optional-unique with a sentinel, not exclusive-with-default (T-101b corrected the recorded policy).
        surfaces.add(Surface.root("build.jenesis.repository.store.TenantsProvider", Policy.OPTIONAL_UNIQUE));
        // Every discovered observer is notified; Publication splits the discovered list by instanceof.
        surfaces.add(Surface.root("build.jenesis.repository.store.PublicationObserver", Policy.ALL));
        // ROLE: the pre-commit, fail-closed half of the same clause. Its verdict legs propagate and fail the write,
        // where a plain PublicationObserver's after-commit legs are contained - opposite failure semantics, so it is
        // a distinct contract surface even though it rides `uses PublicationObserver`.
        surfaces.add(Surface.role("build.jenesis.repository.store.PublishInterceptor",
                "build.jenesis.repository.store.PublicationObserver", Policy.ALL));

        // --- format ------------------------------------------------------------------------------------------
        // A keyed catalogue: every discovered format serves its own paths, and RepositoryFormat.installed(name)
        // looks one up. Additive, so there is no selection to fail.
        surfaces.add(Surface.root("build.jenesis.repository.format.RepositoryFormat", Policy.ALL));
        // ROLES: optional capabilities a format picks up beside the single `uses RepositoryFormat` clause, detected
        // with instanceof. Each carries its own contract (upstream fetch/verify, path<->coordinate mapping,
        // index-driven import) that a hosted-only format never implements.
        surfaces.add(Surface.role("build.jenesis.repository.format.ProxyFormat",
                "build.jenesis.repository.format.RepositoryFormat", Policy.ALL));
        surfaces.add(Surface.role("build.jenesis.repository.format.ArtifactLayout",
                "build.jenesis.repository.format.RepositoryFormat", Policy.ALL));
        surfaces.add(Surface.role("build.jenesis.repository.format.RepositoryImporter",
                "build.jenesis.repository.format.RepositoryFormat", Policy.ALL));
        // `jenesis.repository.fetcher=<name>` selects one; Fetcher.NONE is the unselected sentinel.
        surfaces.add(Surface.root("build.jenesis.repository.format.FetcherProvider", Policy.OPTIONAL_UNIQUE));
        // Every discovered module view contributes to the Maven bridge's rendering.
        surfaces.add(Surface.root("build.jenesis.repository.format.java.bridge.ModuleView", Policy.ALL));

        // --- walk --------------------------------------------------------------------------------------------
        // `jenesis.repository.walk=<name>` selects one; Optional.empty() is the unselected sentinel.
        surfaces.add(Surface.root("build.jenesis.repository.walk.WalkProvider", Policy.OPTIONAL_UNIQUE));
        // Every discovered consumer sees every pass (WalkConsumer.discovered()).
        surfaces.add(Surface.root("build.jenesis.repository.walk.WalkConsumer", Policy.ALL));

        // --- gc / importer / posture / observation / ui -------------------------------------------------------
        // `jenesis.repository.gc=<name>` selects one; Optional.empty() is the unselected sentinel.
        surfaces.add(Surface.root("build.jenesis.repository.gc.GarbageCollectorProvider", Policy.OPTIONAL_UNIQUE));
        // A keyed catalogue of import sources; the import edge looks one up by name, all are advertised.
        surfaces.add(Surface.root("build.jenesis.repository.importer.ImportSourceProvider", Policy.ALL));
        // Every advisor folds into one PostureReport.
        surfaces.add(Surface.root("build.jenesis.repository.posture.SafetyAdvisor", Policy.ALL));
        // Every source folds into one ObservabilityReport.
        surfaces.add(Surface.root("build.jenesis.repository.observation.ObservabilitySource", Policy.ALL));
        // Every discovered panel is rendered.
        surfaces.add(Surface.root("build.jenesis.repository.ui.Panel", Policy.ALL));

        // --- server-spi --------------------------------------------------------------------------------------
        // Every contributor's keys are merged into /api/capabilities.
        surfaces.add(Surface.root("build.jenesis.repository.server.spi.CapabilityContributor", Policy.ALL));
        // Presence alone claims the import edge from the free controller: any active provider claims it, no provider
        // (or every provider configured off) means the free edge is served, and there is no selection key at all - so
        // it is the degenerate additive case, counted through installedNames, not a named singleton (T-101b corrected
        // the recorded policy: OPTIONAL_UNIQUE would key it to a resolution primitive it has no product for).
        surfaces.add(Surface.root("build.jenesis.repository.server.spi.ImportEdgeProvider", Policy.ALL));
        // `jenesis.repository.rate-limiter=<name>` selects one; RateLimiter.NONE is the unselected sentinel.
        surfaces.add(Surface.root("build.jenesis.repository.server.spi.RateLimiterProvider", Policy.OPTIONAL_UNIQUE));
        // `jenesis.repository.token-exchange=<name>` selects one; TokenExchange.NONE is the unselected sentinel.
        surfaces.add(Surface.root("build.jenesis.repository.server.spi.TokenExchangeProvider", Policy.OPTIONAL_UNIQUE));
        // `jenesis.repository.key-usage=<name>` selects one; KeyUsageTracker.NONE is the unselected sentinel.
        surfaces.add(Surface.root("build.jenesis.repository.server.spi.KeyUsageTrackerProvider",
                Policy.OPTIONAL_UNIQUE));

        return List.copyOf(surfaces);
    }

    /**
     * The role-split bases whose sub-interfaces must themselves be inventoried, keyed by the simple name a new role
     * would {@code extends}. A new {@code interface X extends PublicationObserver} that is not a declared role
     * surface fails {@link #every_sub_interface_of_a_role_split_service_is_inventoried()} - which is exactly how a
     * second pre-commit/after-commit split would otherwise slip into the census as one homogeneous family. The
     * format capabilities ({@code ProxyFormat} and friends) do not extend {@code RepositoryFormat}, so they carry no
     * marker and are declared explicitly above.
     */
    private static final Map<String, String> ROLE_MARKERS = Map.of(
            "build.jenesis.repository.store.PublicationObserver", "PublicationObserver");

    /**
     * Seeded doc waivers: an SPI surface whose Contract block is not written yet, each with the reason it is parked
     * and the Phase-3 ticket that burns it down. An entry must name a live, core-owned inventory surface whose
     * interface really lacks the block - a stale entry fails
     * {@link #the_contract_allowlist_stays_live_and_shrinking()}, so this list cannot outlive the work.
     */
    private static final Map<String, String> UNDOCUMENTED = undocumented();

    private static Map<String, String> undocumented() {
        Map<String, String> allow = new LinkedHashMap<>();

        // --- T-301a: store, format and walk SPIs ---
        // ArtifactStoreProvider and TenantsProvider burnt down by T-101b, which migrated both onto the shared
        // Providers primitives and wrote their numbered blocks (TenantsProvider's silent selected-miss fixed there).
        // PublicationObserver burnt down by T-104a, which made the publish commit point singular (Publication.commit)
        // and could therefore state the crash windows and the delivery class exactly: best-effort, repaired by the
        // full walk. T-107 may only strengthen that by proving a pre-commit intent machine at every crash point.
        allow.put("build.jenesis.repository.store.PublishInterceptor",
                "T-301a: the pre-commit sub-contract - chain ordering, disposition strength, per-method failure "
                        + "semantics (verdict legs propagate, inherited observer legs are contained) and withheld "
                        + "read-side purity; lands with the T-205 interceptor kit");
        // RepositoryFormat, ProxyFormat and ArtifactLayout burnt down by T-202a, which built the format contract kit
        // (source/format/testkit + test/format/contract) and could therefore state the clauses it now asserts:
        // HEAD-from-metadata, traversal refusal at the format seam, withhold-on-enumeration, proxy integrity and
        // streaming, and the determinism a generated index's revalidation rests on.
        allow.put("build.jenesis.repository.format.RepositoryImporter",
                "T-301a: streaming transfer, resumability and error classification, shared with the T-203 importer "
                        + "contract extension");
        // FetcherProvider and WalkProvider burnt down by T-101b together with their migration onto optionalUnique.
        allow.put("build.jenesis.repository.format.java.bridge.ModuleView",
                "T-301b: read purity and determinism across discovery order for the Maven bridge's rendering");
        // WalkConsumer burnt down by T-204, which built the walk-consumer contract kit (source/walk/testkit +
        // test/walkconsumer) and could therefore state the clauses it now asserts: the cursor commit as the commit
        // point, the three delivery classes and what a crash-resume converges for each, and the flush hook that makes
        // a batching consumer safe rather than lossy.

        // --- T-301b: server-spi, importer, observers and the remaining SPIs ---
        // GarbageCollectorProvider burnt down by T-101b together with its migration onto optionalUnique.
        allow.put("build.jenesis.repository.importer.ImportSourceProvider",
                "T-301b: resumability, streaming transfer, error classification and credential self-skip - the "
                        + "behavioural rows T-203 turns into fixtures");
        allow.put("build.jenesis.repository.posture.SafetyAdvisor",
                "T-301b: default-deny semantics and read purity (an advisor renders stored state, never probes)");
        allow.put("build.jenesis.repository.ui.Panel",
                "T-301b: read purity, rendering determinism and tenant scoping of a console panel");
        allow.put("build.jenesis.repository.server.spi.CapabilityContributor",
                "T-301b: key ownership, duplicate-key fail-fast on merge and the zero-contributor byte-identical "
                        + "guarantee; lands with the T-208 contributor suite");
        // RateLimiterProvider, TokenExchangeProvider and KeyUsageTrackerProvider burnt down by T-101b together with
        // their migration onto optionalUnique - the blocks the waivers deferred "post T-101" are written.

        return Map.copyOf(allow);
    }

    private static final Pattern USES = Pattern.compile("\\buses\\s+([\\w.$]+)\\s*;");
    private static final Pattern PROVIDES = Pattern.compile(
            "\\bprovides\\s+([\\w.$]+)\\s+with\\s+([^;]+);", Pattern.DOTALL);
    private static final Pattern MODULE = Pattern.compile("\\bmodule\\s+([\\w.]+)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern COMMENTS = Pattern.compile("//[^\\r\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern SUB_INTERFACE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:sealed\\s+|non-sealed\\s+)?interface\\s+(\\w+)[^{]*?\\bextends\\s+([^{]+)\\{",
            Pattern.DOTALL);

    @Test
    void every_declared_service_carries_inventory_metadata() throws IOException {
        Graph graph = graph(sourceRoot());
        assertThat(graph.services()).as("the module-info scan found SPI clauses - the check is not vacuous")
                .isNotEmpty();

        Set<String> roots = rootSurfaces().keySet();
        List<String> unknown = graph.services().stream()
                .filter(service -> !roots.contains(service))
                .map(service -> "  - " + service
                        + "  (uses: " + graph.consumers().getOrDefault(service, Set.of())
                        + ", provides: " + graph.declarations().getOrDefault(service, List.of()).size() + ")")
                .toList();

        assertThat(unknown)
                .as("these services are named by a `uses` or `provides` clause but carry no entry in the executable "
                        + "SPI inventory. A new extension point must declare its owner and its selection policy "
                        + "(ALL / OPTIONAL_UNIQUE / NAMED_UNIQUE / EXCLUSIVE_WITH_DEFAULT) in INVENTORY - T-101's "
                        + "resolution primitives and T-001's census are keyed off that metadata - and then either "
                        + "document its Contract block or park it in UNDOCUMENTED with a reason.%n%s",
                        String.join(System.lineSeparator(), unknown))
                .isEmpty();
    }

    @Test
    void every_inventory_surface_is_live() throws IOException {
        Path sourceRoot = sourceRoot();
        Graph graph = graph(sourceRoot);
        Map<String, Path> sources = interfaceSources(sourceRoot);
        Map<String, Set<String>> roles = rolesByProvider(graph, sources);

        List<String> stale = new ArrayList<>();
        for (Surface surface : INVENTORY) {
            if (surface.base() == null) {
                if (!graph.services().contains(surface.service())) {
                    stale.add("  - " + surface.service() + "  (no `uses` and no `provides` clause names it any more)");
                }
                continue;
            }
            if (rootSurfaces().get(surface.base()) == null) {
                stale.add("  - " + surface.service() + "  (its base " + surface.base() + " is not an inventoried "
                        + "service)");
            } else if (!roles.getOrDefault(surface.service(), Set.of()).isEmpty()) {
                continue;   // at least one provider in this repository implements the role
            } else if (!sources.containsKey(surface.service())) {
                stale.add("  - " + surface.service() + "  (no provider here implements it and this repository does "
                        + "not own its interface source)");
            }
        }

        assertThat(stale)
                .as("these inventory surfaces are stale - the SPI they describe is gone. Remove the entry (and its "
                        + "UNDOCUMENTED waiver) so the inventory tracks the module graph rather than rotting beside "
                        + "it.%n%s", String.join(System.lineSeparator(), stale))
                .isEmpty();
    }

    @Test
    void every_role_split_provider_resolves_to_its_source() throws IOException {
        Path sourceRoot = sourceRoot();
        Graph graph = graph(sourceRoot);
        Map<String, Path> sources = interfaceSources(sourceRoot);

        List<String> unresolved = new ArrayList<>();
        for (String base : roleSplitBases()) {
            for (Declaration declaration : graph.declarations().getOrDefault(base, List.of())) {
                if (!sources.containsKey(declaration.provider())) {
                    unresolved.add("  - " + declaration.provider() + "  (declared by " + declaration.descriptor()
                            + " for " + base + ")");
                }
            }
        }

        assertThat(unresolved)
                .as("these providers of a role-split SPI have no source file under source/, so keying them to a role "
                        + "interface (PublishInterceptor, ProxyFormat, ...) would silently key them to nothing. Role "
                        + "attribution must never be vacuous: a provider with opposite failure semantics that lands "
                        + "in the base family is driven through the wrong contract kit.%n%s",
                        String.join(System.lineSeparator(), unresolved))
                .isEmpty();

        // ... and the keying really fires: if the scan stopped recognising `implements` clauses every provider would
        // silently collapse into its base family and every role surface would read as unimplemented.
        long declared = roleSplitBases().stream()
                .mapToLong(base -> graph.declarations().getOrDefault(base, List.of()).size()).sum();
        if (declared > 0) {
            Map<String, Set<String>> roles = rolesByProvider(graph, sources);
            assertThat(roles.values().stream().mapToLong(Set::size).sum())
                    .as("%d providers of a role-split SPI are declared, yet none was keyed to any role surface - the "
                            + "role scan has gone blind and the census would treat providers with opposite failure "
                            + "semantics as one homogeneous family. Keyed roles: %s", declared, roles)
                    .isGreaterThan(0);
        }
    }

    @Test
    void every_sub_interface_of_a_role_split_service_is_inventoried() throws IOException {
        Path sourceRoot = sourceRoot();
        Map<String, Surface> declared = INVENTORY.stream()
                .collect(Collectors.toMap(Surface::service, surface -> surface, (first, _) -> first, TreeMap::new));

        List<String> undeclared = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(SpiContractPrincipleTest::isJava)::iterator) {
                String body = COMMENTS.matcher(Files.readString(file)).replaceAll("");
                Matcher packageName = PACKAGE.matcher(body);
                if (!packageName.find()) {
                    continue;
                }
                Matcher matcher = SUB_INTERFACE.matcher(body);
                while (matcher.find()) {
                    Set<String> supertypes = simpleNames(matcher.group(2));
                    for (Map.Entry<String, String> marker : ROLE_MARKERS.entrySet()) {
                        if (!supertypes.contains(marker.getValue())) {
                            continue;
                        }
                        String service = packageName.group(1) + "." + matcher.group(1);
                        Surface surface = declared.get(service);
                        if (surface == null || !marker.getKey().equals(surface.base())) {
                            undeclared.add("  - " + service + "  (extends " + marker.getValue() + ", so it rides the "
                                    + "single `uses " + marker.getKey() + "` clause)");
                        }
                    }
                }
            }
        }

        assertThat(undeclared)
                .as("these interfaces split the role of an inventoried SPI but are not inventoried as distinct "
                        + "contract surfaces. A sub-interface discovered through its base's clause carries its own - "
                        + "often opposite - contract (a pre-commit fail-closed screen beside a contained after-commit "
                        + "observer); add it to INVENTORY with its base and policy so the census keys each provider "
                        + "by the role it actually implements.%n%s", String.join(System.lineSeparator(), undeclared))
                .isEmpty();
    }

    @Test
    void every_spi_documents_its_contract_or_carries_a_reasoned_waiver() throws IOException {
        Path sourceRoot = sourceRoot();
        Map<String, Path> sources = interfaceSources(sourceRoot);

        List<String> missing = new ArrayList<>();
        for (Surface surface : INVENTORY) {
            if (surface.home() != Home.FREE_CORE) {
                continue;
            }
            Path source = sources.get(surface.service());
            if (source == null) {
                missing.add("  - " + surface.service() + "  (no interface source found under source/ - the core "
                        + "is declared its owner, so either the entry is misattributed or the SPI moved)");
                continue;
            }
            if (documentsContract(Files.readString(source), surface.simpleName())
                    || UNDOCUMENTED.containsKey(surface.service())) {
                continue;
            }
            missing.add("  - " + surface.service() + "  (" + sourceRoot.getParent().relativize(source) + ")");
        }

        assertThat(missing)
                .as("these SPI surfaces carry no Contract javadoc block. Every SPI documents its contract in a "
                        + "dedicated final javadoc block titled Contract (`<h2>Contract</h2>`, or a `Contract:` "
                        + "heading) covering whichever of the thirteen clauses apply - thread-safety, "
                        + "idempotency/replay, absence sentinel, selection failure, streaming, tenant scoping, error "
                        + "visibility, read purity, staleness, lifecycle/ownership, ordering/concurrency, bounded "
                        + "work/cancellation, durability/delivery. Write the block, or park the SPI in UNDOCUMENTED "
                        + "with the reason and the Phase-3 ticket that burns it down.%n%s",
                        String.join(System.lineSeparator(), missing))
                .isEmpty();
    }

    @Test
    void the_contract_allowlist_stays_live_and_shrinking() throws IOException {
        Path sourceRoot = sourceRoot();
        Map<String, Path> sources = interfaceSources(sourceRoot);
        Map<String, Surface> declared = INVENTORY.stream()
                .collect(Collectors.toMap(Surface::service, surface -> surface, (first, _) -> first, TreeMap::new));

        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> waiver : UNDOCUMENTED.entrySet()) {
            Surface surface = declared.get(waiver.getKey());
            if (surface == null) {
                stale.add("  - " + waiver.getKey() + "  (waived, but no such surface is in the inventory)");
                continue;
            }
            if (surface.home() != Home.FREE_CORE) {
                stale.add("  - " + waiver.getKey() + "  (waived here, but the inventory attributes it to "
                        + surface.home() + " - only the owning repository may waive its own SPI's block)");
                continue;
            }
            if (waiver.getValue().isBlank()) {
                stale.add("  - " + waiver.getKey() + "  (waived without a reason)");
                continue;
            }
            Path source = sources.get(waiver.getKey());
            if (source == null) {
                stale.add("  - " + waiver.getKey() + "  (waived, but its interface source is gone)");
            } else if (documentsContract(Files.readString(source), surface.simpleName())) {
                stale.add("  - " + waiver.getKey() + "  (its Contract block has landed - drop the waiver)");
            }
        }

        assertThat(stale)
                .as("these Contract-block waivers are stale. The seeded allowlist is a burn-down list, not a "
                        + "parking lot: an entry whose SPI is gone, whose block has landed, or which carries no "
                        + "reason must be removed in the same change.%n%s", String.join(System.lineSeparator(), stale))
                .isEmpty();
    }

    @Test
    void every_optional_unique_spi_declares_at_most_one_provider() throws IOException {
        Graph graph = graph(sourceRoot());

        List<String> ambiguous = new ArrayList<>();
        for (Surface surface : INVENTORY) {
            if (surface.policy() != Policy.OPTIONAL_UNIQUE) {
                continue;
            }
            List<Declaration> declarations = graph.declarations().getOrDefault(surface.service(), List.of());
            if (declarations.size() > 1) {
                ambiguous.add("  - " + surface.service() + "  -> "
                        + declarations.stream().map(Declaration::provider).sorted().toList());
            }
        }

        assertThat(ambiguous)
                .as("these SPIs are inventoried OPTIONAL_UNIQUE - exactly one enabled provider resolves - yet this "
                        + "repository declares more than one, so a default deployment that enables both now fails at "
                        + "resolution (Providers.optionalUnique refuses to pick a discovery-order winner) until an "
                        + "operator disambiguates with jenesis.repository.<spi>=<name>. Catching that here, at build "
                        + "time, is cheaper than catching it at boot: either the second provider is a mistake, or the "
                        + "SPI must ship a documented default selection.%n%s",
                        String.join(System.lineSeparator(), ambiguous))
                .isEmpty();
    }

    @Test
    void the_inventory_is_internally_consistent() {
        List<String> broken = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Surface surface : INVENTORY) {
            if (!seen.add(surface.service())) {
                broken.add("  - duplicate inventory entry for " + surface.service());
            }
            if (surface.base() != null && rootSurfaces().get(surface.base()) == null) {
                broken.add("  - " + surface.service() + " rides " + surface.base() + ", which is not a root surface");
            }
        }
        for (String base : ROLE_MARKERS.keySet()) {
            if (rootSurfaces().get(base) == null) {
                broken.add("  - ROLE_MARKERS names " + base + ", which is not a root surface");
            }
        }
        assertThat(broken)
                .as("the executable inventory contradicts itself%n%s", String.join(System.lineSeparator(), broken))
                .isEmpty();
    }

    /** The root surfaces (services a clause names directly), keyed by service name. */
    private static Map<String, Surface> rootSurfaces() {
        return INVENTORY.stream().filter(surface -> surface.base() == null)
                .collect(Collectors.toMap(Surface::service, surface -> surface, (first, _) -> first, TreeMap::new));
    }

    /** The services whose providers are keyed by a role interface rather than by the clause alone. */
    private static Set<String> roleSplitBases() {
        return INVENTORY.stream().map(Surface::base).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Every role surface mapped to the provider classes that implement it. A provider is keyed to a role when its
     * own source declares the role among its supertypes, or names it as a {@code <Role>.class} literal in a
     * capability-declaring {@code signals()} method - the two shapes by which a provider states which role it
     * answers without a separate {@code provides} clause.
     */
    private static Map<String, Set<String>> rolesByProvider(Graph graph, Map<String, Path> sources)
            throws IOException {
        Map<String, Set<String>> keyed = new TreeMap<>();
        for (Surface surface : INVENTORY) {
            if (surface.base() != null) {
                keyed.put(surface.service(), new TreeSet<>());
            }
        }
        for (String base : roleSplitBases()) {
            for (Declaration declaration : graph.declarations().getOrDefault(base, List.of())) {
                Path source = sources.get(declaration.provider());
                if (source == null) {
                    continue;   // reported by every_role_split_provider_resolves_to_its_source
                }
                Set<String> capabilities = capabilities(Files.readString(source), simpleName(declaration.provider()));
                for (Surface surface : INVENTORY) {
                    if (base.equals(surface.base()) && capabilities.contains(surface.simpleName())) {
                        keyed.get(surface.service()).add(declaration.provider());
                    }
                }
            }
        }
        return keyed;
    }

    /** The role names a provider class declares: its own supertypes plus the {@code <Role>.class} literals of a
     *  {@code signals()} capability declaration. */
    private static Set<String> capabilities(String body, String simpleName) {
        String stripped = COMMENTS.matcher(body).replaceAll("");
        Set<String> names = new TreeSet<>();
        Matcher declaration = Pattern.compile(
                        "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)?(?:class|record|enum|interface)\\s+"
                                + Pattern.quote(simpleName) + "\\b([^{]*)\\{", Pattern.DOTALL)
                .matcher(stripped);
        if (declaration.find()) {
            names.addAll(simpleNames(declaration.group(1)));
        }
        int signals = stripped.indexOf("signals()");
        if (signals >= 0) {
            String tail = stripped.substring(signals);
            int end = tail.indexOf("\n    }");
            Matcher literal = Pattern.compile("(\\w+)\\.class").matcher(end < 0 ? tail : tail.substring(0, end));
            while (literal.find()) {
                names.add(literal.group(1));
            }
        }
        return names;
    }

    /** The simple type names in a supertype list, ignoring generics, keywords and package qualifiers. */
    private static Set<String> simpleNames(String types) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile("[\\w.]+").matcher(types.replaceAll("<[^>]*>", ""));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.equals("implements") || token.equals("extends") || token.equals("permits")) {
                continue;
            }
            names.add(simpleName(token));
        }
        return names;
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** Parses every source {@code module-info.java} into the {@code uses} / {@code provides} graph. Multiline
     *  provider lists are handled, and comments are stripped so a clause quoted in javadoc never counts. */
    private static Graph graph(Path sourceRoot) throws IOException {
        Map<String, Set<String>> consumers = new TreeMap<>();
        Map<String, List<Declaration>> declarations = new TreeMap<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.getFileName().toString().equals("module-info.java"))::iterator) {
                String body = COMMENTS.matcher(Files.readString(file)).replaceAll("");
                Matcher module = MODULE.matcher(body);
                String name = module.find() ? module.group(1) : file.toString();
                String descriptor = sourceRoot.getParent().relativize(file).toString();
                Matcher uses = USES.matcher(body);
                while (uses.find()) {
                    consumers.computeIfAbsent(uses.group(1), _ -> new TreeSet<>()).add(name);
                }
                Matcher provides = PROVIDES.matcher(body);
                while (provides.find()) {
                    for (String provider : provides.group(2).split(",")) {
                        declarations.computeIfAbsent(provides.group(1), _ -> new ArrayList<>())
                                .add(new Declaration(provides.group(1), provider.strip(), name, descriptor));
                    }
                }
            }
        }
        return new Graph(Map.copyOf(consumers), Map.copyOf(declarations));
    }

    /** Every top-level type under {@code source/}, keyed by its fully qualified name, so a service or provider name
     *  resolves to the file that declares it. */
    private static Map<String, Path> interfaceSources(Path sourceRoot) throws IOException {
        Map<String, Path> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(SpiContractPrincipleTest::isJava)::iterator) {
                Matcher packageName = PACKAGE.matcher(Files.readString(file));
                if (packageName.find()) {
                    String simple = file.getFileName().toString();
                    sources.put(packageName.group(1) + "." + simple.substring(0, simple.length() - ".java".length()),
                            file);
                }
            }
        }
        return Map.copyOf(sources);
    }

    /** Whether the type javadoc immediately preceding {@code interface <simpleName>} carries the Contract block. */
    private static boolean documentsContract(String body, String simpleName) {
        Matcher declaration = Pattern.compile(
                        "(?m)^\\s*(?:public\\s+)?(?:sealed\\s+|non-sealed\\s+)?interface\\s+"
                                + Pattern.quote(simpleName) + "\\b")
                .matcher(body);
        if (!declaration.find()) {
            return false;
        }
        int close = body.lastIndexOf("*/", declaration.start());
        int open = close < 0 ? -1 : body.lastIndexOf("/**", close);
        if (open < 0) {
            return false;
        }
        String javadoc = body.substring(open, close);
        return javadoc.contains("<h2>Contract</h2>") || javadoc.contains("Contract:");
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java")
                && !path.getFileName().toString().equals("module-info.java");
    }

    /** The module sources directory ({@code <repo>/source}). The build runs the test JVM from the repository root,
     *  so this walks up from the working directory to the first ancestor holding {@code source/} beside
     *  {@code build/jenesis}. Fails loudly if the tree is not reachable, so the check never passes vacuously. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("source")) && Files.isDirectory(dir.resolve("build/jenesis"))) {
                return dir.resolve("source");
            }
        }
        throw new AssertionError("could not locate the core repo root (an ancestor holding source/ beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }
}
