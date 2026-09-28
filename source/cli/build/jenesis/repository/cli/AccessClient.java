package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * Who may do what: the tenant's credentials and their lifetime policy, its members, groups, roles and OIDC trusts,
 * the issued login keys and the SCIM token, and the audit trail of what they did.
 *
 * <p>Reached through {@link RepositoryClient#access()}.
 */
public final class AccessClient extends ClientCalls {

    AccessClient(ClientCalls calls) {
        super(calls);
    }

    /** The tenant's credentials: each id with its label, expiry and use count (the grants are omitted from this view). */
    public List<Credential> credentials() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/credentials", null, null);
        require(response, 200, "list credentials");
        return List.of(JSON.readValue(response.body(), Credential[].class));
    }

    /** Mint a credential, returning its id and the secret key shown only once. */
    public Minted mint(String label) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher body = label == null || label.isBlank()
                ? body(Map.of())
                : body(Map.of("label", label));
        HttpResponse<String> response = send("POST", "/api/credentials", body, "application/json");
        require(response, 201, "mint a credential");
        return JSON.readValue(response.body(), Minted.class);
    }

    public void revoke(String id) throws IOException, InterruptedException {
        require(send("DELETE", "/api/credentials/" + id, null, null), 200, "revoke " + id);
    }

    /** Grant a credential a comma-separated set of tokens at a scope (a project/repository subtree, {@code *} for all). */
    public void setGrant(String id, String scope, List<String> tokens) throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope", scope);
        fields.put("tokens", tokens);
        require(send("POST", "/api/credentials/" + id + "/grants", body(fields), "application/json"),
                200, "grant " + scope + " on " + id);
    }

    public void removeGrant(String id, String scope) throws IOException, InterruptedException {
        require(send("DELETE", "/api/credentials/" + id + "/grants/" + enc(scope), null, null),
                200, "remove grant " + scope + " on " + id);
    }

    /** The tenant's groups, each with the rights it grants. */
    public List<Group> groups() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/groups", null, null);
        require(response, 200, "list groups");
        return List.of(JSON.readValue(response.body(), Group[].class));
    }

    /** One group's members, by provider-qualified id. */
    public List<String> groupMembers(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/groups/" + enc(name) + "/members", null, null);
        require(response, 200, "list members of " + name);
        return List.of(JSON.readValue(response.body(), String[].class));
    }

    /** Grant the group rights at a scope; every member holds them from the next request. */
    public void setGroupGrant(String name, String scope, List<String> tokens, String expires)
            throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope", scope);
        fields.put("tokens", tokens);
        fields.put("expires", expires);
        require(send("POST", "/api/groups/" + enc(name) + "/grants", body(fields), "application/json"),
                200, "grant " + scope + " on group " + name);
    }

    public void removeGroupGrant(String name, String scope) throws IOException, InterruptedException {
        require(send("DELETE", "/api/groups/" + enc(name) + "/grants/" + enc(scope), null, null),
                200, "remove grant " + scope + " on group " + name);
    }

    /** Put a principal in the group. The group need not exist first - one with members and no grants confers
     *  nothing, so there is no order in which an unmade decision reads as access. */
    public void addGroupMember(String name, String id) throws IOException, InterruptedException {
        require(send("POST", "/api/groups/" + enc(name) + "/members",
                        body(Map.of("id", id)), "application/json"),
                200, "add " + id + " to " + name);
    }

    /** Take a principal out of the group. The id rides as a query parameter because it carries a slash. */
    public void removeGroupMember(String name, String id) throws IOException, InterruptedException {
        require(send("DELETE", "/api/groups/" + enc(name) + "/members?id=" + enc(id), null, null),
                200, "remove " + id + " from " + name);
    }

    public void removeGroup(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/groups/" + enc(name), null, null), 200, "remove group " + name);
    }

    /** The tenant's people, each with the rights granted to them directly. */
    public List<Principal> principals() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/principals", null, null);
        require(response, 200, "list principals");
        return List.of(JSON.readValue(response.body(), Principal[].class));
    }

    /** Grant a person rights at a scope. The id is in the body because it carries a slash. */
    public void setPrincipalGrant(String id, String scope, List<String> tokens, String expires)
            throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("scope", scope);
        fields.put("tokens", tokens);
        fields.put("expires", expires);
        require(send("POST", "/api/principals/grants", body(fields), "application/json"),
                200, "grant " + scope + " to " + id);
    }

    public void removePrincipalGrant(String id, String scope) throws IOException, InterruptedException {
        require(send("DELETE", "/api/principals/grants?id=" + enc(id) + "&scope=" + enc(scope), null, null),
                200, "remove grant " + scope + " from " + id);
    }

    public void removePrincipal(String id) throws IOException, InterruptedException {
        require(send("DELETE", "/api/principals?id=" + enc(id), null, null), 200, "remove principal " + id);
    }

    /** Set or clear (blank) a credential's expiry: a leading {@code P} is an ISO-8601 duration from now, else an
     *  absolute instant. */
    public void setExpiry(String id, String expires) throws IOException, InterruptedException {
        require(send("PUT", "/api/credentials/" + id + "/expiry", body(Map.of("expires", expires == null ? "" : expires)),
                "application/json"), 200, "set the expiry of " + id);
    }

    /** Rotate a credential: mint a successor inheriting its grants and allowlist, returning its id and secret once,
     *  the old key expiring after the overlap (ISO-8601, default a week when {@code overlap} is blank). */
    public Minted rotate(String id, String overlap) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher body = overlap == null || overlap.isBlank()
                ? body(Map.of())
                : body(Map.of("overlap", overlap));
        HttpResponse<String> response = send("POST", "/api/credentials/" + id + "/rotate", body, "application/json");
        require(response, 201, "rotate " + id);
        return JSON.readValue(response.body(), Minted.class);
    }

    /** Set or clear (blank) a credential's source-IP allowlist (comma-separated CIDRs or addresses). */
    public void setAllowedAddresses(String id, String addresses) throws IOException, InterruptedException {
        require(send("PUT", "/api/credentials/" + id + "/allowed-ips",
                body(Map.of("addresses", addresses == null ? "" : addresses)), "application/json"),
                200, "set the allowlist of " + id);
    }

    /** The tenant's credential-lifetime policy: the default lifetime stamped on a blank-expiry mint and the optional
     *  ceiling beyond which no key may live ({@code null} when nothing caps it), both ISO-8601 durations. */
    public PolicyView policy() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/policy", null, null);
        require(response, 200, "read the credential policy");
        return JSON.readValue(response.body(), PolicyView.class);
    }

    /** Set (or clear with a blank value) the tenant's default and maximum credential lifetimes (ISO-8601 durations). */
    public void setPolicy(String defaultLifetime, String maxLifetime) throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("defaultLifetime", defaultLifetime == null ? "" : defaultLifetime);
        fields.put("maxLifetime", maxLifetime == null ? "" : maxLifetime);
        require(send("PUT", "/api/policy", body(fields), "application/json"), 200, "set the credential policy");
    }

    /** The tenant's named roles (name to comma-separated tokens): the built-in read-only/deploy/admin plus custom ones. */
    public Map<String, String> roles() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/roles", null, null);
        require(response, 200, "read the roles");
        Map<String, String> roles = new LinkedHashMap<>();
        if (JSON.readValue(response.body(), Object.class) instanceof Map<?, ?> parsed) {
            parsed.forEach((name, tokens) -> roles.put(String.valueOf(name), tokens == null ? "" : String.valueOf(tokens)));
        }
        return roles;
    }

    public void setRole(String name, String tokens) throws IOException, InterruptedException {
        require(send("PUT", "/api/roles/" + enc(name), body(Map.of("tokens", tokens)), "application/json"),
                200, "set role " + name);
    }

    public void removeRole(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/roles/" + enc(name), null, null), 200, "remove role " + name);
    }

    /** The tenant's OIDC trusts: each exchanges a matching id-token for a short-lived credential at {@code /api/token}. */
    public List<TrustView> trusts() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/trusts", null, null);
        require(response, 200, "read the trusts");
        return List.of(JSON.readValue(response.body(), TrustView[].class));
    }

    /** Add or replace an OIDC trust by name; issuer, scope and rights are required, the rest optional (blank omits). */
    public void setTrust(String name, String issuer, String audience, String subject, String scope, String rights,
                         String ttl) throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("issuer", issuer);
        if (audience != null) {
            fields.put("audience", audience);
        }
        if (subject != null) {
            fields.put("subject", subject);
        }
        fields.put("scope", scope);
        fields.put("rights", rights);
        if (ttl != null) {
            fields.put("ttl", ttl);
        }
        require(send("PUT", "/api/trusts/" + enc(name), body(fields), "application/json"), 200, "set trust " + name);
    }

    public void removeTrust(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/trusts/" + enc(name), null, null), 200, "remove trust " + name);
    }

    /** The tenant's audit trail, newest first, optionally bounded by ISO-8601 {@code from}/{@code to} instants and a
     *  single {@code action}; {@code null} when audit is not installed on this deployment (HTTP 501). */
    public List<AuditEvent> audit(String from, String to, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/audit" + auditQuery(from, to, action), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "read the audit trail");
        return List.of(JSON.readValue(response.body(), AuditEvent[].class));
    }

    /** The same audit trail as a CSV download for off-system retention, or {@code null} when audit is not installed. */
    public String auditCsv(String from, String to, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/audit.csv" + auditQuery(from, to, action), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "export the audit trail");
        return response.body();
    }

    private static String auditQuery(String from, String to, String action) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "from", from);
        appendParam(query, "to", to);
        appendParam(query, "action", action);
        return query.toString();
    }

    private static void appendParam(StringBuilder query, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        query.append(query.isEmpty() ? '?' : '&').append(name).append('=').append(enc(value));
    }

    /**
     * The deployment's issued login keys.
     *
     * <p>A login key can bind a principal into any tenant, so these are the operator's calls: the server holds
     * {@code /api/keylogin} to a manage key of the operator tenant, as it does every deployment-wide route. The
     * console's login keys screen issues and revokes through the same implementation.
     */
    public String keyLogins() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/keylogin", null, null);
        require(response, 200, "list the login keys");
        return response.body();
    }

    /** Issue a login key, binding {@code principal} into {@code tenant} at {@code role}; the key is returned once. */
    public String issueKeyLogin(String principal, String login, String tenant, String role)
            throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("principal", principal);
        fields.put("tenant", tenant);
        if (login != null) {
            fields.put("login", login);
        }
        if (role != null) {
            fields.put("role", role);
        }
        HttpResponse<String> response = send("POST", "/api/keylogin", body(fields), "application/json");
        require(response, 200, "issue the login key");
        return response.body();
    }

    /**
     * Revoke one issued login key.
     *
     * <p>Answered {@code 204}, so there is no body to return - which is the whole answer: the key is gone.
     * Revoking mattered most of the three, because a credential a script can mint and only a browser can withdraw
     * is one that outlives the incident that should have ended it.
     */
    public void revokeKeyLogin(String id) throws IOException, InterruptedException {
        require(send("POST", "/api/keylogin/" + enc(id) + "/delete",
                HttpRequest.BodyPublishers.noBody(), null), 204, "revoke the login key");
    }

    /**
     * Mint this tenant's SCIM bearer token and return the server's answer, which carries the secret once - the
     * store keeps only its hash, so there is no reading it back afterwards.
     */
    public String mintScimToken() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/scim/token",
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 201, "mint the SCIM token");
        return response.body();
    }

    public String clearScimToken() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/scim/token/clear",
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "clear the SCIM token");
        return response.body();
    }

    public record Credential(String id, String label, String expires, long useCount) {
    }

    /** One group as the API reports it: its name, its label and the rights it grants per scope. */
    public record Group(String name, String label, Map<String, String> grants) {
    }

    /** One person as the API reports them. {@code grants} is what they hold DIRECTLY; what they hold through a
     *  group is the group's, and is listed there. */
    public record Principal(String id, String label, Map<String, String> grants) {
    }

    public record Minted(String id, String key, String expires) {
    }

    /** The tenant's credential-lifetime policy: the default stamped on a blank-expiry mint and the optional ceiling
     *  beyond which no key may live ({@code null} when nothing caps it), both ISO-8601 durations. */
    public record PolicyView(String defaultLifetime, String maxLifetime) {
    }

    /** One OIDC trust: it exchanges a matching id-token for a short-lived credential at {@code /api/token}. */
    public record TrustView(String name, String issuer, String audience, String subject, String scope, String rights,
                            String ttl) {
    }

    /** One audit event, newest first: when it happened, who did it, the action and the target. */
    public record AuditEvent(String at, String actor, String action, String target) {
    }
}
