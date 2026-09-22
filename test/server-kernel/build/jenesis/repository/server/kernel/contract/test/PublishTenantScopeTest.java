package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.PublishTenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-thread publish-tenant binding {@code DeployController} opens around a publish so the discovered compliance
 * gate resolves that tenant's own policy. The single-writer property the multi-tenant deployment leans on is that the
 * binding is cleared when the scope closes - including when the publish throws - so a pooled request thread never
 * screens tenant B's upload with tenant A's policy. These tests drive the scope directly (the {@code
 * try (PublishTenant.Scope _ = PublishTenant.open(tenant))} that wraps the deploy), proving it clears on the normal
 * path, on an exception, restores a previous binding on a nested publish, and leaks nothing across two sequential
 * publishes on one thread.
 */
class PublishTenantScopeTest {

    @Test
    void a_scope_binds_the_tenant_and_clears_it_on_close() {
        assertThat(PublishTenant.current()).as("unbound before any publish").isNull();
        try (PublishTenant.Scope _ = PublishTenant.open("acme")) {
            assertThat(PublishTenant.current()).isEqualTo("acme");
        }
        assertThat(PublishTenant.current()).as("cleared when the publish finishes").isNull();
    }

    @Test
    void the_binding_is_cleared_even_when_the_publish_throws() {
        assertThatThrownBy(() -> {
            try (PublishTenant.Scope _ = PublishTenant.open("acme")) {
                assertThat(PublishTenant.current()).isEqualTo("acme");
                throw new IllegalStateException("screening failed");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(PublishTenant.current()).as("a failed screen leaves no tenant bound").isNull();
    }

    @Test
    void a_nested_publish_restores_the_outer_tenant_on_close() {
        try (PublishTenant.Scope _ = PublishTenant.open("acme")) {
            try (PublishTenant.Scope _ = PublishTenant.open("globex")) {
                assertThat(PublishTenant.current()).isEqualTo("globex");
            }
            assertThat(PublishTenant.current()).as("the outer tenant is restored").isEqualTo("acme");
        }
        assertThat(PublishTenant.current()).isNull();
    }

    @Test
    void a_reused_thread_never_leaks_a_tenant_into_the_next_publish() {
        try (PublishTenant.Scope _ = PublishTenant.open("acme")) {
            assertThat(PublishTenant.current()).isEqualTo("acme");
        }
        // The same thread now screens a second tenant's upload; it must see globex, never the drained acme.
        try (PublishTenant.Scope _ = PublishTenant.open("globex")) {
            assertThat(PublishTenant.current()).isEqualTo("globex");
        }
        assertThat(PublishTenant.current()).isNull();
    }
}
