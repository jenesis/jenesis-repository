package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.ServableNames.Policy;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;

import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The screened enumeration - the composition that ends the disclosure class (C3): a surface that lists names and a
 * surface that screens them are the same call, so "list, then filter {@code withheld(...)}" can no longer lose its
 * second half. The suite pins the three properties that claim is made of:
 *
 * <ol>
 *   <li><b>Unforgettable.</b> A held name never reaches a sink, on <em>every</em> face the helper serves (served
 *       request paths, version folders, {@code blobs}-namespace pointer keys, and a pointer key mapped from a name in
 *       a different container), and the type structurally cannot be built without the seam - there is no public
 *       constructor, no factory without a {@link ServableNames}, and no name reaches a caller unscreened.</li>
 *   <li><b>Fail-closed, but never silently short.</b> A hostile name whose probe throws a {@link RuntimeException} is
 *       contained and dropped (one bad name cannot 500 a page, and is never disclosed); a real store failure
 *       propagates instead of handing back a listing that quietly lost names.</li>
 *   <li><b>Bounded per T-102a.</b> The take cap (disclosable names) and the scan cap (examined names) each answer
 *       {@link Traversal.Outcome#TRUNCATED} with a continuation cursor that resumes without skipping or repeating a
 *       name; the step bound still raises. A container whose names are all held is bounded by the scan cap, which the
 *       take cap alone would never reach.</li>
 * </ol>
 */
class ScreenedNamesTest {

    private static final String HASH = "a".repeat(64);
    private static final String HELD = "b".repeat(64);
    private static final String GONE = "c".repeat(64);

    // ---- the screen is applied on every face the helper serves --------------------------------------------------

    @Test
    void a_served_path_a_get_would_refuse_never_reaches_the_listing() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("publish/maven/g/a/1/live.jar", HASH);
        store.pointer("publish/maven/g/a/1/gone.jar", GONE);        // pointer to a blob that is not there
        store.pointer("publish/maven/g/a/1/held.jar", HASH);
        store.blob(HASH);
        Publication publication = new Publication(store, List.of(new Withholding("/maven/g/a/1/held.jar")));

        List<String> names = new ArrayList<>();
        Traversal.Result result = ScreenedNames
                .paths(new ServableNames(store, publication), Policy.HIDE_WITHHELD_AND_GONE)
                .scan(store, "publish/maven/g/a/1", (name, _) -> names.add(name));

        assertThat(names).as("the interceptor-held leaf and the blob-gone leaf are both absent, by name")
                .containsExactly("live.jar");
        assertThat(result.exhausted()).isTrue();
        assertThat(result.delivered()).as("delivered counts what was disclosed, not what was examined").isEqualTo(1);
        assertThat(result.steps()).as("steps counts the names examined - the work a screened scan does").isEqualTo(3);
    }

    @Test
    void a_held_version_folder_never_reaches_a_generated_version_index() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("publish/maven/g/a/1/a-1.jar", HASH);
        store.pointer("publish/maven/g/a/2/a-2.jar", HASH);
        store.pointer("publish/quarantine/maven/g/a/2/a-2.jar", HASH);   // the review-pointer hold convention
        store.blob(HASH);

        List<String> versions = new ArrayList<>();
        ScreenedNames.versionFolders(new ServableNames(store))
                .scan(store, "publish/maven/g/a", (name, _) -> versions.add(name));

        assertThat(versions).as("a version under review is not a version the index may name").containsExactly("1");
    }

    @Test
    void a_withheld_pointer_key_never_reaches_a_blobs_namespace_listing() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("pypi/project/files/live-1.0.whl", HASH);
        store.pointer("pypi/project/files/held-2.0.whl", HELD);
        store.blob(HASH);
        store.blob(HELD);
        Withheld.mark(store, HELD);

        List<String> files = new ArrayList<>();
        ScreenedNames.keys(new ServableNames(store), Policy.HIDE_WITHHELD)
                .scan(store, "pypi/project/files", (name, _) -> files.add(name));

        assertThat(files).containsExactly("live-1.0.whl");
    }

    @Test
    void a_version_index_beside_its_content_screens_the_key_the_name_maps_to() throws IOException {
        // The npm / NuGet / Cargo / Composer / RubyGems / CocoaPods layout: the enumerated container is an index of
        // version names, and the bytes live under a different key. The mapper says which key a name discloses; it
        // cannot say "none".
        ScreenStore store = new ScreenStore();
        store.pointer("npm/pkg/versions/1.0.0", HASH);
        store.pointer("npm/pkg/versions/2.0.0", HASH);
        store.pointer("npm/pkg/tarballs/pkg-1.0.0.tgz", HASH);
        store.pointer("npm/pkg/tarballs/pkg-2.0.0.tgz", HELD);
        store.blob(HASH);
        store.blob(HELD);
        Withheld.mark(store, HELD);

        List<String> versions = new ArrayList<>();
        ScreenedNames.keys(new ServableNames(store), Policy.HIDE_WITHHELD,
                        version -> "npm/pkg/tarballs/pkg-" + version + ".tgz")
                .scan(store, "npm/pkg/versions", (name, _) -> versions.add(name));

        assertThat(versions).as("the packument names only versions whose tarball a client could actually fetch")
                .containsExactly("1.0.0");
    }

    @Test
    void a_container_forwards_unscreened_and_the_sink_is_told_which_it_got() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("publish/maven/g/a/1/a-1.jar", HASH);     // 'a' is a container: its own leaves carry the screen
        store.pointer("publish/maven/g/held.jar", HELD);
        store.blob(HASH);
        Publication publication = new Publication(store, List.of(new Withholding("/maven/g/held.jar")));

        Map<String, Boolean> seen = new LinkedHashMap<>();
        ScreenedNames.paths(new ServableNames(store, publication), Policy.HIDE_WITHHELD_AND_GONE)
                .containers(childKey -> !store.list(childKey).isEmpty())
                .scan(store, "publish/maven/g", seen::put);

        assertThat(seen).as("the sub-listing is kept and declared a container; the held sibling leaf is gone")
                .containsExactly(Map.entry("a", true));
    }

    @Test
    void the_review_subtree_is_suppressed_at_the_served_root() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("publish/maven/g/a/1/a-1.jar", HASH);
        store.pointer("publish/quarantine/maven/g/a/2/a-2.jar", HASH);
        store.blob(HASH);

        List<String> namespaces = new ArrayList<>();
        ScreenedNames.paths(new ServableNames(store), Policy.HIDE_WITHHELD_AND_GONE)
                .containers(_ -> true)
                .scan(store, ServableNames.PUBLISHED, (name, _) -> namespaces.add(name));

        assertThat(namespaces).as("the one child of the served root that is stored but never served is not enumerated")
                .containsExactly("maven");
    }

    // ---- the screen cannot be forgotten, structurally -----------------------------------------------------------

    @Test
    void the_helper_cannot_be_built_without_the_seam_and_hands_out_no_unscreened_name() {
        assertThat(ScreenedNames.class.getConstructors())
                .as("a public constructor would let a caller assemble an enumeration with no seam behind it").isEmpty();

        List<Method> factories = Arrays.stream(ScreenedNames.class.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == ScreenedNames.class)
                .toList();
        assertThat(factories).as("the faces are the only way in, so there must be some").isNotEmpty();
        assertThat(factories)
                .as("every entry point takes the servable-name seam, so an unscreened enumeration is unrepresentable")
                .allMatch(method -> Arrays.asList(method.getParameterTypes()).contains(ServableNames.class));

        assertThat(Arrays.stream(ScreenedNames.class.getMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getDeclaringClass() == ScreenedNames.class)
                .map(Method::getName)
                .distinct())
                .as("the instance surface is configuration plus the two enumerations - nothing hands back a raw page")
                .containsExactlyInAnyOrder("scanning", "take", "containers", "scan", "any");
    }

    // ---- fail-closed, but never silently short ------------------------------------------------------------------

    @Test
    void one_hostile_name_is_dropped_rather_than_failing_or_disclosing_the_page() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("publish/raw/dir/live.txt", HASH);
        store.pointer("publish/raw/dir/\uD800hostile.txt", HASH);
        store.blob(HASH);
        store.hostile = true;                      // the backend cannot even resolve the unpaired-surrogate name

        List<String> names = new ArrayList<>();
        assertThatCode(() -> ScreenedNames.paths(new ServableNames(store), Policy.HIDE_WITHHELD_AND_GONE)
                .scan(store, "publish/raw/dir", (name, _) -> names.add(name)))
                .as("one unresolvable name must not fail the whole listing").doesNotThrowAnyException();
        assertThat(names).as("and it is never disclosed either - the containment is fail-CLOSED")
                .containsExactly("live.txt");
    }

    @Test
    void a_store_failure_in_the_screen_fails_the_listing_instead_of_serving_a_short_one() {
        ScreenStore store = new ScreenStore();
        store.pointer("publish/raw/dir/one.txt", HASH);
        store.pointer("publish/raw/dir/two.txt", HASH);
        store.blob(HASH);
        store.failing = true;                      // the store is down: no verdict can be rendered for any name

        List<String> names = new ArrayList<>();
        assertThatThrownBy(() -> ScreenedNames.paths(new ServableNames(store), Policy.HIDE_WITHHELD_AND_GONE)
                .scan(store, "publish/raw/dir", (name, _) -> names.add(name)))
                .as("a screen that cannot answer must fail the enumeration, not quietly drop every name it could not "
                        + "screen - a listing that lost names looks exactly like a listing of what is left")
                .isInstanceOf(IOException.class);
        assertThat(names).isEmpty();
    }

    // ---- bounded per T-102a --------------------------------------------------------------------------------------

    @Test
    void the_take_cap_answers_truncated_with_a_cursor_and_the_continuation_resumes_exactly() throws IOException {
        ScreenStore store = new ScreenStore();
        List<String> published = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String name = String.format("v%02d.whl", index);
            store.pointer("pypi/many/files/" + name, index % 2 == 0 ? HASH : HELD);
            if (index % 2 == 0) {
                published.add(name);              // the odd ones are held, so a page must over-fetch past them
            }
        }
        store.blob(HASH);
        store.blob(HELD);
        Withheld.mark(store, HELD);
        ScreenedNames screened = ScreenedNames.keys(new ServableNames(store), Policy.HIDE_WITHHELD)
                .scanning(BoundedChildren.bounded().page(3))
                .take(2);

        List<String> everything = new ArrayList<>();
        String cursor = null;
        Traversal.Result result;
        int pages = 0;
        do {
            result = screened.scan(store, "pypi/many/files", cursor, (name, _) -> everything.add(name));
            cursor = result.cursor().orElse(null);
            pages++;
            assertThat(result.delivered()).as("no page ever exceeds the take cap").isLessThanOrEqualTo(2);
        } while (result.truncated() && pages < 10);

        assertThat(everything).as("paging a screened enumeration sees every disclosable name exactly once")
                .containsExactlyElementsOf(published);
        assertThat(result.exhausted()).as("the walk ends provably drained, not at a cap").isTrue();
    }

    @Test
    void a_container_of_held_names_is_bounded_by_the_scan_cap_the_take_cap_could_never_reach() throws IOException {
        ScreenStore store = new ScreenStore();
        for (int index = 0; index < 100; index++) {
            store.pointer(String.format("pypi/held/files/v%03d.whl", index), HELD);
        }
        store.blob(HELD);
        Withheld.mark(store, HELD);

        List<String> names = new ArrayList<>();
        Traversal.Result result = ScreenedNames.keys(new ServableNames(store), Policy.HIDE_WITHHELD)
                .scanning(BoundedChildren.bounded().entries(10).page(5))
                .take(25)
                .scan(store, "pypi/held/files", (name, _) -> names.add(name));

        assertThat(names).isEmpty();
        assertThat(result.truncated())
                .as("nothing was disclosable, but the container is NOT drained - saying so is the whole point")
                .isTrue();
        assertThat(result.cursor()).contains("pypi/held/files/v009.whl");
        assertThat(result.steps()).as("the scan cap bounded the work at 10 examined names").isEqualTo(10);
    }

    @Test
    void the_take_cap_is_only_spent_when_a_further_disclosable_name_is_proven() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("publish/raw/pair/a.txt", HASH);
        store.pointer("publish/raw/pair/b.txt", HASH);
        store.blob(HASH);

        List<String> names = new ArrayList<>();
        Traversal.Result result = ScreenedNames.paths(new ServableNames(store), Policy.HIDE_WITHHELD_AND_GONE)
                .take(2)
                .scan(store, "publish/raw/pair", (name, _) -> names.add(name));

        assertThat(names).containsExactly("a.txt", "b.txt");
        assertThat(result.exhausted())
                .as("a page whose disclosable names exactly fill it is not truncated - there was nothing more")
                .isTrue();
    }

    @Test
    void the_step_bound_still_raises_because_it_has_no_safe_continuation() {
        ScreenStore store = new ScreenStore();
        for (int index = 0; index < 40; index++) {
            store.pointer(String.format("publish/raw/wide/v%03d.jar", index), HASH);
        }
        store.blob(HASH);

        assertThatThrownBy(() -> ScreenedNames.paths(new ServableNames(store), Policy.HIDE_WITHHELD_AND_GONE)
                .scanning(BoundedChildren.bounded().steps(2).page(5))
                .take(1_000)
                .scan(store, "publish/raw/wide", (_, _) -> { }))
                .isInstanceOf(TraversalException.class)
                .satisfies(raised ->
                        assertThat(((TraversalException) raised).reason()).isEqualTo(TraversalException.Reason.STEPS));
    }

    // ---- the membership question --------------------------------------------------------------------------------

    @Test
    void any_is_true_on_the_first_surviving_name_and_false_only_when_provably_none() throws IOException {
        ScreenStore store = new ScreenStore();
        store.pointer("oci/live/tags/1.0", "sha256:" + HASH);
        store.pointer("oci/dead/tags/1.0", "sha256:" + HELD);
        store.blob(HASH);
        store.blob(HELD);
        Withheld.mark(store, HELD);
        ScreenedNames screened = ScreenedNames.keys(new ServableNames(store), Policy.HIDE_WITHHELD);

        assertThat(screened.any(store, "oci/live/tags")).isTrue();
        assertThat(screened.any(store, "oci/dead/tags"))
                .as("every tag is held, and the scan proved it - a catalog must not list this image").isFalse();
        assertThat(screened.any(store, "oci/absent/tags")).as("an absent container is not an error").isFalse();
    }

    @Test
    void any_raises_rather_than_reporting_a_plausible_none_it_could_not_prove() throws IOException {
        ScreenStore store = new ScreenStore();
        for (int index = 0; index < 50; index++) {
            store.pointer(String.format("oci/wide/tags/v%03d", index), "sha256:" + HELD);
        }
        store.blob(HELD);
        Withheld.mark(store, HELD);

        assertThatThrownBy(() -> ScreenedNames.keys(new ServableNames(store), Policy.HIDE_WITHHELD)
                .scanning(BoundedChildren.bounded().entries(5).page(5))
                .any(store, "oci/wide/tags"))
                .as("a boolean has no continuation, so an unproven answer must be a visible failure")
                .isInstanceOf(TraversalException.class);
    }

    // ---- selection failure ---------------------------------------------------------------------------------------

    @Test
    void a_served_path_screen_aimed_outside_the_published_namespace_is_refused() {
        ScreenStore store = new ScreenStore();
        store.pointer("npm/pkg/tarballs/pkg-1.0.tgz", HASH);

        assertThatThrownBy(() -> ScreenedNames.paths(new ServableNames(store), Policy.HIDE_WITHHELD_AND_GONE)
                .scan(store, "npm/pkg/tarballs", (_, _) -> { }))
                .as("a derived request path outside publish/ would screen a different artifact than it discloses")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ServableNames.PUBLISHED);
    }

    @Test
    void a_non_positive_take_is_refused_rather_than_meaning_unbounded() {
        ScreenStore store = new ScreenStore();
        assertThatThrownBy(() -> ScreenedNames.keys(new ServableNames(store), Policy.HIDE_WITHHELD).take(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- doubles --------------------------------------------------------------------------------------------------

    /** An interceptor that withholds exactly the request paths it is constructed with - the free chain is empty, so
     *  this is how a test drives the interceptor leg of the screen without a compliance gate on the module path. */
    private static final class Withholding implements PublishInterceptor {

        private final Set<String> held;

        private Withholding(String... paths) {
            this.held = Set.of(paths);
        }

        @Override
        public boolean withheld(String path, ArtifactStore store) {
            return held.contains(path);
        }
    }

    /** An in-memory {@link ArtifactStore} over a flat key map - pointers carry their body, blobs and
     *  {@code withheld/} markers are presence-only, and immediate children are derived from the key set - with two
     *  injectable failures: {@link #hostile} makes a name a backend cannot resolve throw the way a real filesystem
     *  store throws on an unmappable path, and {@link #failing} makes every pointer read fail as a store outage
     *  would. */
    private static final class ScreenStore implements ArtifactStore {

        private final NavigableMap<String, byte[]> objects = new TreeMap<>();

        private boolean hostile;
        private boolean failing;

        void pointer(String key, String body) {
            objects.put(key, body.getBytes(StandardCharsets.UTF_8));
        }

        void blob(String hash) {
            objects.put("blobs/" + hash, new byte[0]);
        }

        /** The unresolvable-name simulation: exactly what {@code FilesystemArtifactStore.resolve} does when a key
         *  carries a character the platform encoding cannot map - an unchecked throw out of a plain read. */
        private void screenKey(String key) {
            if (hostile && key.chars().anyMatch(character -> Character.isSurrogate((char) character))) {
                throw new java.nio.file.InvalidPathException(key, "unmappable name");
            }
        }

        @Override
        public boolean exists(String key) {
            screenKey(key);
            return objects.containsKey(key);
        }

        @Override
        public long size(String key) {
            screenKey(key);
            byte[] value = objects.get(key);
            return value == null ? -1L : value.length;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            screenKey(key);
            if (failing && !key.startsWith(Withheld.ROOT)) {
                throw new IOException("the store is unavailable");
            }
            byte[] value = objects.get(key);
            return value == null ? Optional.empty() : Optional.of(new Versioned(value, value));
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            objects.put(key, content);
            return true;
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            objects.put(key, in.readAllBytes());
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            int emitted = 0;
            for (String name : children(prefix)) {
                if (name.compareTo(startAfter) <= 0) {
                    continue;
                }
                if (emitted++ >= limit) {
                    return;
                }
                consumer.accept(name);
            }
        }

        @Override
        public List<String> list(String prefix) {
            return new ArrayList<>(children(prefix));
        }

        private NavigableSet<String> children(String prefix) {
            String base = prefix.isEmpty() ? "" : prefix + "/";
            NavigableSet<String> names = new TreeSet<>();
            for (String key : objects.keySet()) {
                if (!key.startsWith(base)) {
                    continue;
                }
                String rest = key.substring(base.length());
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash));
            }
            return names;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void read(String key, OutputStream out) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(objects.getOrDefault(key, new byte[0]));
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }
    }
}
