package io.agentic.sdlc.shortener.api;

import io.agentic.sdlc.shortener.config.ShortenerProperties;
import io.agentic.sdlc.shortener.link.LinkRecord;
import io.agentic.sdlc.shortener.link.ShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {
    private final ShortenerService shortener;
    private final ShortenerProperties properties;
    private final CreateRateLimiter rateLimiter;

    public LinkController(ShortenerService shortener, ShortenerProperties properties, CreateRateLimiter rateLimiter) {
        this.shortener = shortener;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<LinkCreatedResponse> create(@Valid @RequestBody LinkCreateRequest request,
                                                       HttpServletRequest servletRequest) {
        String client = servletRequest.getRemoteAddr() == null ? "unknown" : servletRequest.getRemoteAddr();
        if (!rateLimiter.allow(client)) {
            throw new RateLimitExceeded("Link creation rate limit exceeded.");
        }
        LinkRecord link = shortener.create(request.targetUrl(), request.customAlias(), request.expiresInSeconds());
        String base = properties.publicBaseUrl() == null || properties.publicBaseUrl().isBlank()
                ? requestBase(servletRequest)
                : properties.publicBaseUrl().replaceAll("/+$", "");
        return ResponseEntity.status(HttpStatus.CREATED).body(new LinkCreatedResponse(
                link.code(), base + "/r/" + link.code(), link.targetUrl(), link.createdAt(), link.expiresAt()));
    }

    @GetMapping("/{code}/stats")
    public LinkStatsResponse stats(@PathVariable String code) {
        LinkRecord link = shortener.getStats(code);
        boolean active = link.expiresAt() == null || Instant.parse(link.expiresAt()).isAfter(ShortenerService.now());
        return new LinkStatsResponse(link.code(), link.targetUrl(), link.createdAt(), link.expiresAt(),
                link.clickCount(), link.lastAccessedAt(), active);
    }

    private static String requestBase(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if ((scheme.equalsIgnoreCase("http") && port == 80) || (scheme.equalsIgnoreCase("https") && port == 443)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }
}
