package dev.bprithiraj.aegis;
import static org.assertj.core.api.Assertions.*;
import dev.bprithiraj.aegis.Models.ReserveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ApiKeyFilterTest {
    @Test void nonLoopbackRequiresKey() {
        assertThatThrownBy(() -> new ApiKeyFilter("", "0.0.0.0")).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new ApiKeyFilter("demo-key", "0.0.0.0")).doesNotThrowAnyException();
    }
    @Test void protectsReadsAndWrites() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter("test-key", "127.0.0.1");
        var request = new MockHttpServletRequest("GET", "/api/snapshot");
        var denied = new MockHttpServletResponse();
        filter.doFilter(request, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(401);
        request.addHeader("X-API-Key", "test-key");
        var allowed = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, allowed, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(allowed.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void encodedAndMatrixRoutesCannotBypassAuthentication() throws Exception {
        for (String path : java.util.List.of("/api;review/snapshot", "/%61pi/snapshot",
                "/a%70i/snapshot", "/api//snapshot", "/api/snapshot/", "/unknown")) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            new ApiKeyFilter("test-key", "127.0.0.1").doFilter(
                new MockHttpServletRequest("GET", path), response, chain);
            assertThat(response.getStatus()).as(path).isEqualTo(401);
            assertThat(chain.getRequest()).as(path).isNull();
        }
    }
    @Test void healthIsReadableWithoutApiKey() throws Exception {
        var chain = new MockFilterChain();
        new ApiKeyFilter("test-key", "127.0.0.1").doFilter(
            new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
    @Test void fingerprintIncludesEveryBehavioralInput() {
        String first = ReservationService.requestHash(new ReserveRequest("RADIO", 1, false));
        assertThat(first).isNotEqualTo(ReservationService.requestHash(new ReserveRequest("RADIO", 1, true)));
        assertThat(first).isNotEqualTo(ReservationService.requestHash(new ReserveRequest("RADIO", 2, false)));
        assertThat(first).isEqualTo(ReservationService.requestHash(new ReserveRequest("RADIO", 1, false)));
    }
}
