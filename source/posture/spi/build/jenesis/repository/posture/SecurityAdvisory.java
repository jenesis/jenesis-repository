package build.jenesis.repository.posture;

import module java.base;

/**
 * One security-posture advisory: a potentially unsafe configuration a module wants an operator to know about. It
 * carries a stable {@link #id} (the {@link Advisories} grammar, validated at construction), a {@link #severity}, a
 * {@link #scope} with its {@link #tenant}, a {@link #title}, {@link #why} it is unsafe, a safer {@link #fix}, the exact
 * setting to change ({@link #settingKey} / {@link #settingValue}) and a {@link #docs} link.
 *
 * <p><strong>The advisory names the risk, never the secret</strong>: its text never embeds a credential or any read
 * value, so the posture surface cannot leak one.
 */
public record SecurityAdvisory(String id, Severity severity, Scope scope, String tenant, String title, String why,
                               String fix, String settingKey, String settingValue, String docs) {

    public SecurityAdvisory {
        Advisories.require(id);
        severity = Objects.requireNonNull(severity, "severity");
        scope = Objects.requireNonNull(scope, "scope");
        tenant = tenant == null ? "" : tenant;
        title = Objects.requireNonNull(title, "title");
        why = Objects.requireNonNull(why, "why");
        fix = Objects.requireNonNull(fix, "fix");
        settingKey = settingKey == null ? "" : settingKey;
        settingValue = settingValue == null ? "" : settingValue;
        docs = docs == null ? "" : docs;
        if (scope == Scope.TENANT && tenant.isBlank()) {
            throw new IllegalArgumentException("A tenant-scoped advisory must name its tenant: " + id);
        }
        if (scope == Scope.DEPLOYMENT && !tenant.isBlank()) {
            throw new IllegalArgumentException("A deployment-wide advisory must not name a tenant: " + id);
        }
    }

    /** A deployment-wide advisory. */
    public static SecurityAdvisory deployment(String id, Severity severity, String title, String why, String fix,
                                              String settingKey, String settingValue, String docs) {
        return new SecurityAdvisory(id, severity, Scope.DEPLOYMENT, "", title, why, fix, settingKey, settingValue, docs);
    }

    /** A tenant-scoped advisory, concerning {@code tenant}. */
    public static SecurityAdvisory tenant(String id, Severity severity, String tenant, String title, String why,
                                          String fix, String settingKey, String settingValue, String docs) {
        return new SecurityAdvisory(id, severity, Scope.TENANT, tenant, title, why, fix, settingKey, settingValue, docs);
    }
}
