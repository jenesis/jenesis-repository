package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.ui.store.SettingControl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every setting the catalogue can declare is edited by exactly one control, decided from its kind and its form: the
 * kind decides a switch and a drop-down whatever the form, the form decides how other text is edited, and the kind
 * what one line checks.
 */
class SettingControlTest {

    @Test
    void every_kind_and_form_has_one_control() {
        for (Setting.Kind kind : Setting.Kind.values()) {
            for (Setting.Form form : Setting.Form.values()) {
                SettingControl control = SettingControl.of(kind.name(), form.name());
                assertThat(control).as("%s declared as %s", kind, form).isNotNull();
                assertThat(Stream.of(control.select(), control.textArea(), control.input()).filter(drawn -> drawn))
                        .as("%s is drawn as exactly one element", control).hasSize(1);
            }
        }
    }

    @Test
    void the_kind_decides_a_switch_and_a_drop_down_whatever_the_form() {
        for (Setting.Form form : Setting.Form.values()) {
            assertThat(SettingControl.of("BOOLEAN", form.name())).isEqualTo(SettingControl.SWITCH);
            assertThat(SettingControl.of("CHOICE", form.name())).isEqualTo(SettingControl.SELECT);
        }
    }

    @Test
    void the_form_decides_how_text_is_edited_and_the_kind_what_one_line_checks() {
        assertThat(SettingControl.of("STRING", "JSON")).isEqualTo(SettingControl.JSON);
        assertThat(SettingControl.of("STRING", "LINES")).isEqualTo(SettingControl.LINES);
        assertThat(SettingControl.of("STRING", "VALUES")).isEqualTo(SettingControl.VALUES);
        assertThat(SettingControl.of("STRING", "ROUTING")).isEqualTo(SettingControl.ROUTING);
        assertThat(SettingControl.of("LONG", null).inputType()).isEqualTo("number");
        assertThat(SettingControl.of("URI", "LINE").inputType()).isEqualTo("url");
        assertThat(SettingControl.of("SECRET", "LINE").inputType()).isEqualTo("password");
        assertThat(SettingControl.of("DURATION_OR_NONE", "LINE").durationMode()).isEqualTo("or-none");
        assertThat(SettingControl.of("DURATION", "LINE").durationMode()).isEqualTo("duration");
        assertThat(SettingControl.of("STRING", "LINE")).isEqualTo(SettingControl.LINE);
    }

    @Test
    void a_control_says_the_room_it_needs() {
        assertThat(SettingControl.JSON.rows()).isGreaterThan(SettingControl.LINES.rows());
        assertThat(SettingControl.JSON.code()).isTrue();
        assertThat(SettingControl.ROUTING.wide()).as("the routing editor takes the row").isTrue();
        assertThat(SettingControl.LINE.wide()).isFalse();
    }
}
