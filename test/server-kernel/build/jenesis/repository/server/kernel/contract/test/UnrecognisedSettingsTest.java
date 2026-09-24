package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.UnrecognisedSettings;
import build.jenesis.repository.settings.Setting;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boot check that a {@code jenreg.*} property nothing reads is said out loud rather than silently ignored.
 *
 * <p>Its whole value rests on the recognised set being <b>computed from what the deployment actually binds</b>, and
 * the two cells that hold it there are {@link #a_boot_only_bound_property_is_recognised()} and
 * {@link #a_map_bound_property_opens_its_prefix()}. Narrow the source to the settings catalogue alone - the obvious
 * implementation, and the one this was first sketched as - and both fail, because {@code auth},
 * {@code bootstrap-key} and {@code read-only} are deliberately absent from that catalogue (they are boot-only, not
 * runtime-editable) and {@code jenreg.proxy.<format>} is a key the operator names. Those are not edge cases; they are
 * most of a real deployment's configuration, so a check without them would fire on every deployment it ran on, be
 * muted within a day, and take the real warning with it.
 */
class UnrecognisedSettingsTest {

    /** Stands in for a {@code @ConfigurationProperties} object: the shapes that matter are a plain value, a nested
     *  settings object and a {@code Map} whose sub-keys the operator chooses. */
    public static final class Properties {

        public boolean isAuth() {
            return true;
        }

        public String getBootstrapKey() {
            return "";
        }

        public boolean isReadOnly() {
            return false;
        }

        public Map<String, String> getProxy() {
            return Map.of();
        }

        public Nested getUi() {
            return new Nested();
        }
    }

    public static final class Nested {

        public String getTitle() {
            return "";
        }
    }

    /** A second object binding a prefix the first already covers, with keys of its own - as the console's identity
     *  layer binds {@code jenreg.ui} beside its shell. */
    public static final class Console {

        public String getAdminKey() {
            return "";
        }
    }

    private static UnrecognisedSettings.Known known() {
        return UnrecognisedSettings.known(
                List.of(setting("outbox-parked-retention"), setting("listing-rebuild"), setting("walks")),
                List.of(new UnrecognisedSettings.Bound("jenreg", new Properties()),
                        new UnrecognisedSettings.Bound("jenreg.ui", new Console())),
                Set.of("jenreg.filesystem.root", "jenreg.format-upstream.*"));
    }

    private static Setting setting(String key) {
        return new Setting(key, "Group", key, "A dial.", Setting.Kind.STRING, "", true);
    }

    private static List<String> reported(String... configured) {
        return UnrecognisedSettings.assess(List.of(configured), known()).findings().stream()
                .map(UnrecognisedSettings.Finding::key).toList();
    }

    @Test
    void a_catalogue_dial_is_recognised() {
        assertThat(reported("jenreg.outbox-parked-retention", "jenreg.walks")).isEmpty();
    }

    @Test
    void a_boot_only_bound_property_is_recognised() {
        assertThat(reported("jenreg.auth", "jenreg.bootstrap-key", "jenreg.read-only"))
                .as("these are bound at boot and are deliberately not in the runtime-editable catalogue; judging "
                        + "against the catalogue alone would report a normal deployment's own configuration")
                .isEmpty();
    }

    @Test
    void two_objects_binding_one_prefix_are_both_recognised() {
        assertThat(reported("jenreg.ui.title", "JENREG_UI_ADMIN_KEY"))
                .as("the console's shell and its identity layer both bind jenreg.ui with disjoint keys; keeping one "
                        + "object per prefix reported the other's keys as unknown")
                .isEmpty();
    }

    @Test
    void a_key_an_installed_module_declares_is_recognised() {
        assertThat(reported("JENREG_FILESYSTEM_ROOT"))
                .as("a store backend binds nothing, so only its own declaration can say it reads its root - and "
                        + "the filesystem root is the one key every default deployment must set")
                .isEmpty();
    }

    @Test
    void a_declared_prefix_opens_like_a_map_property() {
        assertThat(reported("jenreg.format-upstream.maven", "JENREG_FORMAT_UPSTREAM_NPM"))
                .as("the per-format upstreams the console stores are named by the operator's choice of format")
                .isEmpty();
        assertThat(reported("jenreg.format-upstream"))
                .as("the prefix alone names nothing, as with a Map property")
                .hasSize(1);
    }

    @Test
    void a_map_bound_property_opens_its_prefix() {
        assertThat(reported("jenreg.proxy.maven", "jenreg.proxy.npm"))
                .as("the sub-key is the operator's, which is what a Map-typed property means")
                .isEmpty();
    }

    @Test
    void a_nested_property_object_is_walked() {
        assertThat(reported("jenreg.ui.title")).isEmpty();
    }

    @Test
    void every_spelling_relaxed_binding_accepts_is_one_key() {
        assertThat(reported("jenreg.bootstrap-key", "jenreg.bootstrapKey", "JENREG_BOOTSTRAP_KEY",
                "jenreg.BOOTSTRAP_KEY"))
                .as("a key set as an environment variable is the same key as its kebab-case declaration")
                .isEmpty();
    }

    @Test
    void a_key_nothing_reads_is_reported() {
        assertThat(reported("jenreg.webhook-parked-retention"))
                .as("the motivating case: a dial renamed by a release, whose old spelling now has no effect")
                .containsExactly("jenreg.webhook-parked-retention");
    }

    @Test
    void a_near_miss_is_reported_with_the_key_it_resembles() {
        UnrecognisedSettings.Report report =
                UnrecognisedSettings.assess(List.of("jenreg.listing-rebuilt"), known());
        assertThat(report.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.nearest()).isEqualTo("listingrebuild"));
    }

    @Test
    void a_key_resembling_nothing_is_reported_without_a_guess() {
        UnrecognisedSettings.Report report =
                UnrecognisedSettings.assess(List.of("jenreg.entirely-unrelated-thing"), known());
        assertThat(report.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.nearest())
                        .as("a suggestion that resembles nothing is noise, not help").isNull());
    }

    @Test
    void a_deployment_setting_nothing_unusual_reports_nothing() {
        assertThat(UnrecognisedSettings.assess(List.of(), known()).isEmpty()).isTrue();
    }
}
