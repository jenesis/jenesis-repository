package build.jenesis.repository.ui;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * The console, as one thing an application either carries or does not.
 *
 * <p>It exists because "do not run it" stopped being how a console is switched off. The console used to be its own
 * process, so a deployment that did not want one simply did not start it, and there was no dial at all - the
 * settings reference has no entry for the console because there was never anything to write there. On one node the
 * console is beans in the same application as the repository, and an operator who does not want an admin surface
 * needs to be able to say so.
 *
 * <p><b>The gate decides whether the console is registered, not what it does once it is.</b> That is why it is read
 * from the environment as the context starts rather than from the settings store: a stored setting is read by beans
 * that already exist, and this decides whether they exist. The screens, the shell, the security chain and the
 * discovered console modules all come in together or not at all - switching a console off while leaving its
 * deny-by-default chain registered would keep every path it claims, and answer them all with a redirect to a
 * sign-in page that is no longer served.
 *
 * <p>Default on: an application that puts the console on its module path meant to serve it. {@code
 * jenreg.console=false} takes it out, leaving the repository's own chain as the whole of what the node answers.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ConsoleNode.GATE, havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "build.jenesis.repository.ui",
        // The console's own entry point, which a composing launcher replaces. A full class name, and it must track
        // the package: the combined app once left these patterns naming a package that no longer existed, matched
        // nothing, and scanned both @SpringBootApplication classes back in.
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "build\\.jenesis\\.repository\\.ui\\.Application"))
@Import({ConsoleScreensConfig.class, ConsoleModulesConfig.class})
public class ConsoleNode {

    /** Whether this application serves the console at all. Read before the context starts; applies on restart. */
    public static final String GATE = "jenreg.console";
}
