package build.jenesis.repository.ui.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.ui.DevConsoleSecurity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G7: the dev profile enables an in-memory {@code root}/{@code root} super-admin, so a dev boot must never be
 * network-reachable. {@link DevConsoleSecurity#verifyLoopback} - the check the {@code devLoopbackGuard} bean runs at
 * context refresh, before the web server binds - fails the boot fast when {@code server.address} names a routable
 * interface, and passes for a loopback address or the (loopback-defaulted) empty value.
 */
public class DevLoopbackGuardTest {

    @Test
    public void a_non_loopback_bind_is_rejected() {
        assertThatThrownBy(() -> DevConsoleSecurity.verifyLoopback("0.0.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-loopback");
        assertThatThrownBy(() -> DevConsoleSecurity.verifyLoopback("10.0.0.5"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void a_loopback_or_unset_bind_is_allowed() {
        assertThatCode(() -> DevConsoleSecurity.verifyLoopback("127.0.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> DevConsoleSecurity.verifyLoopback("localhost")).doesNotThrowAnyException();
        assertThatCode(() -> DevConsoleSecurity.verifyLoopback("::1")).doesNotThrowAnyException();
        assertThatCode(() -> DevConsoleSecurity.verifyLoopback("")).doesNotThrowAnyException();
        assertThatCode(() -> DevConsoleSecurity.verifyLoopback(null)).doesNotThrowAnyException();
    }
}
