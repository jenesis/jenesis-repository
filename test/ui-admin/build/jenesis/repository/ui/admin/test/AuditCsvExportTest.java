package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.ui.admin.web.AuditController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's audit export is the API's: the same rows, the same guard that keeps a value from running as a
 * spreadsheet formula, a time window, and {@code 501} on a deployment with no audit module - where the console's own
 * copy streamed an empty file.
 */
class AuditCsvExportTest {

    private static final Instant AT = Instant.parse("2026-10-03T12:00:00Z");

    @Test
    void the_console_export_escapes_bounds_and_says_when_no_trail_is_installed() throws IOException {
        AuditTrail trail = new AuditTrail() {

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void record(String tenant, String actor, String action, String target) {
            }

            @Override
            public List<Event> query(String tenant, Instant from, Instant to, String action) {
                return Stream.of(new Event(AT, "=SUM(A1)", "credential.mint", "a,b"),
                                new Event(AT.minusSeconds(3600), "ada", "credential.mint", "old"))
                        .filter(event -> from == null || !event.at().isBefore(from))
                        .toList();
            }
        };
        Servlets.Response exported = Servlets.response();
        new AuditController(trail, () -> "acme").csv(AT.minusSeconds(60).toString(), null, null, exported.servlet());

        assertThat(exported.body()).isEqualTo("at,actor,action,target\n"
                + AT + ",'=SUM(A1),credential.mint,\"a,b\"\n");

        Servlets.Response missing = Servlets.response();
        new AuditController(AuditTrail.none(), () -> "acme").csv(null, null, null, missing.servlet());
        assertThat(missing.status()).isEqualTo(501);
    }
}
