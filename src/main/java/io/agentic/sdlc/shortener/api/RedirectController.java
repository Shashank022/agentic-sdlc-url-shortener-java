package io.agentic.sdlc.shortener.api;

import io.agentic.sdlc.shortener.link.ShortenerService;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {
    private final ShortenerService shortener;

    public RedirectController(ShortenerService shortener) {
        this.shortener = shortener;
    }

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(shortener.resolveAndRecordClick(code)))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
