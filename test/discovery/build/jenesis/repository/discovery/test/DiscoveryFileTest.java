package build.jenesis.repository.discovery.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.discovery.DiscoveryException;
import build.jenesis.repository.discovery.DiscoveryFile;
import build.jenesis.repository.discovery.DiscoveryFile.Key;
import build.jenesis.repository.discovery.Domains;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A discovery file read as the proposal defines it: the file Jenesis publishes for itself reads key by key, each key
 * selecting the module or artifact it is for, and every refusal the proposal lists fails the read naming the file,
 * while a key it does not name is ignored. The domains a name is asked of are the reversed name, shortest first, and a
 * name that cannot be a domain is asked of none.
 */
class DiscoveryFileTest {

    static final String JENESIS = """
            moduletomaven=build.jenesis:{module}
            module[build.jenesis]=https://github.com/jenesis/jenesis/releases/download/v{version}/{module}-{version}{-classifier}.{type}
            module[build.jenesis].latest=https://github.com/jenesis/jenesis/releases/latest/download/{module}.jar
            module[build.jenesis].suffixes=none
            maven[build.jenesis]=https://github.com/jenesis/jenesis/releases/download/v{version}/{artifactId}-{version}{-classifier}.{type}
            maven[build.jenesis].latest=https://github.com/jenesis/jenesis/releases/latest/download/{artifactId}.pom
            maven[build.jenesis].suffixes=none
            sources[build.jenesis]=https://github.com/jenesis/jenesis/archive/refs/tags/v{version}.zip
            module[build.jenesis.launcher]=https://github.com/jenesis/jenesis-launcher/releases/download/v{version}/{module}-{version}{-classifier}.{type}
            maven[build.jenesis.launcher]=https://github.com/jenesis/jenesis-launcher/releases/download/v{version}/{artifactId}-{version}{-classifier}.{type}
            maven[build.jenesis.launcher].latest=https://github.com/jenesis/jenesis-launcher/releases/latest/download/{artifactId}.pom
            maven[build.jenesis.repository.*]=https://github.com/jenesis/jenesis-repository/releases/download/v{version}/{artifactId}-{version}{-classifier}.{type}
            maven[build.jenesis.repository.*].suffixes=none
            sources[build.jenesis.repository.*]=https://github.com/jenesis/jenesis-repository/archive/refs/tags/v{version}.zip
            """;

    @Test
    void the_file_jenesis_publishes_reads_key_by_key() {
        DiscoveryFile file = DiscoveryFile.parse("jenesis.build", JENESIS);

        assertThat(file.delegate()).as("a file answers alone unless it says otherwise").isFalse();
        assertThat(file.entry(Key.MODULE, "build.jenesis")).hasValueSatisfying(entry -> {
            assertThat(entry.spelled()).isEqualTo("module[build.jenesis]");
            assertThat(entry.template()).isTrue();
            assertThat(entry.suffixes()).containsExactly("none");
            assertThat(entry.latest()).endsWith("/latest/download/{module}.jar");
        });
        assertThat(file.entry(Key.MODULE_TO_MAVEN, "build.jenesis.launcher").orElseThrow().value())
                .as("a key without a selector serves every name").isEqualTo("build.jenesis:{module}");
        assertThat(file.entry(Key.MAVEN, "build.jenesis").orElseThrow().since()).isNull();
        assertThat(file.entry(Key.SOURCES, "build.jenesis").orElseThrow().value())
                .isEqualTo("https://github.com/jenesis/jenesis/archive/refs/tags/v{version}.zip");
        assertThat(DiscoveryFile.address("jenesis.build"))
                .hasToString("https://jenesis.build/.well-known/java-repository.properties");
    }

    @Test
    void the_exact_name_selects_before_the_longest_prefix_and_either_before_the_key_for_every_name() {
        DiscoveryFile file = DiscoveryFile.parse("example.com", """
                maven=https://example.com/every/
                maven[lib-*]=https://example.com/prefix/
                maven[lib-core-*]=https://example.com/longer/
                maven[lib-core-api]=https://example.com/exact/
                """);

        assertThat(file.entry(Key.MAVEN, "lib-core-api").orElseThrow().value()).isEqualTo("https://example.com/exact/");
        assertThat(file.entry(Key.MAVEN, "lib-core-impl").orElseThrow().value())
                .isEqualTo("https://example.com/longer/");
        assertThat(file.entry(Key.MAVEN, "lib-io").orElseThrow().value()).isEqualTo("https://example.com/prefix/");
        assertThat(file.entry(Key.MAVEN, "other").orElseThrow().value()).isEqualTo("https://example.com/every/");
        assertThat(file.entry(Key.MODULE, "other")).as("a key the file does not hold").isEmpty();
        assertThat(DiscoveryFile.parse("example.com", "maven[lib]=https://example.com/m/\n").entry(Key.MAVEN, "other"))
                .as("a selected key serves no other name").isEmpty();
    }

    @Test
    void a_key_the_proposal_does_not_name_is_ignored_and_a_root_is_no_template() {
        DiscoveryFile file = DiscoveryFile.parse("example.com", """
                # a comment
                maven=https://maven.example.com/releases/
                maven.since=1.2.0
                gradle=https://plugins.example.com/
                gradle[x]=https://plugins.example.com/x/
                module.since=1.0
                delegate=true
                """);

        assertThat(file.entries()).extracting(DiscoveryFile.Entry::spelled).containsExactly("maven");
        assertThat(file.entry(Key.MAVEN, "lib").orElseThrow().template()).isFalse();
        assertThat(file.entry(Key.MAVEN, "lib").orElseThrow().since()).isEqualTo("1.2.0");
        assertThat(file.delegate()).isTrue();
    }

    @TestFactory
    Stream<DynamicTest> every_refusal_the_proposal_lists_fails_naming_the_file() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("a key without a value", "maven=");
        refused.put("a selected key without a value", "maven[lib]= ");
        refused.put("a modifier without a value", "maven=https://m.example.com/\nmaven.since=");
        refused.put("a selector that is no name", "maven[a/lib]=https://m.example.com/");
        refused.put("an empty selector", "module[]=https://m.example.com/");
        refused.put("a star inside a selector", "maven[lib*core]=https://m.example.com/");
        refused.put("a suffix that is not one word", "maven=https://m.example.com/\nmaven.suffixes=none,r-c");
        refused.put("a delegate that is neither", "delegate=maybe");
        refused.put("an unknown placeholder", "maven=https://m.example.com/{group}/{version}");
        refused.put("a placeholder of another key", "maven=https://m.example.com/{module}/{version}");
        refused.put("a coordinate naming no artifact", "moduletomaven=com.example");
        refused.put("a coordinate with an empty part", "moduletomaven=com.example:lib:");
        refused.put("a coordinate in module", "module=com.example:lib");
        refused.put("a coordinate in sources", "sources=com.example:lib");
        refused.put("a location in moduletomaven", "moduletomaven=https://m.example.com/");
        refused.put("sources naming no version", "sources=https://m.example.com/archive.zip");
        refused.put("a latest link beside a root", "maven=https://m.example.com/\nmaven.latest=https://m.example.com/l");
        refused.put("a latest link beside a template naming no version",
                "maven=https://m.example.com/{artifactId}.jar\nmaven.latest=https://m.example.com/l");
        refused.put("a latest link beside a coordinate",
                "moduletomaven=com.example:{module}\nmoduletomaven.latest=https://m.example.com/l");
        refused.put("a selected latest link that is not https",
                "maven[lib]=https://m.example.com/{version}/{artifactId}.jar\nmaven[lib].latest=http://m.example.com/l");
        refused.put("a location that is not https", "maven=http://m.example.com/");
        refused.put("a file location", "maven=file:///etc/");
        refused.put("a jar location", "maven=jar:https://m.example.com/x.jar!/");
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
