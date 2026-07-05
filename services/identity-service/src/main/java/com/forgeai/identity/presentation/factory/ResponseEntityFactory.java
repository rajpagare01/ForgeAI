package com.forgeai.identity.presentation.factory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Factory for creating consistent ResponseEntity instances.
 */
public final class ResponseEntityFactory {

    private ResponseEntityFactory() {
        // Prevent instantiation
    }

    public static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok(body);
    }

    public static <T> ResponseEntity<T> created(String idPathSegment, Object id, T body) {
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(id)
                .toUri();
        return ResponseEntity.created(location).body(body);
    }
    
    public static <T> ResponseEntity<T> created(URI location, T body) {
        return ResponseEntity.created(location).body(body);
    }

    public static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    public static <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.accepted().body(body);
    }
}
