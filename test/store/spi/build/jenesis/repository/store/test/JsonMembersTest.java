package build.jenesis.repository.store.test;

import build.jenesis.repository.store.JsonMembers;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMembersTest {

    @Test
    void splits_members_with_their_raw_values() {
        LinkedHashMap<String, String> members = JsonMembers.split(
                " {\"name\" : \"x\", \"versions\":{\"1.0\":{\"dist\":{\"tarball\":\"a}b\"}}, \"2.0\":[1,2,{}]},"
                        + "\"n\":12.5,\"t\":true,\"z\":null , \"esc\\\"aped\":\"q\\\"uote\"} ");
        assertThat(members.keySet()).containsExactly("name", "versions", "n", "t", "z", "esc\"aped");
        assertThat(members.get("name")).isEqualTo("\"x\"");
        assertThat(members.get("versions")).isEqualTo("{\"1.0\":{\"dist\":{\"tarball\":\"a}b\"}}, \"2.0\":[1,2,{}]}");
        assertThat(members.get("n")).isEqualTo("12.5");
        assertThat(members.get("t")).isEqualTo("true");
        assertThat(members.get("z")).isEqualTo("null");
        assertThat(members.get("esc\"aped")).isEqualTo("\"q\\\"uote\"");
    }

    @Test
    void an_empty_object_has_no_members() {
        assertThat(JsonMembers.split("{}")).isEmpty();
        assertThat(JsonMembers.split(" { } ")).isEmpty();
        assertThat(JsonMembers.join(Map.of())).isEqualTo("{}");
    }

    @Test
    void join_round_trips() {
        LinkedHashMap<String, String> members = new LinkedHashMap<>();
        members.put("b", "{\"x\":[1,\"]\"]}");
        members.put("a", "\"s\"");
        String json = JsonMembers.join(members);
        assertThat(json).isEqualTo("{\"b\":{\"x\":[1,\"]\"]},\"a\":\"s\"}");
        assertThat(JsonMembers.split(json)).containsExactlyEntriesOf(members);
    }

    @Test
    void quotes_and_unquotes() {
        assertThat(JsonMembers.quote("a\"b\\c\nd\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\u0001\"");
        assertThat(JsonMembers.unquote("\"a\\\"b\\\\c\\nd\\u0041\"")).isEqualTo("a\"b\\c\ndA");
    }

    @Test
    void malformed_input_is_refused() {
        assertThatThrownBy(() -> JsonMembers.split("[1]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonMembers.split("{\"a\":1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonMembers.split("{\"a\" 1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonMembers.split("{\"a\":\"open}")).isInstanceOf(IllegalArgumentException.class);
    }
}
