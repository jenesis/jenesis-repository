package build.jenesis.repository.auth.ldap;

import module java.base;
import org.springframework.boot.context.properties.ConfigurationProperties;
import build.jenesis.repository.scope.Scopes;

/**
 * The directory, read at boot from {@code jenreg.ui.ldap.*}.
 *
 * <p>A person is found either by a DN pattern ({@code user-dn-pattern}, {@code {0}} standing for the name they typed)
 * or by a search ({@code user-search-base} and {@code user-search-filter}), which Active Directory needs and which
 * runs as {@code bind-dn} when the directory refuses anonymous searches. Their groups are the values of
 * {@code group-name-attribute} on the entries {@code group-search-filter} finds under {@code group-search-base},
 * {@code {0}} standing for their DN and {@code {1}} for the name they typed.
 */
@ConfigurationProperties("jenreg.ui.ldap")
public class LdapProperties {

    private String url = "";
    private String userDnPattern = "";
    private String userSearchBase = "";
    private String userSearchFilter = "(uid={0})";
    private String bindDn = "";
    private String bindPassword = "";
    private String groupSearchBase = "";
    private String groupSearchFilter = "(member={0})";
    private String groupNameAttribute = "cn";
    private String adminGroup = "";
    private String tenants = Scopes.DEFAULT_TENANT;
    private boolean startTls;
    private boolean allowPlaintext;
    private String name = "your directory account";
    private double rateLimit = 30.0;

    /** Refuse a directory configuration that could not work or would expose a password, naming the setting. */
    public void validate() {
        if (url.isBlank()) {
            throw new IllegalStateException("jenreg.ui.ldap.url is not set");
        }
        if (userDnPattern.isBlank() && userSearchBase.isBlank()) {
            throw new IllegalStateException("Set jenreg.ui.ldap.user-dn-pattern or jenreg.ui.ldap.user-search-base, "
                    + "so a name typed at sign-in can be found in the directory");
        }
        for (String each : url.trim().split("\\s+")) {
            String scheme = URI.create(each).getScheme();
            if ("ldap".equalsIgnoreCase(scheme) && !startTls && !allowPlaintext) {
                throw new IllegalStateException("jenreg.ui.ldap.url is plaintext ldap:// - a sign-in would send the "
                        + "password in the clear. Use ldaps://, set jenreg.ui.ldap.start-tls=true, or set "
                        + "jenreg.ui.ldap.allow-plaintext=true if the connection is private");
            }
            if (!"ldap".equalsIgnoreCase(scheme) && !"ldaps".equalsIgnoreCase(scheme)) {
                throw new IllegalStateException("jenreg.ui.ldap.url must be ldap:// or ldaps://, not " + each);
            }
        }
    }

    /** The tenants a person's directory groups are reconciled into. */
    List<String> tenantList() {
        return Arrays.stream(tenants.split(",")).map(String::trim).filter(tenant -> !tenant.isEmpty()).toList();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    public String getUserDnPattern() {
        return userDnPattern;
    }

    public void setUserDnPattern(String userDnPattern) {
        this.userDnPattern = userDnPattern == null ? "" : userDnPattern;
    }

    public String getUserSearchBase() {
        return userSearchBase;
    }

    public void setUserSearchBase(String userSearchBase) {
        this.userSearchBase = userSearchBase == null ? "" : userSearchBase;
    }

    public String getUserSearchFilter() {
        return userSearchFilter;
    }

    public void setUserSearchFilter(String userSearchFilter) {
        this.userSearchFilter = userSearchFilter;
    }

    public String getBindDn() {
        return bindDn;
    }

    public void setBindDn(String bindDn) {
        this.bindDn = bindDn == null ? "" : bindDn;
    }

    public String getBindPassword() {
        return bindPassword;
    }

    public void setBindPassword(String bindPassword) {
        this.bindPassword = bindPassword == null ? "" : bindPassword;
    }

    public String getGroupSearchBase() {
        return groupSearchBase;
    }

    public void setGroupSearchBase(String groupSearchBase) {
        this.groupSearchBase = groupSearchBase == null ? "" : groupSearchBase;
    }

    public String getGroupSearchFilter() {
        return groupSearchFilter;
    }

    public void setGroupSearchFilter(String groupSearchFilter) {
        this.groupSearchFilter = groupSearchFilter;
    }

    public String getGroupNameAttribute() {
        return groupNameAttribute;
    }

    public void setGroupNameAttribute(String groupNameAttribute) {
        this.groupNameAttribute = groupNameAttribute;
    }

    public String getAdminGroup() {
        return adminGroup;
    }

    public void setAdminGroup(String adminGroup) {
        this.adminGroup = adminGroup == null ? "" : adminGroup;
    }

    public String getTenants() {
        return tenants;
    }

    public void setTenants(String tenants) {
        this.tenants = tenants == null ? "" : tenants;
    }

    public boolean isStartTls() {
        return startTls;
    }

    public void setStartTls(boolean startTls) {
        this.startTls = startTls;
    }

    public boolean isAllowPlaintext() {
        return allowPlaintext;
    }

    public void setAllowPlaintext(boolean allowPlaintext) {
        this.allowPlaintext = allowPlaintext;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(double rateLimit) {
        this.rateLimit = rateLimit;
    }
}
