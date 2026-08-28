package build.jenesis.repository.ui;

import module java.base;

/**
 * The plug-in surface this deployment carries: every discovered SPI and the implementations installed for it.
 *
 * <p>Read off the module graph rather than a registry, so it reports what is actually on the path rather than what
 * something believed was configured. Only this product's own services are listed - the JDK's and the frameworks'
 * would bury them - and whether an implementation is <em>active</em> is a separate question, answered by its
 * {@code jenreg.<feature>} key.
 *
 * <p>It is a model rather than markup. The screen renders it through a template, so the escaping, the layout and the
 * accessibility of the result are the template engine's job and not a matter of remembering to call a helper on
 * every value.
 */
public record SpiCatalog(List<Service> services) {

    /** One SPI and its installed implementations, both sorted so the page is stable between reads. */
    public record Service(String name, String simpleName, List<Implementation> implementations) {
    }

    /** One implementation: the class that answers the contract, and the module it arrived in. */
    public record Implementation(String name, String simpleName, String module) {
    }

    /** The catalogue of the running module layer. */
    public static SpiCatalog current() {
        return of(ModuleLayer.boot());
    }

    /** The catalogue of {@code layer}, so a test can build one over modules it controls. */
    public static SpiCatalog of(ModuleLayer layer) {
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
                for (String provider : provides.providers()) {
                    byService.computeIfAbsent(provides.service(), _ -> new ArrayList<>())
                            .add(new Implementation(provider, simpleName(provider), module.getName()));
                }
            }
        }
        List<Service> services = new ArrayList<>();
        byService.forEach((service, implementations) -> {
            implementations.sort(Comparator.comparing(Implementation::name));
            services.add(new Service(service, simpleName(service), List.copyOf(implementations)));
        });
        return new SpiCatalog(List.copyOf(services));
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
