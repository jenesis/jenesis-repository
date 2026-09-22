package build.jenesis.repository.compliance;

import module java.base;

/**
 * One accept-risk waiver: an operator's time-boxed decision to accept a known finding on a specific coordinate rather
 * than let the gate hold or reject it. It names the vulnerability it concerns (its primary id and any aliases), the
 * exact subject coordinate it applies to, when it was {@code granted} and when it {@code expires}, and the human
 * {@code reason} recorded with it - so a gate suppression can name why the flaw was let through and until when. It is
 * the ecosystem-neutral shape the gate reads through {@link Waivers}, projected out of the finding annotation that
 * durably records it (the {@code accept-risk} label on the finding), so the persisted ledger stays the single source of
 * truth and this is only the matcher's view of it.
 *
 * <p>Matching mirrors {@link VexStatement}'s two independent questions: {@link #covers} - does this waiver speak to the
 * advisory (by id or a shared alias) - and {@link #appliesTo} - does it speak to the subject coordinate. Both are pure
 * and case-insensitive on identifiers. A waiver is honoured only while {@link #active}: an expired one suppresses
 * nothing, so an accept-risk decision auto-lapses without any sweep having to retract it.
 */
public record Waiver(String vulnerability, List<String> aliases, String ecosystem, String coordinate, String version,
                     Instant granted, Instant expires, String reason) {

    public Waiver {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** Whether this waiver still stands at {@code now}: it has an expiry and that expiry is not yet past. A waiver with
     *  no expiry never activates (a time-boxed exception is expiry-bounded by contract), so a malformed one can never
     *  blanket-suppress a coordinate's advisories. */
    public boolean active(Instant now) {
        return expires != null && expires.isAfter(now);
    }

    /** Whether this waiver speaks to an advisory: its vulnerability id or any of its aliases matches the advisory's own
     *  id or one of its {@code cves} aliases, compared case-insensitively (a CVE named by OSV and a GHSA alias both
     *  resolve), exactly as a VEX statement covers an advisory. */
    public boolean covers(String advisoryId, List<String> advisoryAliases) {
        Set<String> mine = identifiers(vulnerability, aliases);
        Set<String> theirs = identifiers(advisoryId, advisoryAliases);
        for (String identifier : mine) {
            if (theirs.contains(identifier)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this waiver applies to a subject coordinate: the ecosystem and coordinate match case-insensitively and,
     *  when the waiver pins a version, that version equals the subject's. A blank waiver version applies to every
     *  version of the coordinate; a blank coordinate applies to nothing, so an incomplete waiver never over-reaches. */
    public boolean appliesTo(String subjectEcosystem, String subjectCoordinate, String subjectVersion) {
        if (coordinate == null || coordinate.isBlank()) {
            return false;
        }
        if (ecosystem != null && !ecosystem.isBlank()
                && !ecosystem.equalsIgnoreCase(subjectEcosystem)) {
            return false;
        }
        if (!coordinate.equalsIgnoreCase(subjectCoordinate)) {
            return false;
        }
        return version == null || version.isBlank() || version.equalsIgnoreCase(subjectVersion);
    }

    /** This waiver's grant instant, or {@link Instant#EPOCH} when it carries none - so the newest waiver for a
     *  (vulnerability, coordinate) pair can be picked without a null check. */
    public Instant when() {
        return granted == null ? Instant.EPOCH : granted;
    }

    private static Set<String> identifiers(String primary, List<String> aliases) {
        Set<String> identifiers = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            identifiers.add(primary.strip().toUpperCase(Locale.ROOT));
        }
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                identifiers.add(alias.strip().toUpperCase(Locale.ROOT));
            }
        }
        return identifiers;
    }
}
