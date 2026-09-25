package build.jenesis.repository.ui;

import module java.base;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.templateresolver.AbstractConfigurableTemplateResolver;
import org.thymeleaf.templateresource.FileTemplateResource;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * The console read from a source checkout, for working on its look: with the {@code dev} profile active and
 * {@code jenreg.ui.sources} naming the root of a checkout, every template and every stylesheet, script, font and image
 * is read from the source tree on each request instead of from the module jars, so an edit shows on the next reload
 * of the page with no build, no restart and no test run. A change to Java still needs the modules rebuilt.
 *
 * <p>Templates are found under every module's template folder - {@code templates/} or {@code META-INF/templates/}
 * below {@code core/source} and {@code enterprise/source} - in a resolver ordered ahead of the modules' own, so a
 * template the tree carries is read from the tree and one it does not falls through to the jar. Static files are
 * served from the shell module's {@code META-INF/resources}. Nothing is cached in either case.
 *
 * <p>It is inert without the profile, and the profile itself refuses a non-loopback bind, so a deployment can never
 * be pointed at files on its host this way.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnProperty("jenreg.ui.sources")
public class DevSources implements WebMvcConfigurer {

    /** Where the shell's static files live below a checkout's root. */
    private static final Path STATIC = Path.of("core", "source", "ui", "META-INF", "resources", "ui");

    private final Path root;

    public DevSources(@Value("${jenreg.ui.sources}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root.resolve(STATIC))) {
            throw new IllegalStateException("jenreg.ui.sources names " + this.root + ", which is not a checkout of"
                    + " this repository: it has no " + STATIC);
        }
    }

    /** Every module's templates, read from the tree ahead of the jars. */
    @Bean
    public SourceTemplates sourceTemplates() throws IOException {
        return new SourceTemplates(roots(root));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String folder : List.of("css", "js", "fonts", "img")) {
            registry.addResourceHandler("/ui/" + folder + "/**")
                    .addResourceLocations(root.resolve(STATIC).resolve(folder).toUri().toString())
                    .setCachePeriod(0);
        }
    }

    /** The template folders of every module in the checkout at {@code root}. */
    static List<Path> roots(Path root) throws IOException {
        List<Path> roots = new ArrayList<>();
        for (Path tree : List.of(root.resolve(Path.of("core", "source")), root.resolve(Path.of("enterprise", "source")))) {
            if (!Files.isDirectory(tree)) {
                continue;
            }
            try (Stream<Path> folders = Files.walk(tree)) {
                folders.filter(Files::isDirectory)
                        .filter(folder -> folder.getFileName().toString().equals("templates"))
                        .filter(folder -> !folder.toString().contains(File.separator + "target" + File.separator))
                        .sorted()
                        .forEach(roots::add);
            }
        }
        return List.copyOf(roots);
    }

    /** A resolver asking each template folder in turn, uncached, and passing on a name none of them carries. */
    public static final class SourceTemplates extends AbstractConfigurableTemplateResolver {

        private final List<Path> roots;

        SourceTemplates(List<Path> roots) {
            this.roots = roots;
            setTemplateMode(TemplateMode.HTML);
            setCharacterEncoding("UTF-8");
            setCacheable(false);
            setCheckExistence(true);
            setOrder(0);
        }

        @Override
        protected ITemplateResource computeTemplateResource(IEngineConfiguration configuration, String ownerTemplate,
                                                            String template, String resourceName,
                                                            String characterEncoding,
                                                            Map<String, Object> templateResolutionAttributes) {
            Path found = null;
            for (Path folder : roots) {
                Path candidate = folder.resolve(template + ".html").normalize();
                if (candidate.startsWith(folder) && Files.isRegularFile(candidate)) {
                    found = candidate;
                    break;
                }
            }
            // A name no folder carries resolves to a file that does not exist, which the existence check turns into
            // "not mine" - so the modules' own resolvers answer it.
            Path resource = found != null ? found : roots.isEmpty() ? Path.of(template + ".html")
                    : roots.getFirst().resolve(".absent").resolve(template + ".html");
            return new FileTemplateResource(resource.toString(), characterEncoding);
        }
    }
}
