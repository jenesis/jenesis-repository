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

    /** Where the shell's static files live below the free core's own root. */
    private static final Path STATIC = Path.of("source", "ui", "META-INF", "resources", "ui");

    private final Path root;

    /** The shell's static files: below the root, or below a {@code core} checkout of the free core within it. */
    private final Path statics;

    public DevSources(@Value("${jenrepo.ui.sources}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        Path statics = this.root.resolve(STATIC);
        if (!Files.isDirectory(statics)) {
            statics = this.root.resolve("core").resolve(STATIC);
        }
        if (!Files.isDirectory(statics)) {
            throw new IllegalStateException("jenrepo.ui.sources names " + this.root + ", which is not a checkout of"
                    + " the console: it has no " + STATIC + ", at its root or below core/");
        }
        this.statics = statics;
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
                    .addResourceLocations(statics.resolve(folder).toUri().toString())
                    .setCachePeriod(0);
        }
    }

    /** The template folders of every production module in the checkout at {@code root}: every {@code templates}
     *  folder beneath a {@code source} tree, outside the build's output. */
    static List<Path> roots(Path root) throws IOException {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> folders = Files.walk(root)) {
            folders.filter(Files::isDirectory)
                    .filter(folder -> folder.getFileName().toString().equals("templates"))
                    .filter(folder -> root.relativize(folder).toString().contains("source" + File.separator))
                    .filter(folder -> !root.relativize(folder).toString().contains("target" + File.separator))
                    .sorted()
                    .forEach(roots::add);
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
