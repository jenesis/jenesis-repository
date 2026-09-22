package build.jenesis.repository.management.web;

import module java.base;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.observation.SpiCatalog;
import build.jenesis.repository.settings.ModuleCapability;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SPI catalogue admin read: {@code GET /api/admin/spi} lists every discovered SPI this deployment carries and the
 * installed implementations that provide it, each with its enablement key, enabled state and contributed settings - the
 * per-SPI view over the same module-capability model {@code /api/capabilities} lists per module, so an operator (or a
 * headless agent) sees the whole plug-in surface grouped by contract at a glance. Under {@code /api/admin/}, so the
 * {@code RepositoryAuthorizationManager} scopes it deployment-global: a {@code manage:read} right <em>and</em> the
 * operator tenant, exactly like the storage-manifest admin verbs. Read-only - it observes the plug-in surface, never
 * mutates it; the enable/disable and setting edits live on the modules and settings surfaces. Re-homed beside the
 * credential and authorization management surface (its natural peer) and contributed through the
 * {@code ServerModuleProvider} seam; with this module absent the server carries no {@code /api/admin/spi} endpoint.
 */
@RestController
public class SpiCatalogController {

    private final Settings settings;
    private final Environment environment;
    private final PinnedSettings pins;

    public SpiCatalogController(Settings settings, Environment environment, PinnedSettings pins) {
        this.settings = settings;
        this.environment = environment;
        this.pins = pins;
    }

    /** Every product SPI grouped with its installed implementations, enumerated from the module graph and decorated
     *  with each module's effective installed / enabled state and its contributed settings - the same effective-value
     *  chain the modules screen shows, which is the chain {@link ModuleCapability#catalog} documents its lookup as and the
     *  chain the running server resolves a gate through: an operator's pin over the stored value over the
     *  {@code jenreg.*} default. This read once resolved stored-over-environment and so reported a
     *  gated implementation as enabled off a stored value the pin above it makes inert. {@code version} lets a future
     *  shape change be detected. */
    @GetMapping("/api/admin/spi")
    @ResponseBody
    public CatalogView spi() throws IOException {
        UnaryOperator<String> effective = pins.effective(settings, environment);
        List<SpiView> spis = new ArrayList<>();
        for (SpiCatalog catalog : ModuleCapability.catalog(effective, settings.documents().keySet())) {
            List<ImplementationView> implementations = new ArrayList<>();
            for (SpiCatalog.Implementation implementation : catalog.implementations()) {
                List<SettingView> configuration = new ArrayList<>();
                for (SpiCatalog.Setting setting : implementation.settings()) {
                    configuration.add(new SettingView(setting.key(), setting.label()));
                }
                implementations.add(new ImplementationView(implementation.simpleName(), implementation.type(),
                        implementation.module(), implementation.installed(), implementation.enabled(),
                        implementation.enableKey(), configuration));
            }
            spis.add(new SpiView(catalog.simpleName(), catalog.spi(), implementations));
        }
        return new CatalogView(1, spis);
    }

    /** The whole plug-in surface, grouped by SPI. {@code version} lets a client detect a future shape change. */
    public record CatalogView(int version, List<SpiView> spis) {
    }

    /** One SPI - its short name and fully-qualified service type - and the implementations installed for it. */
    public record SpiView(String name, String type, List<ImplementationView> implementations) {
    }

    /** One installed implementation: its short and fully-qualified provider type, the JPMS module it comes from,
     *  whether it is {@code installed} (on the module path) and {@code enabled} (its module's gate resolves on), the
     *  {@code enableKey} of that gate ({@code null} when always on once installed), and the settings its module reads. */
    public record ImplementationView(String name, String type, String module, boolean installed, boolean enabled,
                                     String enableKey, List<SettingView> settings) {
    }

    /** One configuration key an implementation's module contributes, with its human-readable label. */
    public record SettingView(String key, String label) {
    }
}
