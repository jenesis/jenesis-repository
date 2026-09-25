package build.jenesis.repository.cache.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import build.jenesis.repository.scope.Scopes;

/**
 * The cache server's configuration, bound from {@code jenreg.cache.*}; each property also accepts its
 * relaxed-binding {@code JENREG_CACHE_*} environment variable (declared with defaults in
 * {@code application.properties}). This replaces the previous scattered {@code System.getenv} reads, so
 * every setting is explicit and bound through Spring Boot. Where the bytes go is not among them: the
 * cache delegates into the repository's store, which reads {@code jenreg.store} and that backend's own
 * keys from the same {@code Environment}, keeping one config surface for every app.
 */
@ConfigurationProperties(prefix = "jenreg.cache")
public class CacheProperties {

    /** Byte cap on a single uploaded entry; a larger PUT is refused. */
    private long maxBytes = 2147483648L;
    /** LRU capacity of the in-memory project cache. */
    private int projects = 256;
    /** Reaper interval as an ISO-8601 duration; {@code 0}/{@code off} disables it. */
    private String reaper = "PT1H";
    /** How long an entry's recency stamp stands before a hit renews it, as an ISO-8601 duration; {@code 0}/{@code off}
     *  stamps every hit. The stamp is then up to this much older than the entry's last use. */
    private String touchInterval = "PT6H";
    /** Global free-space reclaim target in bytes ({@code 0} = off). */
    private long minFree = 0;
    /** Global free-space reclaim target in percent ({@code 0} = off). */
    private int minFreePercent = 0;
    /** Project used when a request carries no project header (when not required). */
    private String defaultProject = "default";
    /** Require an explicit project header (disable the default-project fallback). */
    private boolean projectRequired = false;
    /** Optional trial bootstrap key: a single static key for the default tenant (warned, trials only). */
    private String key = "";
    /** Tenant the bootstrap key and default-project fallback resolve against. */
    private String defaultTenant = Scopes.DEFAULT_TENANT;

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getProjects() {
        return projects;
    }

    public void setProjects(int projects) {
        this.projects = projects;
    }

    public String getReaper() {
        return reaper;
    }

    public String getTouchInterval() {
        return touchInterval;
    }

    public void setTouchInterval(String touchInterval) {
        this.touchInterval = touchInterval;
    }

    public void setReaper(String reaper) {
        this.reaper = reaper;
    }

    public long getMinFree() {
        return minFree;
    }

    public void setMinFree(long minFree) {
        this.minFree = minFree;
    }

    public int getMinFreePercent() {
        return minFreePercent;
    }

    public void setMinFreePercent(int minFreePercent) {
        this.minFreePercent = minFreePercent;
    }

    public String getDefaultProject() {
        return defaultProject;
    }

    public void setDefaultProject(String defaultProject) {
        this.defaultProject = defaultProject;
    }

    public boolean isProjectRequired() {
        return projectRequired;
    }

    public void setProjectRequired(boolean projectRequired) {
        this.projectRequired = projectRequired;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

}
