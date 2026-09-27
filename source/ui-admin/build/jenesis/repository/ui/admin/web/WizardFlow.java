package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.ui.store.SettingsAdmin;

/**
 * One run of a wizard: its steps, the values it has been told so far and the step it stands on, rebuilt from each
 * request's form - nothing about a run is kept on the server. Every value rides the page as a hidden field from step to
 * step, so Back loses nothing, a closed tab leaves nothing half made, and nothing is written until the run completes,
 * when the wizard's owner writes the whole of it at once.
 *
 * <p>This is the console's one wizard mechanism. The first boot's setup, a new repository and a new build-cache
 * project each describe their steps ({@link Definition}) and render through the one template, {@code wizard}. Their
 * settings steps are the catalogue's ({@link build.jenesis.repository.settings.Wizard}); a wizard adds only what is not
 * a setting - the identity of what it creates, or a step that informs - and every run ends on a review listing each
 * choice, the defaults a run left alone included.
 *
 * <p>A step is checked when it is left forward, and Back checks nothing. Complete - and the quicker completion offered
 * from every step after the identity one, which takes what the steps not yet seen would have defaulted - checks every
 * step and, when one is refused, stands on the first refused step with each refusal beside its field.
 */
public final class WizardFlow {

    /** The form field naming the step a request was posted from. */
    public static final String STEP = "step";

    /** The form field naming what a request asks: {@link #NEXT}, {@link #BACK}, {@link #COMPLETE} or {@link #NOW}. */
    public static final String ACTION = "action";

    public static final String NEXT = "next";
    public static final String BACK = "back";
    public static final String COMPLETE = "complete";
    public static final String NOW = "now";

    /** The prefix of an identity field's form name. */
    public static final String IDENTITY = "identity.";

    /** The prefix of a setting's form name. */
    public static final String SETTING = "setting.";

    /** What a step is: the identity of what is created, information, a group of settings, or the closing review. */
    public enum Kind {
        IDENTITY, INFORMATION, SETTINGS, REVIEW
    }

    /** An identity field: what a wizard asks of the thing it creates beyond its settings. A field with options is a
     *  choice among them. */
    public record Field(String name, String label, String help, List<String> options, boolean required) {

        public Field {
            options = List.copyOf(options);
        }

        /** The form name the field's value rides under. */
        public String input() {
            return IDENTITY + name;
        }
    }

    /** A link a step offers, to a screen where the thing it talks about is done. */
    public record Link(String label, String href) {
    }

    /** One step: what it is, its title, the paragraphs it opens with, and its fields, settings or links. */
    public record Step(Kind kind, String title, List<String> paragraphs, List<Field> fields,
                       List<SettingsAdmin.SettingView> settings, List<Link> links) {

        public Step {
            paragraphs = List.copyOf(paragraphs);
            fields = List.copyOf(fields);
            settings = List.copyOf(settings);
            links = List.copyOf(links);
        }

        public static Step identity(String title, List<String> paragraphs, List<Field> fields) {
            return new Step(Kind.IDENTITY, title, paragraphs, fields, List.of(), List.of());
        }

        public static Step information(String title, List<String> paragraphs, List<Link> links) {
            return new Step(Kind.INFORMATION, title, paragraphs, List.of(), List.of(), links);
        }

        public static Step settings(String title, List<SettingsAdmin.SettingView> settings) {
            return new Step(Kind.SETTINGS, title, List.of(), List.of(), settings, List.of());
        }

        static Step review(List<String> paragraphs) {
            return new Step(Kind.REVIEW, "Review", paragraphs, List.of(), List.of(), List.of());
        }
    }

    /** What checks a run's values: each refused identity field or setting, keyed by its name or its key, with the
     *  sentence that refuses it. Asked with the values a step carries, never with a blank setting, which keeps what
     *  it inherits. */
    public interface Checks {

        Map<String, String> identity(Map<String, String> identity) throws IOException;

        Map<String, String> settings(Map<String, String> settings) throws IOException;
    }

    /**
     * A wizard, as its owner describes it: its title, the route its form posts to, where Cancel goes, what its
     * completion is called - and the quicker one, offered from every step after the identity one - its steps before
     * the review, what the review says, and what checks its values.
     */
    public record Definition(String title, String action, String cancel, String completeLabel, String nowLabel,
                             List<Step> steps, List<String> review, Checks checks) {

        public Definition {
            steps = List.copyOf(steps);
            review = List.copyOf(review);
        }
    }

    /** A value the page carries past the step it was asked on. */
    public record Carried(String name, String value) {
    }

    /** One line of the review: what the setting or field is, the value that will apply, and - for a value the run
     *  did not choose - where it comes from. */
    public record Choice(String label, String value, String note) {
    }

    private final Definition definition;
    private final List<Step> steps;
    private final Map<String, String> values;
    private final Map<String, String> errors = new LinkedHashMap<>();
    private int index;

    private WizardFlow(Definition definition, Map<String, String> values, int index) {
        this.definition = definition;
        List<Step> all = new ArrayList<>(definition.steps());
        all.add(Step.review(definition.review()));
        this.steps = List.copyOf(all);
        this.values = values;
        this.index = Math.clamp(index, 0, steps.size() - 1);
    }

    /** A run on its first step, its settings starting from {@code initial} - what the deployment holds already, for
     *  the first boot; nothing, for a creation. */
    public static WizardFlow start(Definition definition, Map<String, String> initial) {
        Map<String, String> values = new LinkedHashMap<>();
        initial.forEach((key, value) -> values.put(SETTING + key, value));
        return new WizardFlow(definition, values, 0);
    }

    /** The run a posted form continues: its values are the form's own, and it stands where the form was posted. */
    public static WizardFlow resume(Definition definition, Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>();
        form.forEach((name, value) -> {
            if (name.startsWith(IDENTITY) || name.startsWith(SETTING)) {
                values.put(name, value == null ? "" : value);
            }
        });
        int index;
        try {
            index = Integer.parseInt(form.getOrDefault(STEP, "0").trim());
        } catch (NumberFormatException notAStep) {
            index = 0;
        }
        return new WizardFlow(definition, values, index);
    }

    /**
     * Do what the form asks. {@link #NEXT} checks the step and moves on when nothing on it is refused; {@link #BACK}
     * moves back and checks nothing; {@link #COMPLETE} and {@link #NOW} check every step and stand on the first one
     * refused.
     *
     * @return {@code true} when the run is complete - every step accepted - and its owner writes it now.
     */
    public boolean apply(String action) throws IOException {
        switch (action == null ? "" : action) {
            case BACK -> index = Math.max(0, index - 1);
            case NEXT -> {
                errors.putAll(check(index));
                if (errors.isEmpty()) {
                    index = Math.min(index + 1, steps.size() - 1);
                }
            }
            case COMPLETE, NOW -> {
                for (int step = 0; step < steps.size(); step++) {
                    Map<String, String> refused = check(step);
                    if (!refused.isEmpty()) {
                        errors.putAll(refused);
                        index = step;
                        return false;
                    }
                }
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    /** Refuse the run where its owner found it could not be written after all - a name taken since it was checked -
     *  standing on the step that asked for {@code field}. */
    public void refuse(String field, String reason) {
        errors.put(field, reason);
        for (int step = 0; step < steps.size(); step++) {
            if (inputs(steps.get(step)).contains(field)) {
                index = step;
                return;
            }
        }
    }

    private Map<String, String> check(int step) throws IOException {
        Step checked = steps.get(step);
        Map<String, String> refused = new LinkedHashMap<>();
        switch (checked.kind()) {
            case IDENTITY -> definition.checks().identity(identity())
                    .forEach((name, reason) -> refused.put(IDENTITY + name, reason));
            case SETTINGS -> {
                Map<String, String> given = new LinkedHashMap<>();
                for (SettingsAdmin.SettingView setting : checked.settings()) {
                    String value = values.getOrDefault(SETTING + setting.key(), "");
                    if (!setting.pinned() && !value.isBlank()) {
                        given.put(setting.key(), value);
                    }
                }
                if (!given.isEmpty()) {
                    definition.checks().settings(given).forEach((key, reason) -> refused.put(SETTING + key, reason));
                }
            }
            default -> {
            }
        }
        return refused;
    }

    /** The form names a step asks for. */
    private static Set<String> inputs(Step step) {
        Set<String> names = new LinkedHashSet<>();
        step.fields().forEach(field -> names.add(field.input()));
        step.settings().stream().filter(setting -> !setting.pinned())
                .forEach(setting -> names.add(SETTING + setting.key()));
        return names;
    }

    public String title() {
        return definition.title();
    }

    public String action() {
        return definition.action();
    }

    public String cancel() {
        return definition.cancel();
    }

    public String completeLabel() {
        return definition.completeLabel();
    }

    public String nowLabel() {
        return definition.nowLabel();
    }

    /** Every step, the review last. */
    public List<Step> steps() {
        return steps;
    }

    /** The index of the step the run stands on. */
    public int index() {
        return index;
    }

    public Step current() {
        return steps.get(index);
    }

    /** Whether the run stands on its review, whose submit completes it. */
    public boolean reviewing() {
        return current().kind() == Kind.REVIEW;
    }

    /** Whether the quicker completion is offered here: on every step but the review and an identity step, which
     *  has to be answered before anything can be created. */
    public boolean completesEarly() {
        return current().kind() != Kind.REVIEW && current().kind() != Kind.IDENTITY;
    }

    /** The value the page carries under a form name, empty when it carries none. */
    public String value(String name) {
        return values.getOrDefault(name, "");
    }

    /** The refusal of a form name's value, or {@code null} when it was not refused. */
    public String error(String name) {
        return errors.get(name);
    }

    /** Every refusal of this request, by form name. */
    public Map<String, String> errors() {
        return Collections.unmodifiableMap(errors);
    }

    /** The values the page carries that the current step does not ask for, which ride it as hidden fields. */
    public List<Carried> carried() {
        Set<String> asked = inputs(current());
        List<Carried> carried = new ArrayList<>();
        values.forEach((name, value) -> {
            if (!asked.contains(name)) {
                carried.add(new Carried(name, value));
            }
        });
        return carried;
    }

    /** What the run says it will create, line by line: each identity field, then each setting - its chosen value, or
     *  the value it keeps and where that comes from. */
    public List<Choice> choices() {
        List<Choice> choices = new ArrayList<>();
        for (Step step : steps) {
            for (Field field : step.fields()) {
                String value = value(field.input());
                choices.add(new Choice(field.label(), value.isBlank() ? "(none)" : value, null));
            }
            for (SettingsAdmin.SettingView setting : step.settings()) {
                String value = value(SETTING + setting.key());
                if (setting.pinned()) {
                    choices.add(new Choice(setting.label(), setting.effectiveDisplay(),
                            "fixed by " + setting.pinnedBy()));
                } else if (value.isBlank()) {
                    choices.add(new Choice(setting.label(), setting.defaultDisplay(), "default"));
                } else {
                    choices.add(new Choice(setting.label(), value, null));
                }
            }
        }
        return choices;
    }

    /** The identity the run was given, by field name. */
    public Map<String, String> identity() {
        Map<String, String> identity = new LinkedHashMap<>();
        for (Step step : steps) {
            for (Field field : step.fields()) {
                identity.put(field.name(), value(field.input()).trim());
            }
        }
        return identity;
    }

    /** Every setting the run asks and may set, by key, with the value it carries - blank for one it leaves to what
     *  it inherits. */
    public Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (Step step : steps) {
            for (SettingsAdmin.SettingView setting : step.settings()) {
                if (!setting.pinned()) {
                    settings.put(setting.key(), value(SETTING + setting.key()).trim());
                }
            }
        }
        return settings;
    }

    /** {@link #settings()} without the blank ones: what a creation writes as the new object's own. */
    public Map<String, String> chosen() {
        Map<String, String> chosen = new LinkedHashMap<>();
        settings().forEach((key, value) -> {
            if (!value.isBlank()) {
                chosen.put(key, value);
            }
        });
        return chosen;
    }
}
