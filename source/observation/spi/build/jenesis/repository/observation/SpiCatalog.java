package build.jenesis.repository.observation;

import module java.base;

/**
 * The plug-in surface grouped by SPI: every discovered {@code ServiceLoader} contract this deployment carries and the
 * installed implementations that {@code provide} it, so an operator sees the whole extension surface at a glance, one
 * SPI at a time. Pure discovery over the JPMS module graph - an implementation is a {@code provides} declaration in a
 * module descriptor and the SPI is that declaration's service - so it reports what is actually on the path rather than
 * what something believed was configured. Only this product's own contracts are listed, a service under the
 * {@code build.jenesis.} namespace, or the frameworks' own {@code ServiceLoader} plumbing would bury them.
 *
 * <p><b>The walk is here once, and what a deployment knows on top of it is a {@link Decoration}.</b> There were two
 * walks: a console one that could say only which providers existed, and a settings one that also knew each module's
 * installed and enabled state, its enablement key and the settings it contributes. They enumerated the same graph with
 * the same product-namespace filter and the same ordering, and produced two records the same screen was written
 * against twice. A deployment with no stored settings decorates with {@link #ALWAYS_ON} and gets the first; one that
 * reads settings supplies a decoration and gets the second. The screen does not know which it is looking at.
 *
 * <p>It is a model rather than markup: a screen renders it through a template, so the escaping, the layout and the
 * accessibility of the result are the template engine's job.
 */
public record SpiCatalog(String spi, List<Implementation> implementations) {

    public SpiCatalog {
        implementations = List.copyOf(implementations);
    }

    /** The SPI's short name (the last segment of its fully-qualified service type), for a compact heading. */
    public String simpleName() {
        return simpleName(spi);
    }

    /**
     * One installed implementation of an SPI: the provider type that {@code provides} it, the JPMS module it came in,
     * and what the deployment knows about that module - whether it is installed, whether it is enabled, the key that
     * gates it and the settings it contributes. {@code enableKey} is {@code null} when the module is always on once
     * installed, which is every module under {@link #ALWAYS_ON}.
     */
    public record Implementation(String type, String module, boolean installed, boolean enabled, String enableKey,
                                 List<Setting> settings) {

        public Implementation {
            settings = List.copyOf(settings);
        }

        /** The provider's short name (the last segment of its fully-qualified type). */
        public String simpleName() {
            return SpiCatalog.simpleName(type);
        }

        /** Whether this implementation's module carries an enablement gate, as opposed to being always on once
         *  installed. The screen says "always on" for the second, which is a different statement from "enabled". */
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

    /** The per-module lookup that decorates the walk - answer {@link Capability#ALWAYS_ON} for a module you know
     *  nothing about, never {@code null}. */
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

    /**
     * Every product SPI in {@code layer} and its installed implementations, each decorated with what
     * {@code decoration} knows about its declaring module.
     *
     * <p>SPIs are ordered by service name and each SPI's implementations by provider type, so a page rendered twice
     * over an unchanged graph is the same page - which is what lets a screen be cached and revalidated at all.
     */
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
