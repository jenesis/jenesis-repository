package build.jenesis.repository.gate.test;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * A test-only {@link RepositoryFormat}, discovered by name through {@code ServiceLoader}, that stands in for a hosted
 * format whose own {@code handle} re-publishes an upload through the {@link Publication} (Maven's does). It exists
 * only to be replayed by {@link build.jenesis.repository.gate.store.HoldLifecycle#release} through
 * {@link build.jenesis.repository.gate.store.ComplianceScreen#replaying}: its {@link #handle} restreams the released body
 * back through {@code new Publication(store).screen(...)} - the same discovered {@code ComplianceScreen} an ordinary
 * publish drives - and records the disposition that inner screen reached, so a test can prove the screen was suppressed
 * (accepted the already-released bytes rather than re-quarantining them) during the replay. It claims no serving path
 * ({@link #handles} is always {@code false}); it is only ever reached by {@link RepositoryFormat#installed name}.
 */
public final class GateReplayTestFormat implements RepositoryFormat {

    /** The dispatch {@code format} name a test records so the release replay resolves this format. */
    static final String NAME = "gatetestreplay";

    /** The disposition the discovered screen reached on the most recent replayed re-publish - {@code ACCEPT} while the
     *  screen is suppressed, whatever the wired gate would otherwise return. Static because {@code ServiceLoader}
     *  constructs its own instance; a test reads it back after driving the release. */
    private static final AtomicReference<PublishInterceptor.Disposition> LAST_REPLAY = new AtomicReference<>();

    /** Clear the captured disposition between tests. */
    static void reset() {
        LAST_REPLAY.set(null);
    }

    /** The disposition the discovered screen reached on the last replayed re-publish, or {@code null} if none ran. */
    static PublishInterceptor.Disposition lastReplayDisposition() {
        return LAST_REPLAY.get();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean handles(String path) {
        return false;   // never serves; the release replay reaches it only by name through installed()
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        byte[] body;
        try (InputStream in = exchange.requestStream()) {
            body = in.readAllBytes();
        }
        // Re-publish the released body through the discovered ComplianceScreen exactly as a real format's handle does.
        // While HoldLifecycle.release drives this through ComplianceScreen.replaying, the screen is suppressed on this
        // thread, so this must ACCEPT the already-released bytes rather than re-screen them into a fresh quarantine.
        Publication.Published outcome = new Publication(store).screen(
                ArtifactDescriptor.at("test", exchange.path()), new ByteArrayInputStream(body));
        LAST_REPLAY.set(outcome.disposition());
        exchange.respond(200);
    }
}
