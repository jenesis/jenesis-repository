package build.jenesis.repository.compliance;

import module java.base;

/**
 * The operator override of the gate: a list of coordinates an administrator has decided must never be served,
 * independent of any feed. An entry is matched against a subject's {@code coordinate} and its
 * {@code coordinate:version}, so {@code com.evil:malware} blocks every version and {@code org.acme:lib:1.2.3} blocks
 * one; an entry ending in {@code *} is a prefix match, so {@code com.evil:*} blocks an entire group (or {@code evil-*}
 * a family of npm packages). A match yields {@code action} - {@link Verdict#REJECT} by default, the deterministic
 * "refuse this now" control - and can be softened to {@link Verdict#QUARANTINE}. An empty list passes everything.
 *
 * <p><b>{@link Verdict#ALLOW} evaluates and permits; it does not switch the dimension off</b> (the ruling
 * settled for the seven discovered {@link GatePolicyProvider} dimensions and this core one was outside the
 * reach of - it is not a discovered provider, so no fixture and no census covered it). A coordinate an entry matches
 * is still matched and still reported, as {@code Finding(ALLOW, "Deny-listed: <entry>")} - the shape
 * {@link ComplianceGate} already uses for a VEX-suppressed or waived advisory - so a reviewer can tell an operator's
 * deliberate "let this one through" from a coordinate no entry ever named. An <em>empty</em> list is the other thing
 * entirely and still reports nothing: that is the dimension having nothing configured to gate on, not a permit.
 */
public final class DenyListPolicy {

    private final List<String> denied;
    private final Verdict action;

    public DenyListPolicy(List<String> denied) {
        this(List.copyOf(denied), Verdict.REJECT);
    }

    private DenyListPolicy(List<String> denied, Verdict action) {
        this.denied = denied;
        this.action = action;
    }

    public DenyListPolicy action(Verdict action) {
        return new DenyListPolicy(denied, action);
    }

    List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject) {
        // An empty list is "nothing configured to gate on" and reports nothing. The ACTION never short-circuits the
        // match: ALLOW reports the permit rather than hiding it.
        if (denied.isEmpty()) {
            return List.of();
        }
        String coordinate = subject.coordinate();
        String versioned = coordinate + ":" + subject.version();
        for (String entry : denied) {
            if (matches(entry, coordinate) || matches(entry, versioned)) {
                return List.of(new ComplianceGate.Finding(action, "Deny-listed: " + entry));
            }
        }
        return List.of();
    }

    private static boolean matches(String entry, String coordinate) {
        return entry.endsWith("*")
                ? coordinate.startsWith(entry.substring(0, entry.length() - 1))
                : entry.equals(coordinate);
    }
}
