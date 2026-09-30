package build.jenesis.repository.failure;

import module java.base;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;

/**
 * Installs the referenced error attributes and the problem-document error controller ahead of Boot's own, which then
 * back off, so every composition that carries this module answers a failure with a reference and nothing else.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
public class FailureAutoConfiguration {

    @Bean
    public ErrorAttributes errorAttributes() {
        return new ReferencedErrorAttributes();
    }

    @Bean
    public ProblemErrorController basicErrorController(ErrorAttributes errorAttributes,
                                                       ObjectProvider<ErrorViewResolver> resolvers) {
        return new ProblemErrorController(errorAttributes, resolvers.orderedStream().toList());
    }
}
