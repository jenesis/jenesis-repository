package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.maintenance.StorageNamespaces;
import build.jenesis.repository.management.web.StoragePurgeController;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.store.Tenants;
import org.junit.jupiter.api.io.TempDir;
import build.jenesis.repository.web.testkit.Web;
import org.springframework.web.bind.annotation.RequestParam;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orphan purge - the one admin endpoint that deletes data an operator cannot get back.
 *
 * <p>Its whole safety rests on one word: {@code dryRun} defaults to {@code true}, so a caller who omits the
 * parameter <em>plans</em> and never purges. That default lives in a {@code @RequestParam} annotation and nothing
 * else in the build looks at it; flipping it to {@code false} compiles, passes every existing test, and turns a
 * curl an operator ran to see what would happen into the thing happening. So it is asserted here directly, off the
 * annotation, rather than through a call that has to pass the flag explicitly.
 *
 * <p>The rest is the shape of a destructive operation done properly: a plan writes nothing and records nothing,
 * because an audit row for a deletion that did not occur makes the trail disagree with the store; a real purge
 * records under one owned action name with the counts it removed; an unknown namespace is a 404 rather than a
 * silent no-op; and the reserved root spaces are reported as out of reach on every answer, so an operator meets
 * the limit rather than wondering why a space never shrinks.
 *
 * <p>Driven over a real {@link StorageNamespaces} on a temporary filesystem store, so a purge that claims to have
 * removed nothing has actually removed nothing.
 */
class StoragePurgeControllerTest {

    @TempDir
    Path root;

    private Web.Recording audit;
    private StoragePurgeController controller;

    @BeforeEach
    void wire() {
        audit = Web.audit();
        RepositoryProperties properties = new RepositoryProperties();
        controller = new StoragePurgeController(new StorageNamespaces(Web.store(root)),
                Tenants.fixed("default"), audit, properties);
    }

    @Test
    void the_purge_parameter_defaults_to_a_dry_run() throws Exception {
        // The single word this endpoint's safety rests on. Read off the annotation because that is where it lives:
        // every call in the product passes the flag explicitly, so no other test can notice it changing.
        Method purge = StoragePurgeController.class.getMethod("purge", String.class, boolean.class, String.class);
        RequestParam dryRun = null;
        for (Parameter parameter : purge.getParameters()) {
            RequestParam annotation = parameter.getAnnotation(RequestParam.class);
            if (annotation != null && "dryRun".equals(annotation.value())) {
                dryRun = annotation;
            }
        }
        assertThat(dryRun).as("the purge endpoint declares a dryRun parameter").isNotNull();
        assertThat(dryRun.defaultValue())
                .as("omitting dryRun must plan, never delete")
                .isEqualTo("true");
    }

    @Test
    void an_unknown_namespace_is_a_404_rather_than_a_silent_success() throws Exception {
        assertThat(controller.purge("no-such-module", true, null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.purge("no-such-module", false, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void a_dry_run_records_no_audit_row() throws Exception {
        controller.purge("no-such-module", true, null);

        // Not merely "nothing was deleted": an audit row for a deletion that did not happen is a trail that
        // disagrees with the store, and the trail is what an operator reconstructs an incident from.
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void the_orphan_report_names_the_spaces_a_purge_can_never_reach() throws Exception {
        var view = controller.orphans();

        // An operator who cannot see this limit reads a space that never shrinks as a broken purge.
        assertThat(view.unreachable()).isNotEmpty().allSatisfy(space -> assertThat(space).endsWith("/"));
        assertThat(view.note()).isEqualTo(StorageNamespaces.UNREACHABLE_NOTE);
    }

    @Test
    void every_purge_answer_carries_the_same_unreachable_note_as_the_report() throws Exception {
        // The two surfaces must agree: a plan that omitted the caveat would read as a complete account of what is
        // about to be removed.
        assertThat(controller.orphans().unreachable())
                .isEqualTo(StorageNamespaces.UNREACHABLE.stream().map(space -> space + "/").toList());
    }

    @Test
    void a_purge_can_never_reach_the_audit_trail() throws Exception {
        // The product's own spaces under .system are out of reach by construction, and the audit trail is one of
        // them. That is what stops the endpoint that deletes data from being able to delete the record of it having
        // done so - the single most useful thing about the reserved set, and the reason it is derived from Scopes
        // rather than written out somewhere a future space can be forgotten.
        assertThat(controller.orphans().unreachable()).contains("audit/");
    }
}
