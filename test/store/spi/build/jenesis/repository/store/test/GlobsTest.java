package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Globs;

import static org.assertj.core.api.Assertions.assertThat;

/** An operator's glob: literal but for {@code *}, which matches any run including none, anchored at both ends. */
class GlobsTest {

    @Test
    void a_star_selects_a_namespace_and_every_other_character_is_literal() {
        Pattern namespace = Globs.compile("com.foo.*");
        assertThat(namespace.matcher("com.foo.bar").matches()).isTrue();
        assertThat(namespace.matcher("com.foo.").matches()).as("a run of none").isTrue();
        assertThat(namespace.matcher("comXfoo.bar").matches()).as("a dot is a dot").isFalse();
        assertThat(namespace.matcher("org.com.foo.bar").matches()).as("anchored").isFalse();

        assertThat(Globs.compile("@corp/*").matcher("@corp/lib").matches()).isTrue();
        assertThat(Globs.compile("repo:*:ref:refs/heads/main").matcher("repo:acme/app:ref:refs/heads/main").matches())
                .isTrue();
        assertThat(Globs.compile("exact").matcher("exact").matches()).isTrue();
        assertThat(Globs.compile("exact").matcher("exactly").matches()).isFalse();
        assertThat(Globs.compile("*").matcher("").matches()).isTrue();
        assertThat(Globs.compile("*").matcher("com.acme:lib").matches()).as("a lone star is every namespace").isTrue();
        assertThat(Globs.compile("*.acme").matcher("com.acme").matches()).as("a leading star").isTrue();
        assertThat(Globs.compile("com.*.lib").matcher("com.acme.lib").matches()).isTrue();
    }
}
