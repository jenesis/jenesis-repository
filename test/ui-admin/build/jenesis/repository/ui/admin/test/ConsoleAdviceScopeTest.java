package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.ui.admin.web.GlobalControllerAdvice;
import build.jenesis.repository.ui.admin.web.RepositoryAdminController;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerTypePredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's advice answers for its screens and for nothing else in the context. The shipped image runs the
 * console and the repository in one Spring context, so an advice selecting every handler rendered a failed download
 * as the console's HTML error page with a {@code 200}, and built the console's navigation for every artifact request.
 * The selection is read off the advice's own annotation, the way Spring reads it.
 */
class ConsoleAdviceScopeTest {

    private static final HandlerTypePredicate CONSOLE = selection(GlobalControllerAdvice.class);

    @Test
    void the_console_advice_answers_for_a_screen() {
        assertThat(CONSOLE.test(RepositoryAdminController.class)).isTrue();
    }

    @Test
    void the_console_advice_does_not_answer_for_the_data_plane() {
        assertThat(CONSOLE.test(RepositoryController.class)).isFalse();
    }

    private static HandlerTypePredicate selection(Class<?> advice) {
        ControllerAdvice declared = advice.getAnnotation(ControllerAdvice.class);
        return HandlerTypePredicate.builder()
                .basePackage(declared.basePackages())
                .basePackageClass(declared.basePackageClasses())
                .assignableType(declared.assignableTypes())
                .annotation(declared.annotations())
                .build();
    }
}
