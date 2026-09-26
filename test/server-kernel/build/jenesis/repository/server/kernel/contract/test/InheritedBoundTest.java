package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behavioural half of the inherited-bound rule: an SPI's paged or streaming {@code default} implemented over a
 * whole-list abstract sibling <b>fails visibly</b> at a stated ceiling instead of degrading silently on a
 * deployment-sized ledger.
 *
 * <p>Five SPIs shipped that shape - {@code Findings} (paged and Visitor), {@code HealthLedger},
 * {@code RepositoryInventory}, {@code DependentsQuery} and {@code AuditTrail} (paged and streaming) - each
 * documenting itself with the sentence this rule was written about: "the default is correct for a simple
 * implementation; the store overrides it". That sentence describes an implementation nobody ships while the shipped one
 * overrides, so the default's cost is invisible until a plug-in author inherits it: every one of the seven legs below
 * <em>silently buffered the whole ledger and answered</em> before this test existed, and every one of these assertions
 * fails on that behaviour. They now refuse past {@link ArtifactStore#MAX_INHERITED_CHILDREN} - the one ceiling, reused
 * rather than restated - naming the inheriting class, the leg, the whole-list sibling, the size and the override that
 * fixes it.
 *
 * <p>Three properties are asserted at each leg, because a bound that only ever throws is as untrustworthy as one that
 * never does: it <b>serves</b> at the ceiling, it <b>refuses</b> one row past it, and the refusal <b>names</b> the
 * implementation and the remedy rather than surfacing as an anonymous {@code OutOfMemoryError} three layers up. The
 * streaming legs additionally prove the refusal lands <em>before</em> the first row is emitted, so a consumer never
 * half-processes an export that is about to die.
 *
 * <p>The eighth site is not a bound but a repair: {@link DependentsQuery#built()} exists to answer "has the index ever
 * been swept" and its javadoc calls it "a single small-object existence probe, never a scan", while its default
 * answered by materialising the whole coordinate set and asking {@code isEmpty()}. It now reads the sweep's own
 * completion stamp ({@link DependentsQuery#builtAt()}), which is both what the javadoc always claimed and the only
 * evidence that can tell a swept-empty index from a never-built one.
 */
class InheritedBoundTest {

    /** The one ceiling, read from one place rather than restated here - if it moves, every leg moves with it. */
    private static final int CEILING = ArtifactStore.MAX_INHERITED_CHILDREN;

    // --- Findings: the paged console read and the Visitor stream over the whole-ledger read ------------------------

    @Test
    void the_findings_page_default_serves_at_the_ceiling_and_refuses_past_it() {
        assertThatCode(() -> ledgerOf(CEILING).all(Findings.Filter.none(), 0, 20))
                .as("a ledger at the ceiling is what the inherited fallback is for: it still answers")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> ledgerOf(CEILING + 1).all(Findings.Filter.none(), 0, 20))
                .as("one row past the ceiling the paged default must refuse, not buffer the ledger and answer")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all(Filter, int, int)")
                .hasMessageContaining("all(Filter)")
                .hasMessageContaining(String.valueOf(CEILING + 1))
                .hasMessageContaining("Override");
    }

    @Test
    void the_findings_visitor_default_refuses_before_it_emits_a_row() throws IOException {
        List<Findings.Located> seen = new ArrayList<>();
        ledgerOf(CEILING).all(Findings.Filter.none(), seen::add);
        assertThat(seen).as("at the ceiling the Visitor form still delivers every matched row").hasSize(CEILING);

        List<Findings.Located> aborted = new ArrayList<>();
        assertThatThrownBy(() -> ledgerOf(CEILING + 1).all(Findings.Filter.none(), aborted::add))
                .as("the streaming leg promises never to materialise the matched set; past the ceiling the "
                        + "inherited body that does exactly that refuses instead")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all(Filter, Visitor)");
        assertThat(aborted)
                .as("and it refuses BEFORE the first row, so a consumer never half-processes a doomed pass")
                .isEmpty();
    }

    // --- HealthLedger: the rank-index rebuild's streaming primitive ------------------------------------------------

    @Test
    void the_health_ledger_visitor_default_refuses_past_the_ceiling() throws IOException {
        List<HealthLedger.Located> seen = new ArrayList<>();
        healthLedgerOf(CEILING).all(seen::add);
        assertThat(seen).as("at the ceiling the fold still sees every scored coordinate").hasSize(CEILING);

        assertThatThrownBy(() -> healthLedgerOf(CEILING + 1).all(_ -> {
        }))
                .as("its own javadoc calls this the primitive a rank-index rebuild folds over 'so a repository with "
                        + "millions of scored coordinates never buffers them all' - the inherited body buffers them "
                        + "all, so past the ceiling it must say so")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all(LedgerVisitor)")
                .hasMessageContaining("all()")
                .hasMessageContaining("Override");
    }

    // --- RepositoryInventory: the grouped release stream the retention plan drives ---------------------------------

    @Test
    void the_inventory_grouped_stream_default_refuses_past_the_ceiling() throws IOException {
        List<Release> seen = new ArrayList<>();
        inventoryOf(CEILING).releases(seen::add);
        assertThat(seen).as("at the ceiling the grouped delivery still visits every release").hasSize(CEILING);

        assertThatThrownBy(() -> inventoryOf(CEILING + 1).releases(_ -> {
        }))
                .as("grouped delivery exists so a retention plan holds one coordinate's versions instead of the "
                        + "repository's whole release list; the inherited body holds the list, so it refuses")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("releases(ReleaseVisitor)")
                .hasMessageContaining("releases()");
    }

    // --- DependentsQuery: the cursor page over the whole reverse-dependency key set --------------------------------

    @Test
    void the_dependents_page_default_refuses_past_the_ceiling() throws IOException {
        assertThat(indexOf(CEILING).coordinates(null, 5).coordinates())
                .as("at the ceiling the inherited page still answers its slice").hasSize(5);

        assertThatThrownBy(() -> indexOf(CEILING + 1).coordinates(null, 5))
                .as("the default sorts and slices the entire graph's key set per page, so a caller paging N pages "
                        + "materialises it N times - past the ceiling it refuses instead")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coordinates(String, int)")
                .hasMessageContaining("coordinates()")
                .hasMessageContaining(String.valueOf(CEILING + 1));
    }

    // --- AuditTrail: the CSV export's row sink and the paged console read over the whole trail ---------------------

    @Test
    void the_audit_trail_stream_default_refuses_before_it_emits_a_row() throws IOException {
        List<AuditTrail.Event> exported = new ArrayList<>();
        trailOf(CEILING).stream("acme", null, null, null, exported::add);
        assertThat(exported).as("at the ceiling the export still writes every row").hasSize(CEILING);

        List<AuditTrail.Event> aborted = new ArrayList<>();
        assertThatThrownBy(() -> trailOf(CEILING + 1).stream("acme", null, null, null, aborted::add))
                .as("the streaming form exists so the whole unrotated trail never lands in heap at once; the "
                        + "inherited body puts it there to stream it, so past the ceiling it refuses")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream(String, Instant, Instant, String, Sink)");
        assertThat(aborted)
                .as("and it refuses before the first CSV row reaches the response")
                .isEmpty();
    }

    @Test
    void the_audit_trail_page_default_refuses_past_the_ceiling() {
        assertThatCode(() -> trailOf(CEILING).query("acme", null, null, null, 0, 25))
                .as("at the ceiling the paged console read still answers").doesNotThrowAnyException();

        assertThatThrownBy(() -> trailOf(CEILING + 1).query("acme", null, null, null, 0, 25))
                .as("byte-for-byte the inherited-default shape, one SPI over: a page served by materialising the whole trail")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query(String, Instant, Instant, String, int, int)")
                .hasMessageContaining("query(String, Instant, Instant, String)")
                .hasMessageContaining("Override");
    }

    // --- every refusal names the implementation that inherited it --------------------------------------------------

    @Test
    void a_refusal_names_the_implementation_the_operator_has_to_fix() {
        assertThatThrownBy(() -> trailOf(CEILING + 1).query("acme", null, null, null, 0, 25))
                .as("a bound that fails visibly names the class an operator or plug-in author must change - an "
                        + "anonymous heap exhaustion three frames up is precisely the silent degrade this replaces")
                .hasMessageContaining(trailOf(0).getClass().getName())
                .hasMessageContaining(CEILING + "-row bound");
    }

    // --- the eighth site: an existence probe that reads a marker, not the graph -------------------------------------

    @Test
    void the_built_probe_reads_the_sweep_marker_and_never_the_coordinate_set() throws IOException {
        DependentsQuery swept = new DependentsQuery() {
            @Override
            public List<String> dependents(String coordinate) {
                return List.of();
            }

            @Override
            public List<String> coordinates() {
                throw new AssertionError("built() must not read the coordinate set - its javadoc calls it 'a single "
                        + "small-object existence probe, never a scan', and a scan cannot answer the question "
                        + "anyway: a swept-empty index and a never-built one look identical from the data");
            }

            @Override
            public Set<String> reachable(Collection<String> coordinates) {
                return Set.of();                        // nothing recorded here; the probe under test is built()
            }

            @Override
            public Optional<Instant> builtAt() {
                return Optional.of(Instant.parse("2026-08-15T10:15:30Z"));
            }
        };

        assertThat(swept.built())
                .as("a committed sweep left a completion stamp, which is the whole answer")
                .isTrue();

        // The ambiguity the old default could not resolve: a sweep that committed an EMPTY index. Reading the
        // coordinate set answers 'false' there - a never-derived view served as an authoritative empty one, the
        // exact failure built() exists to prevent.
        DependentsQuery sweptEmpty = query(List.of(), Optional.of(Instant.parse("2026-08-15T10:15:30Z")));
        assertThat(sweptEmpty.built())
                .as("a swept-empty index is authoritative-empty, not never-built")
                .isTrue();

        // And the converse: an implementation that keeps no completion marker reads as not-yet-built, whatever its
        // data holds, so its surface says "not yet built" rather than presenting an underived view as whole.
        DependentsQuery unmarked = query(List.of("pkg:maven/com.example/app@1.0.0"), Optional.empty());
        assertThat(unmarked.built())
                .as("no completion stamp, so nothing proves a sweep ran - the conservative half of the ambiguity")
                .isFalse();
    }

    // --- the inheriting implementations: each answers ONLY the abstract legs, so it inherits every default ----------

    /** A findings ledger of {@code rows} matched findings that overrides nothing paged or streaming. */
    private static Findings ledgerOf(int rows) {
        Finding finding = Finding.of("CVE-2026-0001", "test", Finding.Kind.VULNERABILITY, "test", Severity.LOW,
                "a fixture row", Instant.parse("2026-08-15T10:15:30Z"));
        List<Findings.Located> located = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            located.add(new Findings.Located("maven", "com.example:app", index + ".0.0", finding));
        }
        List<Findings.Located> answer = List.copyOf(located);
        return new Findings() {

            @Override
            public void record(String ecosystem, String coordinate, String version, Finding row) {
                throw new UnsupportedOperationException("not exercised: this fixture is a read model");
            }

            @Override
            public void label(String ecosystem, String coordinate, String version, String source, String id,
                              Finding.Label label) {
                throw new UnsupportedOperationException("not exercised: this fixture is a read model");
            }

            @Override
            public void supersede(String ecosystem, String coordinate, String version, String source, String id,
                                  String supersededBy) {
                throw new UnsupportedOperationException("not exercised: this fixture is a read model");
            }

            @Override
            public List<Finding> of(String ecosystem, String coordinate, String version) {
                return List.of();
            }

            @Override
            public List<Located> all(Filter filter) {
                return answer;
            }
        };
    }

    /** A health ledger of {@code rows} scored coordinates that overrides neither the stream nor the paged read. */
    private static HealthLedger healthLedgerOf(int rows) {
        HealthSource.Health health = new HealthSource.Health("https://example.invalid/repo", 5.0, 5.0, 5.0, 5.0);
        List<HealthLedger.Located> located = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            located.add(new HealthLedger.Located("maven", "com.example:app" + index, health,
                    Instant.parse("2026-08-15T10:15:30Z")));
        }
        List<HealthLedger.Located> answer = List.copyOf(located);
        return new HealthLedger() {

            @Override
            public Optional<HealthSource.Health> health(String ecosystem, String coordinate) {
                return Optional.empty();
            }

            @Override
            public Freshness freshness() {
                return Freshness.NEVER;
            }

            @Override
            public void record(String ecosystem, String coordinate, HealthSource.Health scored, Instant scannedAt) {
                throw new UnsupportedOperationException("not exercised: this fixture is a read model");
            }

            @Override
            public List<Located> all() {
                return answer;
            }
        };
    }

    /** The list-backed inventory the SPI's own javadoc names as the default's audience, at {@code rows} releases. */
    private static RepositoryInventory inventoryOf(int rows) {
        List<Release> releases = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            releases.add(new Release("com.example:app", index + ".0.0", false, Instant.parse("2026-08-15T10:15:30Z")));
        }
        List<Release> answer = List.copyOf(releases);
        return new RepositoryInventory() {

            @Override
            public Collection<Release> releases() {
                return answer;
            }

            @Override
            public void evict(Release release) {
                throw new UnsupportedOperationException("not exercised: this fixture is a read model");
            }
        };
    }

    /** A reverse-dependency index of {@code rows} coordinates that inherits the cursor page. */
    private static DependentsQuery indexOf(int rows) {
        List<String> coordinates = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            coordinates.add("pkg:maven/com.example/app" + index + "@1.0.0");
        }
        return query(List.copyOf(coordinates), Optional.of(Instant.parse("2026-08-15T10:15:30Z")));
    }

    private static DependentsQuery query(List<String> coordinates, Optional<Instant> builtAt) {
        return new DependentsQuery() {

            @Override
            public List<String> dependents(String coordinate) {
                return List.of();
            }

            @Override
            public List<String> coordinates() {
                return coordinates;
            }

            /** An in-memory index answers the bounded probe from the set it already holds - the shape the SPI's
             *  javadoc names now that there is no materialising {@code default} to inherit. */
            @Override
            public Set<String> reachable(Collection<String> queried) {
                Set<String> want = new HashSet<>(queried);
                Set<String> hit = new HashSet<>();
                for (String coordinate : coordinates) {
                    String neutral = DependentsQuery.neutralise(coordinate);
                    if (want.contains(neutral)) {
                        hit.add(neutral);
                    }
                }
                return hit;
            }

            @Override
            public Optional<Instant> builtAt() {
                return builtAt;
            }
        };
    }

    /** An audit trail of {@code rows} recorded events that inherits both the paged and the streaming leg. */
    private static AuditTrail trailOf(int rows) {
        List<AuditTrail.Event> events = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            events.add(new AuditTrail.Event(Instant.parse("2026-08-15T10:15:30Z"), "actor", "publish",
                    "com.example:app:" + index));
        }
        List<AuditTrail.Event> answer = List.copyOf(events);
        return new AuditTrail() {

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void record(String tenant, String actor, String action, String target) {
                throw new UnsupportedOperationException("not exercised: this fixture is a read model");
            }

            @Override
            public List<Event> query(String tenant, Instant from, Instant to, String action) {
                return answer;
            }
        };
    }
}
