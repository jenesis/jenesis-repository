package build.jenesis.repository.scope;

import module java.base;

/**
 * The {@code anonymous-rights} grammar - the shape a credential's grants object has, written as one setting - read
 * once for every module that judges it: the authorization that enforces it and the security posture that warns about
 * it. A bare token is granted on the {@code *} (every-repository) scope; a {@code <scope>=<token>} entry, the scope a
 * repository name or a {@code <repository>:<prefix>} path scope, is granted on that scope. Blank entries, and an entry
 * naming no scope or no token, are skipped, so what is warned about is exactly what is enforced.
 */
public final class AnonymousGrants {

    private AnonymousGrants() {
    }

    /** The tokens each scope is granted, in the order the setting names them; empty for a blank or absent value. */
    public static Map<String, List<String>> parse(String rights) {
        if (rights == null || rights.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> grants = new LinkedHashMap<>();
        for (String element : rights.split(",")) {
            String entry = element.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int equals = entry.indexOf('=');
            String scope = equals < 0 ? "*" : entry.substring(0, equals).strip();
            String token = (equals < 0 ? entry : entry.substring(equals + 1)).strip();
            if (scope.isEmpty() || token.isEmpty()) {
                continue;
            }
            grants.computeIfAbsent(scope, _ -> new ArrayList<>()).add(token);
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        grants.forEach((scope, tokens) -> immutable.put(scope, List.copyOf(tokens)));
        return Collections.unmodifiableMap(immutable);
    }

    /** Whether the value lets a keyless caller write or administer: it grants the all-privileges {@code *}, any
     *  {@code <surface>:write} or {@code <surface>:*}, or any {@code manage:<verb>}. Anonymous read is a warning;
     *  this is the escalation. */
    public static boolean grantsWriteOrAdmin(String rights) {
        for (List<String> tokens : parse(rights).values()) {
            for (String token : tokens) {
                if (token.equals("*")) {
                    return true;
                }
                int colon = token.indexOf(':');
                String surface = colon < 0 ? token : token.substring(0, colon);
                String verb = colon < 0 ? "" : token.substring(colon + 1).strip();
                if (surface.equals("manage") || verb.equals("write") || verb.equals("*")) {
                    return true;
                }
            }
        }
        return false;
    }
}
