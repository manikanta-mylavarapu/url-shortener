package com.example.shortener.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the API contract at {@code /openapi.yaml}. The file is copied from {@code docs/openapi.yaml} onto the
 * classpath at build time (see pom.xml), so the documented contract and the served one cannot drift apart.
 *
 * <p>A literal mapping beats the {@code /{code}} redirect pattern, so this path is never treated as a short code.
 */
@RestController
public class OpenApiController {

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    @GetMapping("/openapi.yaml")
    public ResponseEntity<Resource> openApi() {
        return ResponseEntity.ok().contentType(YAML).body(new ClassPathResource("openapi.yaml"));
    }
}
