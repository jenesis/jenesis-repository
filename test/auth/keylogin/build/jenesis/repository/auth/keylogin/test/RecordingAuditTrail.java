package build.jenesis.repository.auth.keylogin.test;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;

/** An {@link AuditTrail} that captures every recorded event, so a test can assert a sign-in was audited. */
final class RecordingAuditTrail implements AuditTrail {

    record Recorded(String tenant, String actor, String action, String target) {
    }

    final List<Recorded> events = new ArrayList<>();

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void record(String tenant, String actor, String action, String target) {
        events.add(new Recorded(tenant, actor, action, target));
    }

    @Override
    public List<Event> query(String tenant, Instant from, Instant to, String action) {
        return List.of();
    }

    boolean has(String action, String actor) {
        return events.stream().anyMatch(e -> e.action().equals(action) && e.actor().equals(actor));
    }
}
