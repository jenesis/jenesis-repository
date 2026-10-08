package build.jenesis.repository.discovery.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.discovery.DiscoveryException;
import build.jenesis.repository.discovery.DiscoveryFile;
import build.jenesis.repository.discovery.RepositoryDiscovery;
import build.jenesis.repository.discovery.RepositoryDiscovery.Located;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a request path's file is, as the domain its name reverses into says: through the file Jenesis publishes for
 * itself a Maven file, a module file and the Maven view of a module land on its GitHub releases, each file checked
 * against the checksum beside it, and a request without a version reads the newest from the latest link's redirect, or
 * from the {@code maven-metadata.xml} it names. Each project under one domain is located through the key selecting it.
 * The shortest domain answers for its subdomains unless it says {@code delegate=true}, a key restricted by
 * {@code .since} or {@code .suffixes} leaves other versions - and a request without one - to the other legs, each file
 * is asked for once per period - its absence and its refusal too - and a private host is never asked.
 */
class RepositoryDiscoveryTest {

    private static final String RELEASES = "https://github.com/jenesis/jenesis/releases/";

    private static final String METADATA = """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>lib</artifactId>
              <versioning>
                <latest>1.2-SNAPSHOT</latest>
                <release>1.1</release>
                <versions>
                  <version>1.0</version>
                  <version>1.1-rc1</version>
                  <version>1.1</version>
                  <version>1.2-SNAPSHOT</version>
                </versions>
              </versioning>
            </metadata>
            """;

    private final Table table = new Table().file("jenesis.build", DiscoveryFileTest.JENESIS);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-07T00:00:00Z"));

    private RepositoryDiscovery discovery(Predicate<URI> refused) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new RepositoryDiscovery(table, refused, Duration.ofHours(1), clock);
    }

    private RepositoryDiscovery discovery() {
        return discovery(_ -> false);
    }

    @Test
    void a_maven_file_of_jenesis_is_its_release_asset_checked_against_the_checksum_beside_it() {
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0.jar"))
                .contains(new Located.Fetched(URI.create(RELEASES + "download/v0.20.0/build.jenesis-0.20.0.jar"), true));
        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0-sources.jar"))
                .contains(new Located.Fetched(URI.create(RELEASES
                        + "download/v0.20.0/build.jenesis-0.20.0-sources.jar"), true));
        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0.jar.sha256"))
                .as("a checksum is itself not checked against one")
                .contains(new Located.Fetched(URI.create(RELEASES
                        + "download/v0.20.0/build.jenesis-0.20.0.jar.sha256"), false));
        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis/0.20.0-SNAPSHOT/"
                + "build.jenesis-0.20.0-SNAPSHOT.jar")).as("suffixes=none serves no snapshot").isEmpty();
        assertThat(discovery.locate("/maven/org/acme/lib/1.0/lib-1.0.jar")).as("acme.org publishes no file")
                .isEmpty();
    }

    @Test
    void the_newest_version_is_read_from_where_the_latest_link_redirects() throws Exception {
        table.redirect(RELEASES + "latest/download/build.jenesis.pom",
                RELEASES + "download/v0.21.0/build.jenesis.pom");
        RepositoryDiscovery discovery = discovery();

        Located metadata = discovery.locate("/maven/build/jenesis/build.jenesis/maven-metadata.xml").orElseThrow();
        assertThat(metadata).isInstanceOf(Located.Answered.class);
        String document = new String(((Located.Answered) metadata).body(), StandardCharsets.UTF_8);
        assertThat(document).contains("<release>0.21.0</release>").contains("<version>0.21.0</version>")
                .contains("<artifactId>build.jenesis</artifactId>");
        Located sha1 = discovery.locate("/maven/build/jenesis/build.jenesis/maven-metadata.xml.sha1").orElseThrow();
        assertThat(new String(((Located.Answered) sha1).body(), StandardCharsets.US_ASCII)).as("its own checksum")
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                        .digest(((Located.Answered) metadata).body())));
    }

    @Test
    void a_module_is_located_through_module_first_and_its_maven_view_through_moduletomaven_first() {
        table.redirect(RELEASES + "latest/download/build.jenesis.jar",
                RELEASES + "download/v0.21.0/build.jenesis.jar");
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/module/build.jenesis/0.20.0/build.jenesis.jar"))
                .contains(new Located.Fetched(URI.create(RELEASES + "download/v0.20.0/build.jenesis-0.20.0.jar"), true));
        assertThat(discovery.locate("/module/build.jenesis/build.jenesis.jar"))
                .as("the latest pointer through the module's latest link")
                .contains(new Located.Fetched(URI.create(RELEASES + "download/v0.21.0/build.jenesis-0.21.0.jar"), true));
        assertThat(discovery.locate("/artifact/build.jenesis/0.20.0/build.jenesis.pom"))
                .as("the Maven view asks moduletomaven, then the artifact's group's maven key")
                .contains(new Located.Fetched(URI.create(RELEASES + "download/v0.20.0/build.jenesis-0.20.0.pom"), true));
    }

    @Test
    void a_suffix_placeholder_maps_each_module_below_the_domain_to_its_artifact() {
        table.file("bytebuddy.net", """
                moduletomaven=net.bytebuddy:byte-buddy{-suffix}
                maven=https://maven.bytebuddy.net/releases/
                """);
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/artifact/net.bytebuddy.agent/1.15.11/net.bytebuddy.agent.jar"))
                .contains(new Located.Fetched(URI.create("https://maven.bytebuddy.net/releases/net/bytebuddy/"
                        + "byte-buddy-agent/1.15.11/byte-buddy-agent-1.15.11.jar"), false));
        assertThat(discovery.locate("/maven/net/bytebuddy/byte-buddy/1.15.11/byte-buddy-1.15.11.pom"))
                .as("a root relays the request's own path")
                .contains(new Located.Relayed(URI.create("https://maven.bytebuddy.net/releases/"),
                        "net/bytebuddy/byte-buddy/1.15.11/byte-buddy-1.15.11.pom"));
        assertThat(table.asked("agent.bytebuddy.net")).as("the shortest file speaks for its subdomains").isZero();
    }

    @Test
    void a_file_saying_delegate_true_lets_the_most_specific_file_holding_a_key_answer() {
        table.file("bytebuddy.net", """
                maven=https://maven.bytebuddy.net/releases/
                module=https://modules.bytebuddy.net/
                delegate=true
                """);
        table.file("agent.bytebuddy.net", "module=https://agent.bytebuddy.net/modules/\n");
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/module/net.bytebuddy.agent/1.0/net.bytebuddy.agent.jar"))
                .contains(new Located.Relayed(URI.create("https://agent.bytebuddy.net/modules/"),
                        "module/net.bytebuddy.agent/1.0/net.bytebuddy.agent.jar"));
        assertThat(discovery.answering("net.bytebuddy.agent").get(DiscoveryFile.Key.MAVEN).domain())
                .as("a key the subdomain does not hold is its parent's").isEqualTo("bytebuddy.net");
    }

    @Test
    void a_longer_domain_is_asked_only_where_no_shorter_one_publishes_a_file() {
        table.file("agent.bytebuddy.net", "maven=https://agent.bytebuddy.net/maven/\n");
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/maven/net/bytebuddy/agent/x/1.0/x-1.0.jar"))
                .contains(new Located.Relayed(URI.create("https://agent.bytebuddy.net/maven/"),
                        "net/bytebuddy/agent/x/1.0/x-1.0.jar"));
        assertThat(table.asked("bytebuddy.net")).isOne();
    }

    @Test
    void a_key_serves_from_its_since_on_and_the_qualifiers_its_suffixes_name() {
        table.file("example.com", """
                maven=https://example.com/m/{groupPath}/{artifactId}/{version}/{artifactId}-{version}{-classifier}.{type}
                maven.since=1.2.0
                maven.suffixes=none,rc
                """);
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/maven/com/example/lib/1.1.9/lib-1.1.9.jar")).as("before since").isEmpty();
        assertThat(discovery.locate("/maven/com/example/lib/1.2.0/lib-1.2.0.jar")).isPresent();
        assertThat(discovery.locate("/maven/com/example/lib/1.10.0/lib-1.10.0.jar")).as("Maven's order").isPresent();
        assertThat(discovery.locate("/maven/com/example/lib/1.3.0-RC2/lib-1.3.0-RC2.jar")).isPresent();
        assertThat(discovery.locate("/maven/com/example/lib/1.3.0-rc.1/lib-1.3.0-rc.1.jar")).isPresent();
        assertThat(discovery.locate("/maven/com/example/lib/1.3.0-rc12/lib-1.3.0-rc12.jar")).isPresent();
        assertThat(discovery.locate("/maven/com/example/lib/1.3.0-rcx/lib-1.3.0-rcx.jar")).as("a longer word").isEmpty();
        assertThat(discovery.locate("/maven/com/example/lib/1.3.0-SNAPSHOT/lib-1.3.0-SNAPSHOT.jar")).isEmpty();
        assertThat(discovery.locate("/maven/com/example/lib/1.2.0/lib-1.2.0.jar").orElseThrow())
                .isEqualTo(new Located.Fetched(URI.create("https://example.com/m/com/example/lib/1.2.0/lib-1.2.0.jar"),
                        true));
    }

    @Test
    void a_template_without_classifier_or_type_serves_the_plain_jar_alone() {
        table.file("example.com", "maven=https://example.com/{artifactId}-{version}.jar\n");
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/maven/com/example/lib/1.0/lib-1.0.jar"))
                .contains(new Located.Fetched(URI.create("https://example.com/lib-1.0.jar"), false));
        assertThat(discovery.locate("/maven/com/example/lib/1.0/lib-1.0-sources.jar")).isEmpty();
        assertThat(discovery.locate("/maven/com/example/lib/1.0/lib-1.0.pom")).isEmpty();
    }

    @Test
    void each_file_and_each_absence_is_asked_for_once_a_period() {
        RepositoryDiscovery discovery = discovery();
        for (int request = 0; request < 3; request++) {
            discovery.locate("/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0.jar");
            discovery.locate("/maven/org/acme/lib/1.0/lib-1.0.jar");
        }
        assertThat(table.asked("jenesis.build")).isOne();
        assertThat(table.asked("acme.org")).as("an absence is remembered too").isOne();

        now.set(now.get().plus(Duration.ofMinutes(61)));
        discovery.locate("/maven/org/acme/lib/1.0/lib-1.0.jar");
        assertThat(table.asked("acme.org")).as("and asked again once the period is over").isEqualTo(2);
    }

    @Test
    void a_refused_file_fails_every_request_it_answers_and_is_remembered_as_refused() {
        table.file("example.com", "maven=http://example.com/plain/\n");
        RepositoryDiscovery discovery = discovery();

        for (int request = 0; request < 2; request++) {
            assertThatThrownBy(() -> discovery.locate("/maven/com/example/lib/1.0/lib-1.0.jar"))
                    .isInstanceOf(DiscoveryException.class).hasMessageContaining("not an https location");
        }
        assertThat(table.asked("example.com")).isOne();
    }

    @Test
    void a_latest_link_redirecting_where_its_template_does_not_describe_is_refused() {
        table.redirect(RELEASES + "latest/download/build.jenesis.pom", "https://elsewhere.example/build.jenesis.pom");
        RepositoryDiscovery discovery = discovery();

        assertThatThrownBy(() -> discovery.locate("/maven/build/jenesis/build.jenesis/maven-metadata.xml"))
                .isInstanceOf(DiscoveryException.class).hasMessageContaining("does not describe");
    }

    @Test
    void a_latest_link_naming_its_version_in_a_header_is_read_without_a_redirect() {
        table.head(RELEASES + "latest/download/build.jenesis.pom", 200,
                Map.of("Jenesis-MavenVersion", "0.22.0"));
        RepositoryDiscovery discovery = discovery();

        Located metadata = discovery.locate("/maven/build/jenesis/build.jenesis/maven-metadata.xml").orElseThrow();
        assertThat(new String(((Located.Answered) metadata).body(), StandardCharsets.UTF_8))
                .contains("<release>0.22.0</release>");
    }

    @Test
    void each_project_under_one_domain_is_located_through_the_key_selecting_it() {
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis.launcher/1.4.0/build.jenesis.launcher-1.4.0.jar"))
                .contains(new Located.Fetched(URI.create("https://github.com/jenesis/jenesis-launcher/releases/"
                        + "download/v1.4.0/build.jenesis.launcher-1.4.0.jar"), true));
        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis.repository.store/1.0.0/"
                + "build.jenesis.repository.store-1.0.0.pom")).as("a prefix selects every artifact it begins")
                .contains(new Located.Fetched(URI.create("https://github.com/jenesis/jenesis-repository/releases/"
                        + "download/v1.0.0/build.jenesis.repository.store-1.0.0.pom"), true));
        assertThat(discovery.locate("/artifact/build.jenesis.repository.store/1.0.0/build.jenesis.repository.store.jar"))
                .as("a module mapped by the key for every name, located by the key selecting its artifact")
                .contains(new Located.Fetched(URI.create("https://github.com/jenesis/jenesis-repository/releases/"
                        + "download/v1.0.0/build.jenesis.repository.store-1.0.0.jar"), true));
        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis.other/1.0/build.jenesis.other-1.0.jar"))
                .as("an artifact no key selects").isEmpty();
        assertThat(discovery.answering("build.jenesis", DiscoveryFile.Key.SOURCES, "build.jenesis.repository.store"))
                .hasValueSatisfying(answer -> assertThat(answer.entry().value())
                        .isEqualTo("https://github.com/jenesis/jenesis-repository/archive/refs/tags/v{version}.zip"));
        assertThat(discovery.answering("build.jenesis").get(DiscoveryFile.Key.SOURCES).entry().spelled())
                .isEqualTo("sources[build.jenesis]");
    }

    @Test
    void a_delegating_file_lets_a_deeper_subdomain_answer_past_one_without_a_file() {
        table.file("bytebuddy.net", "maven=https://maven.bytebuddy.net/releases/\ndelegate=true\n");
        table.file("x.agent.bytebuddy.net", "maven=https://x.bytebuddy.net/maven/\n");
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/maven/net/bytebuddy/agent/x/lib/1.0/lib-1.0.jar"))
                .contains(new Located.Relayed(URI.create("https://x.bytebuddy.net/maven/"),
                        "net/bytebuddy/agent/x/lib/1.0/lib-1.0.jar"));
        assertThat(table.asked("agent.bytebuddy.net")).isOne();
    }

    @Test
    void a_plain_coordinate_for_every_name_maps_only_the_module_of_its_own_domain() {
        table.file("example.com", """
                moduletomaven=com.example:example-core
                maven=https://maven.example.com/releases/
                """);
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/artifact/com.example/1.0/com.example.jar"))
                .contains(new Located.Fetched(URI.create("https://maven.example.com/releases/com/example/"
                        + "example-core/1.0/example-core-1.0.jar"), false));
        assertThat(discovery.locate("/artifact/com.example.legacy/1.0/com.example.legacy.jar")).isEmpty();

        table.file("example.com", """
                moduletomaven=com.example:example-core
                moduletomaven[com.example.legacy]=com.example:example-classic
                maven=https://maven.example.com/releases/
                """);
        discovery.forget();
        assertThat(discovery.locate("/artifact/com.example.legacy/1.0/com.example.legacy.jar"))
                .as("a selected coordinate maps its module wherever it sits below the domain")
                .contains(new Located.Fetched(URI.create("https://maven.example.com/releases/com/example/"
                        + "example-classic/1.0/example-classic-1.0.jar"), false));
    }

    @Test
    void a_latest_link_naming_maven_metadata_lists_the_versions_the_key_serves() {
        table.file("example.com", """
                maven=https://cdn.example.com/{artifactId}/{version}/{artifactId}-{version}{-classifier}.{type}
                maven.latest=https://maven.example.com/releases/{groupPath}/{artifactId}/maven-metadata.xml
                maven.suffixes=none,rc
                module=https://cdn.example.com/{module}/{version}/{module}-{version}.jar
                module.latest=https://maven.example.com/releases/{module}/maven-metadata.xml
                module.since=1.1
                """);
        table.document("https://maven.example.com/releases/com/example/lib/maven-metadata.xml", METADATA);
        table.document("https://maven.example.com/releases/com.example.lib/maven-metadata.xml", METADATA);
        RepositoryDiscovery discovery = discovery();

        Located metadata = discovery.locate("/maven/com/example/lib/maven-metadata.xml").orElseThrow();
        assertThat(new String(((Located.Answered) metadata).body(), StandardCharsets.UTF_8))
                .contains("<version>1.0</version>", "<version>1.1-rc1</version>", "<version>1.1</version>",
                        "<latest>1.1</latest>", "<release>1.1</release>")
                .doesNotContain("SNAPSHOT");
        assertThat(discovery.locate("/module/com.example.lib/com.example.lib.jar"))
                .as("the newest release the key serves")
                .contains(new Located.Fetched(URI.create("https://cdn.example.com/com.example.lib/1.1/"
                        + "com.example.lib-1.1.jar"), false));
        assertThat(table.asked).as("read, never sent a HEAD").noneMatch(url -> !url.getPath().endsWith(".xml")
                && !url.getPath().endsWith(".properties"));
    }

    @Test
    void a_key_restricting_its_versions_serves_no_request_without_one_and_filters_a_roots_metadata() {
        table.file("example.com", """
                module=https://modules.example.com/
                module.since=1.0
                maven=https://maven.example.com/releases/
                maven.suffixes=none
                """);
        table.document("https://maven.example.com/releases/com/example/lib/maven-metadata.xml", METADATA);
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/module/com.example/com.example.jar")).isEmpty();
        assertThat(discovery.locate("/module/com.example/1.1/com.example.jar"))
                .contains(new Located.Relayed(URI.create("https://modules.example.com/"),
                        "module/com.example/1.1/com.example.jar"));
        Located metadata = discovery.locate("/maven/com/example/lib/maven-metadata.xml").orElseThrow();
        assertThat(new String(((Located.Answered) metadata).body(), StandardCharsets.UTF_8))
                .contains("<version>1.0</version>", "<version>1.1</version>", "<release>1.1</release>")
                .doesNotContain("rc1", "SNAPSHOT");
    }

    @Test
    void a_latest_link_naming_what_is_no_version_is_refused() {
        table.head(RELEASES + "latest/download/build.jenesis.pom", 200,
                Map.of("Jenesis-MavenVersion", "../../etc"));
        RepositoryDiscovery discovery = discovery();

        assertThatThrownBy(() -> discovery.locate("/maven/build/jenesis/build.jenesis/maven-metadata.xml"))
                .isInstanceOf(DiscoveryException.class).hasMessageContaining("newest version");
    }

    @Test
    void a_private_host_is_never_asked_and_never_a_location() {
        table.file("corp.internal", "maven=https://maven.corp.internal/\n");
        table.file("example.com", "maven=https://10.0.0.1/maven/\n");
        RepositoryDiscovery discovery = discovery(uri -> uri.getHost().endsWith(".internal")
                || uri.getHost().startsWith("10."));

        assertThat(discovery.locate("/maven/internal/corp/lib/1.0/lib-1.0.jar")).isEmpty();
        assertThat(table.asked("corp.internal")).as("the file of a private host is not asked for").isZero();
        assertThatThrownBy(() -> discovery.locate("/maven/com/example/lib/1.0/lib-1.0.jar"))
                .isInstanceOf(DiscoveryException.class).hasMessageContaining("does not reach");
    }

    @Test
    void a_check_asks_each_domain_afresh_and_says_what_answers_and_where_a_path_goes() {
        table.file("bytebuddy.net", "maven=https://maven.bytebuddy.net/releases/\ndelegate=true\n");
        table.file("agent.bytebuddy.net", "maven=http://agent.bytebuddy.net/plain/\n");
        RepositoryDiscovery discovery = discovery();
        discovery.locate("/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0.jar");

        RepositoryDiscovery.Check jenesis = discovery.check("build.jenesis",
                "/maven/build/jenesis/build.jenesis/0.20.0/build.jenesis-0.20.0.jar");
        assertThat(table.asked("jenesis.build")).as("asked afresh, not as remembered").isEqualTo(2);
        assertThat(jenesis.domains()).singleElement().satisfies(asked -> {
            assertThat(asked.state()).isEqualTo("found");
            assertThat(asked.file().entries()).extracting(DiscoveryFile.Entry::key).contains(DiscoveryFile.Key.MAVEN);
        });
        assertThat(jenesis.located()).isEqualTo(new Located.Fetched(
                URI.create(RELEASES + "download/v0.20.0/build.jenesis-0.20.0.jar"), true));

        RepositoryDiscovery.Check agent = discovery.check("net.bytebuddy.agent", null);
        assertThat(agent.domains()).extracting(RepositoryDiscovery.Asked::state).containsExactly("found", "refused");
        assertThat(agent.domains().getLast().refusal()).contains("not an https location");

        assertThat(discovery.check("lib", null).domains()).as("a name that is no domain asks none").isEmpty();
    }

    @Test
    void a_path_of_no_shape_discovery_reads_asks_for_nothing() {
        RepositoryDiscovery discovery = discovery();

        assertThat(discovery.locate("/raw/a/b")).isEmpty();
        assertThat(discovery.locate("/maven/build/jenesis/../jenesis/x/1/x-1.jar")).isEmpty();
        assertThat(discovery.locate("/maven/build/jenesis/build.jenesis/0.20.0/other-0.20.0.jar")).isEmpty();
        assertThat(table.asked).isEmpty();
    }
}
