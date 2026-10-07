package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ContentScanner;
import build.jenesis.repository.compliance.ContentScanner.Bill;
import build.jenesis.repository.compliance.ContentScanner.Collected;
import build.jenesis.repository.compliance.ContentScanner.Fidelity;
import build.jenesis.repository.compliance.ContentScanner.Report;
import build.jenesis.repository.compliance.ContentScanner.Submitted;
import build.jenesis.repository.compliance.Severity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A chain scans as one scanner: the cataloguer is asked for its bill and for a catalogue in the format the chain was
 * joined in, the matcher is handed that very catalogue, and the report carries the matcher's advisories beside the
 * cataloguer's bill and catalogue - whether the cataloguer answers as it is asked or is collected later. A cataloguer
 * that makes no catalogue fails the scan; a matcher out of reach is an outage, the cataloguer collected again after.
 */
class ScannerChainTest {

    private static final String SYFT_JSON = "application/vnd.syft+json";

    private static final String CYCLONEDX = "application/vnd.cyclonedx+json";

    private static final Bill BILL = new Bill(CYCLONEDX, "{\"bomFormat\":\"CycloneDX\"}".getBytes(
            StandardCharsets.UTF_8));

    private static final Bill CATALOGUE = new Bill(SYFT_JSON, "{\"artifacts\":[]}".getBytes(StandardCharsets.UTF_8));

    private static final AdvisorySource.Advisory FOUND = new AdvisorySource.Advisory("CVE-2026-1234",
            Severity.HIGH, false, null, List.of("CVE-2026-1234"), "found by matching", List.of());

    private static final ContentScanner.Request REQUEST = new ContentScanner.Request("http://jenesis:8080", null,
            "default/images/team/app", "sha256:" + "a".repeat(64), "application/vnd.oci.image.manifest.v1+json",
            CYCLONEDX, null);

    private final StandInScanner syft = StandInScanner.cataloguer("syft", "syft-command", SYFT_JSON, CYCLONEDX);

    private final StandInScanner grype = StandInScanner.matcher("grype", "grype-command",
            Map.of(SYFT_JSON, Fidelity.FULL, CYCLONEDX, Fidelity.LOSSY));

    @Test
    void a_cataloguer_answering_as_asked_is_matched_at_once_over_the_catalogue_it_made() throws IOException {
        syft.submission.set(new Submitted.Done(new Report("syft 1.0", List.of(), BILL, CATALOGUE)));
        grype.match.set(List.of(FOUND));

        Submitted submitted = session().submit(REQUEST);

        assertThat(syft.submitted).singleElement().satisfies(asked -> {
            assertThat(asked.bill()).as("the bill the host asked for").isEqualTo(CYCLONEDX);
            assertThat(asked.catalogue()).as("and the catalogue its matcher reads in full").isEqualTo(SYFT_JSON);
            assertThat(asked.digest()).isEqualTo(REQUEST.digest());
        });
        assertThat(grype.matched).as("the matcher is handed the catalogue made").containsExactly(CATALOGUE);
        assertThat(submitted).isInstanceOfSatisfying(Submitted.Done.class, done -> {
            assertThat(done.report().scanner()).isEqualTo("syft 1.0 > grype 1.0");
            assertThat(done.report().advisories()).containsExactly(FOUND);
            assertThat(done.report().bill()).as("the bill attached is the cataloguer's").isEqualTo(BILL);
            assertThat(done.report().catalogue()).as("kept beside what was matched").isEqualTo(CATALOGUE);
        });
    }

    @Test
    void a_cataloguer_collected_later_is_matched_as_its_report_comes_in() throws IOException {
        ContentScanner.Session session = session();
        assertThat(session.submit(REQUEST)).isEqualTo(new Submitted.Accepted("scan-1"));

        assertThat(session.collect("scan-1", CYCLONEDX, null, false)).isInstanceOf(Collected.Running.class);
        assertThat(grype.matched).as("nothing to match while it runs").isEmpty();

        syft.collection.set(new Collected.Done(new Report("syft 1.0", List.of(), BILL, CATALOGUE)));
        grype.match.set(List.of(FOUND));
        assertThat(session.collect("scan-1", CYCLONEDX, null, false)).isInstanceOfSatisfying(Collected.Done.class,
                done -> assertThat(done.report().advisories()).containsExactly(FOUND));
        assertThat(syft.collected).as("each ask names the chain's catalogue, whatever the host passed")
                .allSatisfy(asked -> assertThat(asked).containsExactly("scan-1", CYCLONEDX, SYFT_JSON));
    }

    @Test
    void a_cataloguer_that_makes_no_catalogue_fails_the_scan_rather_than_reporting_it_clean() throws IOException {
        syft.collection.set(new Collected.Done(new Report("syft 1.0", List.of(), BILL, null)));

        assertThat(session().collect("scan-1", CYCLONEDX, null, false)).isInstanceOfSatisfying(Collected.Failed.class,
                failed -> assertThat(failed.reason()).contains("no " + SYFT_JSON + " catalogue").contains("grype"));
        assertThat(grype.matched).isEmpty();

        syft.submission.set(new Submitted.Done(new Report("syft 1.0", List.of(), BILL, null)));
        assertThatThrownBy(() -> session().submit(REQUEST)).isInstanceOf(ContentScanner.Refused.class);
    }

    @Test
    void a_matcher_out_of_reach_is_an_outage_and_the_cataloguer_is_collected_again() throws IOException {
        syft.collection.set(new Collected.Done(new Report("syft 1.0", List.of(), BILL, CATALOGUE)));
        grype.match.set(new IOException("grype's database is being replaced"));
        ContentScanner.Session session = session();

        assertThatThrownBy(() -> session.collect("scan-1", CYCLONEDX, null, false))
                .isNotInstanceOf(ContentScanner.Refused.class).hasMessageContaining("being replaced");

        grype.match.set(List.of());
        assertThat(session.collect("scan-1", CYCLONEDX, null, false)).isInstanceOfSatisfying(Collected.Done.class,
                done -> assertThat(done.report().advisories()).as("a clean match").isEmpty());
        assertThat(syft.collected).hasSize(2);
    }

    @Test
    void a_matcher_the_cataloguer_must_answer_lossily_is_handed_what_it_reads_partially() throws IOException {
        StandInScanner partial = StandInScanner.matcher("partial", "grype-command", Map.of(CYCLONEDX,
                Fidelity.LOSSY));
        syft.submission.set(new Submitted.Done(new Report("syft 1.0", List.of(), BILL, BILL)));
        ContentScanner chain = ContentScanner.selected(List.of(syft, partial), key -> switch (key) {
            case ContentScanner.SETTING -> "syft>partial";
            case ContentScanner.LOSSY -> "true";
            default -> null;
        }, _ -> "set", ContentScanner.Input.IMAGE_MANIFEST).getFirst();

        Submitted submitted = chain.open(_ -> "set").submit(REQUEST);

        assertThat(syft.submitted).singleElement().satisfies(asked -> assertThat(asked.catalogue())
                .isEqualTo(CYCLONEDX));
        assertThat(partial.matched).containsExactly(BILL);
        assertThat(((Submitted.Done) submitted).report().coverage()).as("a lossy match never reads as a whole one")
                .satisfies(coverage -> {
                    assertThat(coverage.completeness()).isEqualTo(ContentScanner.Completeness.PARTIAL);
                    assertThat(coverage.gaps()).singleElement().asString().contains("partial")
                            .contains(CYCLONEDX).contains("only in part");
                });
    }

    @Test
    void what_the_cataloguer_could_not_read_stands_in_the_chains_report() throws IOException {
        syft.submission.set(new Submitted.Done(new Report("syft 1.0", List.of(), BILL, CATALOGUE,
                ContentScanner.Coverage.of(ContentScanner.Completeness.NOT_CATALOGUED,
                        "no package of a type syft reads"))));

        Submitted submitted = session().submit(REQUEST);

        assertThat(((Submitted.Done) submitted).report().coverage()).isEqualTo(ContentScanner.Coverage.of(
                ContentScanner.Completeness.NOT_CATALOGUED, "no package of a type syft reads"));
        assertThat(ContentScanner.Completeness.PARTIAL.and(ContentScanner.Completeness.COMPLETE))
                .as("the lesser of two").isEqualTo(ContentScanner.Completeness.PARTIAL);
    }

    @Test
    void a_chain_hands_an_archive_to_its_cataloguer_alone_and_a_scanner_taking_none_refuses_it() throws IOException {
        StandInScanner archiver = StandInScanner.archiver("syft", "syft-command", SYFT_JSON, CYCLONEDX);
        ContentScanner chain = ContentScanner.selected(List.of(archiver, grype), _ -> null, _ -> "set",
                ContentScanner.Input.ARCHIVE).getFirst();
        ContentScanner.Archive archive = new ContentScanner.Archive(Path.of("app-1.0.war"), "app-1.0.war", CYCLONEDX);

        assertThat(chain.name()).as("chained by default, and handed archives as its cataloguer is")
                .isEqualTo("syft>grype");
        ContentScanner.Report report = chain.open(_ -> "set").catalogue(archive);

        assertThat(report.bill().format()).isEqualTo(CYCLONEDX);
        assertThat(report.advisories()).isEmpty();
        assertThat(archiver.catalogued).containsExactly(archive);
        assertThat(grype.matched).as("a published archive is known through its closure, never matched").isEmpty();
        assertThatThrownBy(() -> syft.open(_ -> "set").catalogue(archive)).isInstanceOf(ContentScanner.Refused.class)
                .hasMessageContaining("handed no archive");
    }

    @Test
    void a_chain_is_handed_no_bill_of_its_own() {
        assertThatThrownBy(() -> session().match(CATALOGUE)).isInstanceOf(ContentScanner.Refused.class);
    }

    @Test
    void a_bill_is_a_value_its_holder_cannot_change() {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        Bill bill = new Bill(CYCLONEDX, bytes);
        bytes[0] = 'x';
        bill.document()[1] = 'x';

        assertThat(new String(bill.document(), StandardCharsets.UTF_8)).as("neither the array given nor the one "
                + "handed out reaches it").isEqualTo("{}");
        assertThat(bill).isEqualTo(new Bill(CYCLONEDX, "{}".getBytes(StandardCharsets.UTF_8)))
                .hasSameHashCodeAs(new Bill(CYCLONEDX, "{}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(new Bill(SYFT_JSON, "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(bill.length()).isEqualTo(2);
    }

    private ContentScanner.Session session() {
        return ContentScanner.selected(List.of(syft, grype), key -> ContentScanner.SETTING.equals(key)
                ? "syft>grype" : null, _ -> "set", ContentScanner.Input.IMAGE_MANIFEST).getFirst().open(_ -> "set");
    }
}
