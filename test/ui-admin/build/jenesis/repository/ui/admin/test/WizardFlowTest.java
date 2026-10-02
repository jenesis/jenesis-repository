package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.admin.web.WizardFlow;
import build.jenesis.repository.ui.store.SettingsAdmin;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's one wizard mechanism, driven as the page drives it - a form posted per step: a step is checked when it
 * is left forward and Back loses nothing; completing checks every step and stands on the first refused; the quicker
 * completion is offered only once there is something to create; the review lists every choice, the defaults included;
 * and what the run hands its owner to write is only what it chose.
 */
class WizardFlowTest {

    private final List<Map<String, String>> checked = new ArrayList<>();

    private static SettingsAdmin.SettingView view(String key, String kind, String inherited, boolean pinned) {
        return new SettingsAdmin.SettingView(key, "Retention", key, "What " + key + " does.", kind, List.of(),
                inherited, inherited, false, true, false, pinned, pinned ? "the deployment's operator" : null,
                "build.jenesis.repository.probe", false, null, null);
    }

    private final WizardFlow.Checks checks = new WizardFlow.Checks() {
        @Override
        public Map<String, String> identity(Map<String, String> identity) {
            String name = identity.getOrDefault("name", "");
            return name.isBlank() ? Map.of("name", "A repository needs a name.") : Map.of();
        }

        @Override
        public Map<String, String> settings(Map<String, String> settings) {
            checked.add(settings);
            return "-1".equals(settings.get("keep-last")) ? Map.of("keep-last", "keep-last is at least 0.")
                    : Map.of();
        }
    };

    private final WizardFlow.Definition definition = new WizardFlow.Definition("New repository",
            "/ui/new/repository", new WizardFlow.Exit("Cancel", "/ui/repositories", false), "Create repository",
            "Create now",
            List.of(WizardFlow.Step.identity("Repository", List.of("What it is called."),
                            List.of(new WizardFlow.Field("name", "Name", "Letters and digits.", List.of(), true))),
                    WizardFlow.Step.settings("Retention", List.of(view("keep-last", "LONG", "0", false),
                            view("max-age", "DURATION_OR_NONE", "", false))),
                    WizardFlow.Step.settings("Routing", List.of(view("routing", "STRING", "writable", true)))),
            List.of("Nothing is created until the review is completed."), checks);

    private static Map<String, String> form(String... pairs) {
        Map<String, String> form = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.put(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    @Test
    void a_step_is_checked_when_it_is_left_forward_and_back_loses_nothing() throws IOException {
        WizardFlow unnamed = WizardFlow.resume(definition, form("step", "0", "identity.name", ""));
        assertThat(unnamed.apply(WizardFlow.NEXT)).isFalse();
        assertThat(unnamed.index()).as("a refused step is not left").isZero();
        assertThat(unnamed.error("identity.name")).isEqualTo("A repository needs a name.");

        WizardFlow named = WizardFlow.resume(definition, form("step", "0", "identity.name", "libs"));
        named.apply(WizardFlow.NEXT);
        assertThat(named.index()).isEqualTo(1);
        assertThat(named.carried()).as("the name rides the settings step as a hidden field")
                .containsExactly(new WizardFlow.Carried("identity.name", "libs"));

        WizardFlow back = WizardFlow.resume(definition,
                form("step", "1", "identity.name", "libs", "setting.keep-last", "-1"));
        back.apply(WizardFlow.BACK);
        assertThat(back.index()).isZero();
        assertThat(back.errors()).as("Back checks nothing").isEmpty();
        assertThat(back.carried()).as("and what the later step was told rides back with it")
                .containsExactly(new WizardFlow.Carried("setting.keep-last", "-1"));
        assertThat(back.value("identity.name")).isEqualTo("libs");
    }

    @Test
    void a_refused_setting_keeps_the_run_on_its_step_and_a_blank_one_is_never_asked_about() throws IOException {
        WizardFlow flow = WizardFlow.resume(definition,
                form("step", "1", "identity.name", "libs", "setting.keep-last", "-1", "setting.max-age", ""));

        assertThat(flow.apply(WizardFlow.NEXT)).isFalse();
        assertThat(flow.index()).isEqualTo(1);
        assertThat(flow.error("setting.keep-last")).isEqualTo("keep-last is at least 0.");
        assertThat(checked).as("a blank value keeps what it inherits, so there is nothing to check")
                .containsExactly(Map.of("keep-last", "-1"));
    }

    @Test
    void completing_checks_every_step_and_hands_over_only_what_was_chosen() throws IOException {
        WizardFlow unnamed = WizardFlow.resume(definition, form("step", "3", "setting.keep-last", "5"));
        assertThat(unnamed.apply(WizardFlow.COMPLETE)).isFalse();
        assertThat(unnamed.index()).as("it stands on the first refused step").isZero();

        WizardFlow flow = WizardFlow.resume(definition,
                form("step", "3", "identity.name", "libs", "setting.keep-last", "5", "setting.max-age", ""));
        assertThat(flow.apply(WizardFlow.COMPLETE)).isTrue();
        assertThat(flow.identity()).containsExactly(Map.entry("name", "libs"));
        assertThat(flow.chosen()).as("what the owner writes: the chosen values, not the defaults, not the fixed")
                .containsExactly(Map.entry("keep-last", "5"));
        assertThat(flow.settings()).containsOnlyKeys("keep-last", "max-age");
    }

    @Test
    void the_review_lists_every_choice_the_defaults_and_the_fixed_ones_included() {
        WizardFlow flow = WizardFlow.resume(definition,
                form("step", "3", "identity.name", "libs", "setting.keep-last", "5", "setting.max-age", ""));

        assertThat(flow.reviewing()).isTrue();
        assertThat(flow.choices()).containsExactly(
                new WizardFlow.Choice("Name", "libs", null),
                new WizardFlow.Choice("keep-last", "5", null),
                new WizardFlow.Choice("max-age", "none", "default"),
                new WizardFlow.Choice("routing", "writable", "fixed by the deployment's operator"));
    }

    @Test
    void the_quicker_completion_is_offered_once_there_is_something_to_create() throws IOException {
        assertThat(WizardFlow.resume(definition, form("step", "0")).completesEarly())
                .as("not before the identity is given").isFalse();
        assertThat(WizardFlow.resume(definition, form("step", "3")).completesEarly())
                .as("nor on the review, whose submit completes").isFalse();

        WizardFlow early = WizardFlow.resume(definition, form("step", "1", "identity.name", "libs"));
        assertThat(early.completesEarly()).isTrue();
        assertThat(early.apply(WizardFlow.NOW)).as("the quicker completion confirms on the review first").isFalse();
        assertThat(early.reviewing()).isTrue();
        assertThat(early.chosen()).as("the steps not seen keep their defaults").isEmpty();
        assertThat(early.apply(WizardFlow.COMPLETE)).isTrue();
    }

    @Test
    void a_step_of_the_list_is_reached_directly_an_earlier_one_at_once_a_later_one_past_checked_steps()
            throws IOException {
        WizardFlow back = WizardFlow.resume(definition, form("step", "2", "identity.name", "libs"));
        back.apply(WizardFlow.GOTO + "0");
        assertThat(back.index()).isZero();

        WizardFlow unnamed = WizardFlow.resume(definition, form("step", "0"));
        unnamed.apply(WizardFlow.GOTO + "2");
        assertThat(unnamed.index()).as("a later step waits on the refused one before it").isZero();

        WizardFlow named = WizardFlow.resume(definition, form("step", "0", "identity.name", "libs"));
        named.apply(WizardFlow.GOTO + "2");
        assertThat(named.index()).isEqualTo(2);
        named.apply(WizardFlow.GOTO + "99");
        assertThat(named.index()).as("a step that does not exist leaves the run where it is").isEqualTo(2);
    }

    @Test
    void a_run_starts_from_what_is_held_already_and_rides_the_page_from_there() {
        WizardFlow started = WizardFlow.start(definition, Map.of("keep-last", "7"));

        assertThat(started.index()).isZero();
        assertThat(started.carried()).containsExactly(new WizardFlow.Carried("setting.keep-last", "7"));
    }
}
