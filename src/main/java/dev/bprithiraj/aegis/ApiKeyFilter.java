package dev.bprithiraj.aegis;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    // Exact public paths: encoded or matrix-parameter variants must still authenticate.
    private static final Set<String> PUBLIC_PATHS =
        Set.of("/", "/index.html", "/app.js", "/app.css", "/actuator/health");
    private final byte[] expected;
    public ApiKeyFilter(@Value("${aegis.api-key:}") String key,
                        @Value("${server.address:127.0.0.1}") String bindAddress) {
        if (key.isBlank() && !Set.of("127.0.0.1", "::1", "localhost").contains(bindAddress))
            throw new IllegalStateException("Set AEGIS_API_KEY before binding to a non-loopback address.");
        expected = key.getBytes(StandardCharsets.UTF_8);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        if (!PUBLIC_PATHS.contains(request.getRequestURI())) {
            response.setHeader("Cache-Control", "no-store");
            String supplied = request.getHeader("X-API-Key");
            if (expected.length > 0 && (supplied == null ||
                    !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8)))) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"A valid X-API-Key is required.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
