package build.jenesis.repository.observation;

import module java.base;

/**
 * The plug-in surface grouped by SPI: every product {@code ServiceLoader} contract (under {@code build.jenesis.}) this
 * deployment carries and the implementations that {@code provide} it, read from the module graph, so it reports what is
 * on the path rather than what was believed configured.
 *
 * <p>The walk is here once, and what a deployment knows beyond it is a {@link Decoration}: a console knows only the
 * providers; a deployment with settings also knows each module's installed and enabled state, enablement key and
 * settings. A deployment without stored settings decorates with {@link #ALWAYS_ON}.
 *
 * <p>A model rather than markup, rendered through a template.
 */
public record SpiCatalog(String spi, List<Implementation> implementations) {

    public SpiCatalog {
        implementations = List.copyOf(implementations);
    }

    /** The SPI's short name (the last segment of its fully-qualified service type), for a compact heading. */
    public String simpleName() {
        return simpleName(spi);
    }

    /** One installed implementation: its provider type, its module, and what the deployment knows of that module -
     *  installed, enabled, the key gating it and its settings. {@code enableKey} is {@code null} for a module always on
     *  once installed. */
    public record Implementation(String type, String module, boolean installed, boolean enabled, String enableKey,
                                 List<Setting> settings) {

        public Implementation {
            settings = List.copyOf(settings);
        }

        /** The provider's short name (the last segment of its fully-qualified type). */
        public String simpleName() {
            return SpiCatalog.simpleName(type);
        }

        /** Whether this implementation's module has an enablement gate; the screen says "always on" otherwise, which is
         *  not the same as "enabled". */
        public boolean gated() {
            return enableKey != null;
        }
    }

    /** One setting a module contributes, as the catalogue shows it: the key an operator would edit and its label. */
    public record Setting(String key, String label) {
    }

    /** What a deployment knows about one module beyond the fact that it is on the graph. */
    public record Capability(boolean installed, boolean enabled, String enableKey, List<Setting> settings) {

        /** A module that is installed, on, gated by nothing and contributes no editable setting. */
        public static final Capability ALWAYS_ON = new Capability(true, true, null, List.of());

        public Capability {
            settings = List.copyOf(settings);
        }
    }

    /** The per-module lookup decorating the walk: {@link Capability#ALWAYS_ON} for an unknown module, never
     *  {@code null}. */
    @FunctionalInterface
    public interface Decoration {

        Capability of(String module);
    }

    /** The decoration of a deployment that reads no stored configuration: everything installed is on. */
    public static final Decoration ALWAYS_ON = _ -> Capability.ALWAYS_ON;

    /** The catalogue of the running module layer, undecorated. */
    public static List<SpiCatalog> current() {
        return of(ModuleLayer.boot(), ALWAYS_ON);
    }

    /** Every product SPI in {@code layer} and its implementations, each decorated by {@code decoration}. SPIs are
     *  ordered by service name and implementations by type, so an unchanged graph renders the same page. */
    public static List<SpiCatalog> of(ModuleLayer layer, Decoration decoration) {
        Map<String, List<Implementation>> byService = new TreeMap<>();
        for (Module module : layer.modules()) {
            ModuleDescriptor descriptor = module.getDescriptor();
            if (descriptor == null) {
                continue;
            }
            for (ModuleDescriptor.Provides provides : descriptor.provides()) {
                if (!provides.service().startsWith("build.jenesis.")) {
                    continue;
                }
                Capability capability = Objects.requireNonNullElse(
                        decoration.of(module.getName()), Capability.ALWAYS_ON);
                for (String provider : provides.providers()) {
                    byService.computeIfAbsent(provides.service(), _ -> new ArrayList<>())
                            .add(new Implementation(provider, module.getName(), capability.installed(),
                                    capability.enabled(), capability.enableKey(), capability.settings()));
                }
            }
        }
        List<SpiCatalog> catalog = new ArrayList<>();
        byService.forEach((service, implementations) -> {
            implementations.sort(Comparator.comparing(Implementation::type));
            catalog.add(new SpiCatalog(service, List.copyOf(implementations)));
        });
        return List.copyOf(catalog);
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
