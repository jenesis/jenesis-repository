package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.SettingsEnvironmentLayer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime-settings precedence rule: a stored setting overrides the packaged (classpath) default, but an
 * operator's explicit pin - an environment variable, a {@code -D} system property, the command line or an external
 * config file - overrides the store. {@link SettingsEnvironmentLayer} orders the sources for this, and the
 * {@link PinnedSettings} probe reports which keys an operator has fixed so a pinned key ignores the store.
 *
 * <h2>Why every packaged source below is named after a real app</h2>
 * This suite used to spell the packaged source as {@code [application.properties]} - one fabricated name, exercised
 * against a matcher that required the substring {@code "application"}. No app has been configured by a file of that
 * name since all four were renamed ({@code repository}, {@code cache}, {@code combined}, {@code ui}) so no
 * dependency's root {@code application.properties} could race them, so the matcher had matched nothing for a year -
 * every stored setting was layered at the <em>bottom</em> of the environment and {@link PinnedSettings} walked
 * straight past the shipped defaults - and this suite stayed green throughout, because the only packaged source it
 * ever built was the one the stale matcher still recognised. A test that pins an operator-visible identifier keeps the
 * literal (that rule is {@code ProviderIdentifierPrincipleTest}'s), so the names below are literals; what changed is
 * that they are now the names the product actually ships. {@code SpringConfigNamePrincipleTest} binds the same
 * recogniser to every {@code spring.config.name} the tree declares, so a fifth app cannot be added without one of the
 * two failing.
 */
class SettingsPrecedenceTest {

    /** The config names the applications ship, pinned as literals - see the class javadoc. */
    private static final List<String> PACKAGED = List.of("repository", "cache", "combined", "ui");

    private static final String PROPERTY = "jenreg.vulnerability-threshold";

    /** How Spring names the property source of an app's packaged classpath config file. */
    private static String packaged(String configName) {
        return "Config resource 'class path resource [" + configName
                + ".properties]' via location 'optional:classpath:/'";
    }

    /** And how it names an <em>external</em> one, which outranks the store and therefore pins its keys. */
    private static String external(String configName) {
        return "Config resource 'file [/etc/jenesis/" + configName
                + ".properties]' via location 'optional:file:/etc/jenesis/'";
    }

    /** An environment carrying only the sources a leg builds. The ambient {@code systemProperties} and
     *  {@code systemEnvironment} would sit <em>above</em> everything here, so a sibling suite's {@code -D} (this module
     *  has one that pins {@code jenreg.auth} for a booted server) or a developer's exported
     *  {@code JENREG_*} would be read as the pin these legs are about - a real precedence rule, but not the
     *  one under test, and one that would make the answer depend on what else ran. */
    private static StandardEnvironment isolated() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }

    @Test
    void a_stored_setting_beats_the_packaged_default_but_an_operator_pin_beats_the_store() {
        for (String configName : PACKAGED) {
            StandardEnvironment environment = isolated();
            MutablePropertySources sources = environment.getPropertySources();
            sources.addLast(new MapPropertySource(packaged(configName), Map.of(PROPERTY, "CRITICAL")));

            SettingsEnvironmentLayer.insert(sources, Map.of(PROPERTY, "MEDIUM"));     // above packaged, below env
            PinnedSettings pins = new PinnedSettings(environment);

            assertThat(environment.getProperty(PROPERTY))
                    .as("a stored setting overrides the shipped default in %s.properties", configName)
                    .isEqualTo("MEDIUM");
            assertThat(pins.pinned("vulnerability-threshold"))
                    .as("nothing above the store pins it in the %s app", configName).isEmpty();

            // An operator now pins the key from a source above the store.
            sources.addFirst(new MapPropertySource("commandLineArgs", Map.of(PROPERTY, "HIGH")));

            assertThat(environment.getProperty(PROPERTY)).as("the pin wins over the store").isEqualTo("HIGH");
            assertThat(pins.pinned("vulnerability-threshold")).get()
                    .extracting(PinnedSettings.Pin::source, PinnedSettings.Pin::value)
                    .containsExactly("command line", "HIGH");
        }
    }

    @Test
    void an_environment_variable_pins_in_either_spelling_spring_boot_binds() {
        // The process environment spells a key two ways and the binder reads both: hyphens as underscores, or
        // hyphens dropped (Spring Boot's canonical form). The pin chain must see both, or a deployment on the second
        // has a working setting everywhere except for the readers that take a pin or a literal default.
        for (String spelling : List.of("JENREG_PROXY_ALLOW_INTERNAL", "JENREG_PROXYALLOWINTERNAL")) {
            StandardEnvironment environment = isolated();
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(spelling, "true")));
            assertThat(new PinnedSettings(environment).pinned("proxy-allow-internal")).get()
                    .as("%s pins proxy-allow-internal", spelling)
                    .extracting(PinnedSettings.Pin::source, PinnedSettings.Pin::value)
                    .containsExactly("environment variable", "true");
        }
    }

    @Test
    void a_value_present_only_in_the_packaged_default_is_never_mistaken_for_a_pin() {
        for (String configName : PACKAGED) {
            StandardEnvironment environment = isolated();
            environment.getPropertySources()
                    .addLast(new MapPropertySource(packaged(configName), Map.of(PROPERTY, "CRITICAL")));
            // No stored source is inserted at all; the probe must still not treat the shipped default as a pin.
            assertThat(new PinnedSettings(environment).pinned("vulnerability-threshold"))
                    .as("the %s app's shipped default is not an operator's pin", configName).isEmpty();
        }
    }

    /**
     * The concrete harm the stale matcher did, as its own leg: the shipped defaults are written as environment
     * placeholders ({@code jenreg.auth=${JENREG_AUTH:true}}), and a {@code PropertySource}
     * hands back the <em>raw</em> text - only the {@code Environment} expands a placeholder. So a probe that walks past
     * the packaged defaults does not merely mis-attribute the origin; it reports the key as pinned to the literal
     * {@code ${JENREG_AUTH:true}}, which every consumer of the chain then reads as a value. The posture
     * report's {@code Configuration.flag} accepts only a literal {@code "true"}, so that string reads as <b>false</b>
     * and {@code SecurityPosture} raises its CRITICAL {@code jenreg.auth.open} row against a deployment whose
     * authorization is on. No suite could see it: every server-booting suite pins {@code jenreg.auth=false}
     * from a source above the store, which is the one arrangement in which the walk stops before the packaged file.
     */
    @Test
    void a_packaged_default_holding_an_unresolved_placeholder_is_not_reported_as_a_pin() {
        StandardEnvironment environment = isolated();
        environment.getPropertySources().addLast(new MapPropertySource(packaged("repository"),
                Map.of("jenreg.auth", "${JENREG_AUTH:true}")));

        assertThat(new PinnedSettings(environment).pinned("auth"))
                .as("the shipped secure default is not an operator's pin, and its unexpanded placeholder is not a "
                        + "value any reader of the chain may be handed")
                .isEmpty();
    }

    /** The other half of the rule the packaged/external distinction exists for: an operator's own config file sits
     *  above the store and does pin its keys, so widening the recogniser to every classpath resource must not have
     *  swallowed the file case with it. */
    @Test
    void an_external_config_file_still_pins_over_the_store() {
        StandardEnvironment environment = isolated();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addLast(new MapPropertySource(external("repository"), Map.of(PROPERTY, "HIGH")));
        sources.addLast(new MapPropertySource(packaged("repository"), Map.of(PROPERTY, "CRITICAL")));

        SettingsEnvironmentLayer.insert(sources, Map.of(PROPERTY, "MEDIUM"));

        assertThat(environment.getProperty(PROPERTY)).as("the operator's own file outranks the store")
                .isEqualTo("HIGH");
        assertThat(new PinnedSettings(environment).pinned("vulnerability-threshold")).get()
                .extracting(PinnedSettings.Pin::source, PinnedSettings.Pin::value)
                .containsExactly("configuration file", "HIGH");
    }

    /**
     * A pin written as a placeholder reports what it resolves to, not how it is spelled.
     *
     * <p>An operator's own config file is a pin, and it is exactly where a deployment writes
     * {@code jenreg.vulnerability-threshold=${THRESHOLD:HIGH}}. A {@link org.springframework.core.env.PropertySource}
     * hands back that text verbatim, so a pin read straight off the source carried the literal - and
     * {@code effective} hands a pin's value on as the effective value of the key, so every consumer then parsed the
     * placeholder. The threshold parsed as no threshold; a flag written this way parsed as {@code false}.
     */
    @Test
    void a_pin_written_as_a_placeholder_reports_the_value_it_resolves_to() {
        StandardEnvironment environment = isolated();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addLast(new MapPropertySource("systemEnvironment", Map.of("THRESHOLD", "CRITICAL")));
        SettingsEnvironmentLayer.insert(sources, Map.of(PROPERTY, "MEDIUM"));
        sources.addFirst(new MapPropertySource(external("repository"), Map.of(PROPERTY, "${THRESHOLD:HIGH}")));

        assertThat(new PinnedSettings(environment).pinned("vulnerability-threshold")).get()
                .extracting(PinnedSettings.Pin::source, PinnedSettings.Pin::value)
                .as("the origin comes from the walk, the value from the environment")
                .containsExactly("configuration file", "CRITICAL");
    }

    /** The placeholder's own default is a value like any other: nothing sets {@code THRESHOLD}, so the pin is what
     *  the operator wrote after the colon - and never the {@code ${...}} text that says so. */
    @Test
    void a_placeholder_pin_falls_back_to_its_written_default() {
        StandardEnvironment environment = isolated();
        MutablePropertySources sources = environment.getPropertySources();
        SettingsEnvironmentLayer.insert(sources, Map.of(PROPERTY, "MEDIUM"));
        sources.addFirst(new MapPropertySource(external("repository"), Map.of(PROPERTY, "${THRESHOLD:HIGH}")));

        assertThat(new PinnedSettings(environment).pinned("vulnerability-threshold")).get()
                .extracting(PinnedSettings.Pin::value).isEqualTo("HIGH");
    }

    /**
     * An unresolvable placeholder with no default is reported at its spelling rather than failing the read.
     *
     * <p>Spring refuses to expand it rather than inventing a value, and that refusal must not propagate: this is
     * the surface an operator opens to find out what their configuration is doing, and a placeholder nothing sets
     * is precisely what they need to be shown. Failing the settings screen instead would hide it.
     */
    @Test
    void an_unresolvable_placeholder_is_reported_rather_than_thrown() {
        StandardEnvironment environment = isolated();
        MutablePropertySources sources = environment.getPropertySources();
        SettingsEnvironmentLayer.insert(sources, Map.of(PROPERTY, "MEDIUM"));
        sources.addFirst(new MapPropertySource(external("repository"), Map.of(PROPERTY, "${NOTHING_SETS_THIS}")));

        assertThat(new PinnedSettings(environment).pinned("vulnerability-threshold")).get()
                .extracting(PinnedSettings.Pin::value).isEqualTo("${NOTHING_SETS_THIS}");
    }
}
