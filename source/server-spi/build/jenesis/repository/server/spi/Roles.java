package build.jenesis.repository.server.spi;

import module java.base;

/**
 * A tenant's named roles: a role bundles grant tokens under a friendly name, so a console can offer a role instead of
 * raw {@code <surface>:<verb>} tokens. Built-in defaults form a hierarchy - read-only reads, deploy adds writes,
 * admin grants everything - overlaid by the tenant's stored custom roles, one document per tenant in the credential
 * space. A custom role may override a default.
 */
public final class Roles {

    private final CredentialSpace space;

    Roles(CredentialSpace space) {
        this.space = space;
    }

    /** A tenant's roles by name, the built-in defaults overlaid with its stored custom roles. */
    public Map<String, String> of(String tenant) throws IOException {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("read-only", "cache:read,repository:read");
        roles.put("deploy", "cache:read,cache:write,repository:read,repository:write");
        roles.put("admin", "*");
        Properties stored = space.enforcing() ? space.read(path(tenant)) : null;
        if (stored != null) {
            for (String name : stored.stringPropertyNames()) {
                roles.put(name, stored.getProperty(name));
            }
        }
        return roles;
    }

    /** Add or replace a custom role on a tenant; a built-in name can be overridden. */
    public void set(String tenant, String name, String tokens) throws IOException {
        space.require();
        if (name == null || name.isBlank() || tokens == null || tokens.isBlank()) {
            throw new IllegalArgumentException("A role needs a name and tokens");
        }
        Properties stored = space.read(path(tenant));
        if (stored == null) {
            stored = new Properties();
        }
        stored.setProperty(name.trim(), tokens.trim());
        space.write(path(tenant), stored);
    }

    /** Remove a stored custom role (a built-in default reappears unless it was overriding one). */
    public void remove(String tenant, String name) throws IOException {
        space.require();
        Properties stored = space.read(path(tenant));
        if (stored == null) {
            return;
        }
        stored.remove(name);
        space.write(path(tenant), stored);
    }

    private static String path(String tenant) {
        return CredentialSpace.tenantDocument(tenant, "roles");
    }
}
