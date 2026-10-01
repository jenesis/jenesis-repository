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
 * {@code jenrepo.ui.sources} naming a checkout's root, templates and static files are read from the tree on each
 * request, uncached, so an edit shows on the next reload without a build.
 *
 * <p>Templates come from every module's {@code templates/} folder, in a resolver ordered ahead of the modules' own, so
 * a template the tree lacks falls through to the jar; static files from the shell module's {@code META-INF/resources}.
 * Inert without the profile, which refuses a non-loopback bind.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnProperty("jenrepo.ui.sources")
public class DevSources implements WebMvcConfigurer {

    /** Where the shell's static files live below a checkout's root. */
    private static final Path STATIC = Path.of("core", "source", "ui", "META-INF", "resources", "ui");

    private final Path root;

    public DevSources(@Value("${jenrepo.ui.sources}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root.resolve(STATIC))) {
            throw new IllegalStateException("jenrepo.ui.sources names " + this.root + ", which is not a checkout of"
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
            // A name no folder carries resolves to an absent file, so the modules' own resolvers answer it.
            Path resource = found != null ? found : roots.isEmpty() ? Path.of(template + ".html")
                    : roots.getFirst().resolve(".absent").resolve(template + ".html");
            return new FileTemplateResource(resource.toString(), characterEncoding);
        }
    }
}
