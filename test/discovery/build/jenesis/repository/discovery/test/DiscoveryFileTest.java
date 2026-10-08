package build.jenesis.repository.discovery.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.discovery.DiscoveryException;
import build.jenesis.repository.discovery.DiscoveryFile;
import build.jenesis.repository.discovery.Domains;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A discovery file read as the proposal defines it: the file Jenesis publishes for itself reads key by key, and every
 * refusal the proposal lists fails the read naming the file, while a key it does not name is ignored. The domains a
 * name is asked of are the reversed name, shortest first, and a name that cannot be a domain is asked of none.
 */
class DiscoveryFileTest {

    static final String JENESIS = """
            module=https://github.com/jenesis/jenesis/releases/download/v{version}/{module}-{version}{-classifier}.{type}
            module.latest=https://github.com/jenesis/jenesis/releases/latest/download/{module}.jar
            module.suffixes=none
            moduletomaven=build.jenesis:{module}
            maven=https://github.com/jenesis/jenesis/releases/download/v{version}/{artifactId}-{version}{-classifier}.{type}
            maven.latest=https://github.com/jenesis/jenesis/releases/latest/download/{artifactId}.pom
            maven.suffixes=none
            """;

    @Test
    void the_file_jenesis_publishes_reads_key_by_key() {
        DiscoveryFile file = DiscoveryFile.parse("jenesis.build", JENESIS);

        assertThat(file.delegate()).as("a file answers alone unless it says otherwise").isFalse();
        assertThat(file.entry(DiscoveryFile.Key.MODULE)).hasValueSatisfying(entry -> {
            assertThat(entry.template()).isTrue();
            assertThat(entry.suffixes()).containsExactly("none");
            assertThat(entry.latest()).endsWith("/latest/download/{module}.jar");
        });
        assertThat(file.entry(DiscoveryFile.Key.MODULE_TO_MAVEN).orElseThrow().value())
                .isEqualTo("build.jenesis:{module}");
        assertThat(file.entry(DiscoveryFile.Key.MAVEN).orElseThrow().since()).isNull();
        assertThat(DiscoveryFile.address("jenesis.build"))
                .hasToString("https://jenesis.build/.well-known/java-repository.properties");
    }

    @Test
    void a_key_the_proposal_does_not_name_is_ignored_and_a_root_is_no_template() {
        DiscoveryFile file = DiscoveryFile.parse("example.com", """
                # a comment
                maven=https://maven.example.com/releases/
                maven.since=1.2.0
                gradle=https://plugins.example.com/
                delegate=true
                """);

        assertThat(file.entries()).containsOnlyKeys(DiscoveryFile.Key.MAVEN);
        assertThat(file.entry(DiscoveryFile.Key.MAVEN).orElseThrow().template()).isFalse();
        assertThat(file.entry(DiscoveryFile.Key.MAVEN).orElseThrow().since()).isEqualTo("1.2.0");
        assertThat(file.delegate()).isTrue();
    }

    @TestFactory
    Stream<DynamicTest> every_refusal_the_proposal_lists_fails_naming_the_file() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("a key without a value", "maven=");
        refused.put("a suffix that is not one word", "maven=https://m.example.com/\nmaven.suffixes=none,r-c");
        refused.put("a delegate that is neither", "delegate=maybe");
        refused.put("an unknown placeholder", "maven=https://m.example.com/{group}/{version}");
        refused.put("a placeholder of another key", "maven=https://m.example.com/{module}/{version}");
        refused.put("a coordinate naming no artifact", "moduletomaven=com.example");
        refused.put("a coordinate in module", "module=com.example:lib");
        refused.put("a location in moduletomaven", "moduletomaven=https://m.example.com/");
        refused.put("a latest link beside a root", "maven=https://m.example.com/\nmaven.latest=https://m.example.com/l");
        refused.put("a latest link beside a coordinate",
                "moduletomaven=com.example:{module}\nmoduletomaven.latest=https://m.example.com/l");
        refused.put("a location that is not https", "maven=http://m.example.com/");
        refused.put("a file location", "maven=file:///etc/");
        refused.put("a jar location", "maven=jar:https://m.example.com/x.jar!/");
        refused.put("a modifier beside no key", "maven.since=1.0");
        return refused.entrySet().stream().map(refusal -> DynamicTest.dynamicTest(refusal.getKey(), () ->
                assertThatThrownBy(() -> DiscoveryFile.parse("example.com", refusal.getValue()))
                        .isInstanceOf(DiscoveryException.class)
                        .hasMessageContaining("https://example.com/.well-known/java-repository.properties")));
    }

    @Test
    void a_name_is_asked_of_its_reversed_domains_shortest_first() {
        assertThat(Domains.of("net.bytebuddy.agent")).containsExactly("bytebuddy.net", "agent.bytebuddy.net");
        assertThat(Domains.of("build.jenesis")).containsExactly("jenesis.build");
        assertThat(Domains.of("org.apache.logging.log4j"))
                .containsExactly("apache.org", "logging.apache.org", "log4j.logging.apache.org");
        assertThat(Domains.suffix("net.bytebuddy.agent", "bytebuddy.net")).isEqualTo("-agent");
        assertThat(Domains.suffix("net.bytebuddy", "bytebuddy.net")).isEmpty();
    }

    @Test
    void a_name_that_cannot_be_a_domain_is_asked_of_none() {
        assertThat(Domains.of("lib")).as("a single label").isEmpty();
        assertThat(Domains.of("com.example+x")).as("a character no label holds").isEmpty();
        assertThat(Domains.of("com.my_company.lib")).as("an underscore").isEmpty();
        assertThat(Domains.of("com..lib")).as("an empty label").isEmpty();
    }
}
