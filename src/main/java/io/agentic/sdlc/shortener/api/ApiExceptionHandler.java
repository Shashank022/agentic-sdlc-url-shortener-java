package io.agentic.sdlc.shortener.api;

import io.agentic.sdlc.shortener.link.ShortenerService.AliasConflict;
import io.agentic.sdlc.shortener.link.ShortenerService.InvalidRequest;
import io.agentic.sdlc.shortener.link.ShortenerService.LinkExpired;
import io.agentic.sdlc.shortener.link.ShortenerService.LinkNotFound;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({InvalidRequest.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalidRequest(Exception exception) {
        String detail = exception instanceof InvalidRequest ? exception.getMessage() : "Request validation failed.";
        return response(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    @ExceptionHandler(AliasConflict.class)
    ResponseEntity<Map<String, String>> conflict(AliasConflict exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(LinkNotFound.class)
    ResponseEntity<Map<String, String>> notFound(LinkNotFound exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(LinkExpired.class)
    ResponseEntity<Map<String, String>> gone(LinkExpired exception) {
        return response(HttpStatus.GONE, exception.getMessage());
    }

    @ExceptionHandler(RateLimitExceeded.class)
    ResponseEntity<Map<String, String>> tooManyRequests(RateLimitExceeded exception) {
        return response(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        LOGGER.error("Unhandled API error", exception);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "The request could not be completed.");
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
