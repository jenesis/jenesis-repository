package build.jenesis.repository.posture;

import module java.base;
import build.jenesis.repository.scope.AnonymousGrants;

/**
 * The {@link SafetyAdvisor} for the deployment-cross-cutting advisories - properties of the whole deployment rather
 * than of one feature (authorization off, the dev profile, the import screen off, no rate limit, a writable demo,
 * anonymous rights). Each is grounded in a real {@code jenrepo.*} or Spring key, so the fix can be copied as written.
 */
public final class SecurityPosture implements SafetyAdvisor {

    /** Where the reference documents each advisory's condition and fix; each advisory anchors on its id. */
    static final String DOCS = "https://jenesis.build/repository/operations/";

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        List<SecurityAdvisory> advisories = new ArrayList<>();

        // Per-credential authorization disabled: every request is served anonymously. This is the single source of the
        // boot "running ANONYMOUS/OPEN" warning.
        if (!config.flag("jenrepo.auth", true)) {
            advisories.add(SecurityAdvisory.deployment("jenrepo.auth.open", Severity.CRITICAL,
                    "Authorization is disabled - the instance is fully open",
                    "jenrepo.auth=false serves every request anonymously: anyone on the network can read, "
                            + "publish, delete and administer artifacts with no credential.",
                    "Enforce per-credential authorization and grant each client only the repositories and actions it "
                            + "needs. Anonymous read is fine for a public mirror, but keep writes and admin key-gated.",
                    "jenrepo.auth", "true", DOCS + "#jenrepo.auth.open"));
        }

        // Import screen disabled: the one dial covers both halves, so an import URL may reach internal addresses AND
        // travel in cleartext with the upstream credentials attached. The advisory names both.
        if (!config.flag("jenrepo.block-private-import-hosts", true)) {
            advisories.add(SecurityAdvisory.deployment("jenrepo.importer.ssrf", Severity.WARN,
                    "Import screen is disabled",
                    "jenrepo.block-private-import-hosts=false lets an import fetch from private/loopback "
                            + "addresses, so a caller can use the importer to reach internal services (an SSRF pivot), "
                            + "and lets a migration run over plaintext http with the upstream credentials attached - "
                            + "especially dangerous on a multi-tenant or anonymous instance.",
                    "Keep the screen on; open it only for a controlled internal or plaintext on-premises migration "
                            + "and close it again afterwards.",
                    "jenrepo.block-private-import-hosts", "true", DOCS + "#jenrepo.importer.ssrf"));
        }

        // No rate limit. Only an explicit 0 switches the limiter off - unset, or unparseable, is the server's default
        // ceiling - so the advisory reads the value as set.
        if (config.optional("jenrepo.rate-limit").isPresent() && config.number("jenrepo.rate-limit", 1) <= 0) {
            advisories.add(SecurityAdvisory.deployment("jenrepo.ratelimit.unset", Severity.WARN,
                    "The request rate limit is switched off",
                    "jenrepo.rate-limit is 0 (unlimited), so a public instance has no per-credential throttle and a "
                            + "single client can saturate it (a brute-force or denial-of-service vector).",
                    "Set a sensible per-credential requests-per-minute ceiling; a small limit stops abuse while leaving "
                            + "normal build traffic untouched.",
                    "jenrepo.rate-limit", "600", DOCS + "#jenrepo.ratelimit.unset"));
        }

        // No advisory about jenrepo.ui.admins=*: both consoles refuse that value at startup, so no running deployment
        // carries it.

        // The dev security profile replaces the production chain with a permissive local-only one (form login,
        // in-memory users).
        if (springProfiles(config).contains("dev")) {
            advisories.add(SecurityAdvisory.deployment("jenrepo.profile.dev", Severity.CRITICAL,
                    "The 'dev' security profile is active",
                    "spring.profiles.active includes 'dev', so the console runs its local-only development security "
                            + "(permissive form login with in-memory users) instead of the production OAuth2/OIDC chain "
                            + "- an authentication bypass anywhere but a developer's machine.",
                    "Remove 'dev' from the active profiles in any shared or production deployment and configure a real "
                            + "login provider (OIDC/SSO or GitHub).",
                    "spring.profiles.active", "<remove dev>", DOCS + "#jenrepo.profile.dev"));
        }

        // Anonymous rights under an enforcing deployment grant a keyless caller a defined set of rights. Read-only is a
        // WARN, write or any manage right a CRITICAL. Silent when unset, or under auth=false where jenrepo.auth.open
        // applies.
        if (config.isSet("jenrepo.anonymous-rights") && config.flag("jenrepo.auth", true)) {
            if (AnonymousGrants.grantsWriteOrAdmin(config.value("jenrepo.anonymous-rights"))) {
                advisories.add(SecurityAdvisory.deployment("jenrepo.anonymous.write", Severity.CRITICAL,
                        "Anonymous callers may write or administer",
                        "jenrepo.anonymous-rights grants a keyless caller write and/or manage/admin rights, "
                                + "so anyone on the network can mutate or administer artifacts with no credential (a "
                                + "public drop-box / open admin) - the loudest anonymous combination.",
                        "Grant the anonymous caller read only (repository:read) for a public mirror and keep writes and "
                                + "admin key-gated, or unset anonymous-rights to require a key for every request.",
                        "jenrepo.anonymous-rights", "repository:read", DOCS + "#jenrepo.anonymous.write"));
            } else {
                advisories.add(SecurityAdvisory.deployment("jenrepo.anonymous.enabled", Severity.WARN,
                        "Anonymous read access is enabled",
                        "jenrepo.anonymous-rights grants a keyless caller a defined set of rights, so a "
                                + "request with no credential is served against that grant instead of being rejected - "
                                + "the public-mirror pattern, which must be a deliberate choice.",
                        "Intended for a public read-only mirror; confirm the grant is read-only and pair it with "
                                + "jenrepo.read-only=true so the mirror is browsable but immutable.",
                        "jenrepo.read-only", "true", DOCS + "#jenrepo.anonymous.enabled"));
            }
        }

        return advisories;
    }

    /** The active Spring profiles as a lowercase set, read from {@code spring.profiles.active} (comma-separated). */
    private static Set<String> springProfiles(Configuration config) {
        return config.optional("spring.profiles.active")
                .map(value -> Arrays.stream(value.split(","))
                        .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                        .filter(profile -> !profile.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
    }
}
