package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import org.springframework.util.MultiValueMap;

/**
 * A token-exchange request (RFC 8693 section 2.1) reduced to what the exchange acts on: the tenant whose trusts
 * judge the token, and the token. {@link #of} applies the request rules and refuses with the error code RFC 6749
 * section 5.2 and RFC 8693 section 2.2.2 name for each; a parameter sent without a value counts as omitted, and a
 * parameter the two documents do not define is ignored, as RFC 6749 section 3.2 requires of both.
 *
 * <p>No refusal quotes a value the client sent: a description is fixed text, so neither a token nor an attacker's
 * string reaches the response.
 *
 * @param tenant       the tenant the request names by {@code tenant}, {@code audience} or {@code resource}, or
 *                     {@code null} when it names none
 * @param subjectToken the token to exchange
 */
record TokenRequest(String tenant, String subjectToken) {

    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange",
            ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token",
            ID_TOKEN = "urn:ietf:params:oauth:token-type:id_token",
            JWT = "urn:ietf:params:oauth:token-type:jwt";

    /** The parameters whose repetition is refused: the ones this endpoint reads. One a request repeats while the
     *  endpoint ignores it changes nothing. */
    private static final Set<String> SINGLE = Set.of("grant_type", "subject_token", "subject_token_type",
            "requested_token_type", "scope", "actor_token", "actor_token_type");

    /** A refused request: the {@code error} code and its {@code error_description}. */
    static final class Refused extends Exception {

        private final String error;

        Refused(String error, String description) {
            super(description);
            this.error = error;
        }

        String error() {
            return error;
        }
    }

    /**
     * The request {@code parameters} describe, or the refusal they earn.
     *
     * <ul>
     * <li>{@code grant_type} is required and names the token-exchange grant; another one is
     *     {@code unsupported_grant_type}.</li>
     * <li>{@code subject_token} and {@code subject_token_type} are required; the type is an id-token or a JWT, which
     *     is what an id-token is, and any other is {@code invalid_request}.</li>
     * <li>{@code requested_token_type}, when sent, names an access token - the one kind issued.</li>
     * <li>{@code actor_token} and {@code actor_token_type} are {@code invalid_request}: no delegation is recorded,
     *     so accepting an actor would issue a key that silently drops it.</li>
     * <li>{@code scope} is {@code invalid_scope}: what a key may do is the matching trust's grant, and no scope
     *     value is defined that could narrow it.</li>
     * <li>The target is a tenant, named in any of three ways that may be combined and repeated while they agree:
     *     {@code resource}, the URL the key will be used against, which {@code located} reads a tenant from;
     *     {@code audience}, the tenant's name as the logical name of the target; and {@code tenant}, the same name
     *     under this server's own parameter. A resource that names no tenant, a name that is not a tenant name and
     *     targets of different tenants are {@code invalid_target}.</li>
     * </ul>
     *
     * @param located the tenant a {@code resource} names, empty when it names none.
     */
    static TokenRequest of(MultiValueMap<String, String> parameters,
                           Function<String, Optional<String>> located) throws Refused {
        for (String name : SINGLE) {
            if (values(parameters, name).size() > 1) {
                throw new Refused("invalid_request", "The parameter " + name + " is sent more than once");
            }
        }
        String grantType = single(parameters, "grant_type");
        if (grantType == null) {
            throw new Refused("invalid_request", "The parameter grant_type is required");
        }
        if (!grantType.equals(GRANT_TYPE)) {
            throw new Refused("unsupported_grant_type", "The only grant type is " + GRANT_TYPE);
        }
        String subjectToken = single(parameters, "subject_token");
        if (subjectToken == null) {
            throw new Refused("invalid_request", "The parameter subject_token is required");
        }
        String subjectTokenType = single(parameters, "subject_token_type");
        if (subjectTokenType == null) {
            throw new Refused("invalid_request", "The parameter subject_token_type is required");
        }
        if (!subjectTokenType.equals(ID_TOKEN) && !subjectTokenType.equals(JWT)) {
            throw new Refused("invalid_request", "The subject_token_type is " + ID_TOKEN + " or " + JWT);
        }
        if (single(parameters, "actor_token") != null || single(parameters, "actor_token_type") != null) {
            throw new Refused("invalid_request", "An actor token is not supported");
        }
        String requestedTokenType = single(parameters, "requested_token_type");
        if (requestedTokenType != null && !requestedTokenType.equals(ACCESS_TOKEN)) {
            throw new Refused("invalid_request", "The only requested_token_type is " + ACCESS_TOKEN);
        }
        if (single(parameters, "scope") != null) {
            throw new Refused("invalid_scope", "No scope is defined: a key carries the grant of the trust that "
                    + "admitted the token");
        }
        // The tenant scopes the credential store an exchange reads and, on a trusted match, mints into. A client
        // supplies it, so a traversal-unsafe name is refused before it can reach a store key.
        Set<String> tenants = new LinkedHashSet<>();
        for (String name : List.of("tenant", "audience")) {
            for (String tenant : values(parameters, name)) {
                if (!Scopes.valid(tenant.trim())) {
                    throw new Refused("invalid_target", "The " + name + " is not a tenant name");
                }
                tenants.add(tenant.trim());
            }
        }
        for (String resource : values(parameters, "resource")) {
            tenants.add(located.apply(resource.trim()).orElseThrow(() -> new Refused("invalid_target",
                    "A resource is a URL of this server that names a tenant")));
        }
        if (tenants.size() > 1) {
            throw new Refused("invalid_target", "A key is issued for one tenant: the targets name several");
        }
        String named = tenants.isEmpty() ? null : tenants.iterator().next();
        return new TokenRequest(named, subjectToken);
    }

    /** The values of {@code name} that carry a value. */
    private static List<String> values(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null
                ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        List<String> values = values(parameters, name);
        return values.isEmpty() ? null : values.getFirst().trim();
    }
}
