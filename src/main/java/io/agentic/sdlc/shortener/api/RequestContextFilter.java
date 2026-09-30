package io.agentic.sdlc.shortener.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("shortenerRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-ID");
        String requestId = valid(supplied) ? supplied : UUID.randomUUID().toString();
        long started = System.nanoTime();
        response.setHeader("X-Request-ID", requestId);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            LOGGER.error("request failed request_id={} method={} path={}", requestId,
                    request.getMethod(), request.getRequestURI(), exception);
            throw exception;
        } finally {
            LOGGER.info("request completed request_id={} method={} path={} status={} duration_ms={}",
                    requestId, request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000.0);
        }
    }

    private static boolean valid(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.chars().allMatch(character -> character >= 32 && character <= 126);
    }
}
