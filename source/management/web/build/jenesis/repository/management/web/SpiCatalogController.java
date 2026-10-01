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
 * The SPI catalogue: {@code GET /api/admin/spi} lists every product SPI this deployment carries and the implementations
 * providing it, each with its enablement key, enabled state and settings - the per-SPI view of the module model
 * {@code /api/capabilities} lists per module. Under {@code /api/admin/}: a {@code manage:read} right and the operator
 * tenant. Read-only; enabling and settings live on their own surfaces. Without this module there is no such endpoint.
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

    /** Every product SPI with its implementations, from the module graph, decorated with each module's effective
     *  installed and enabled state and its settings through the chain the running server resolves a gate by: an
     *  operator's pin over the stored value over the {@code jenrepo.*} default ({@link ModuleCapability#catalog}).
     *  {@code version} lets a client detect a shape change. */
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

    /** One installed implementation: its short and full provider type, its module, whether it is {@code installed} and
     *  {@code enabled}, its gate's {@code enableKey} ({@code null} when always on), and its module's settings. */
    public record ImplementationView(String name, String type, String module, boolean installed, boolean enabled,
                                     String enableKey, List<SettingView> settings) {
    }

    /** One configuration key an implementation's module contributes, with its human-readable label. */
    public record SettingView(String key, String label) {
    }
}
