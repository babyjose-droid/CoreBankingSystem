package com.corebanking.platform.web;

import com.corebanking.platform.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer portal (US-119): the OpenAPI contract and a static reference page, served from the classpath folder
 * {@code developer/}. The build copies them there from {@code docs/api} (see {@code backend/app/build.gradle.kts}),
 * so the page always shows the contract of the running version.
 * <p>
 * Public on the API host: the files hold no secret and no tenant data, and the page makes no request except for
 * the files below. Only these five fixed names are served; nothing is resolved from the request path. The
 * Content-Security-Policy allows scripts, styles and requests from this origin only, with no inline script.
 */
@RestController
class DeveloperPortalController {

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    @GetMapping({"/developer", "/developer/", "/developer/index.html"})
    ResponseEntity<byte[]> page() {
        return serve("index.html", "text/html; charset=UTF-8");
    }

    @GetMapping("/developer/portal.js")
    ResponseEntity<byte[]> script() {
        return serve("portal.js", "text/javascript; charset=UTF-8");
    }

    @GetMapping("/developer/portal.css")
    ResponseEntity<byte[]> style() {
        return serve("portal.css", "text/css; charset=UTF-8");
    }

    @GetMapping("/developer/guide.md")
    ResponseEntity<byte[]> guide() {
        return serve("guide.md", "text/markdown; charset=UTF-8");
    }

    @GetMapping("/developer/openapi.yaml")
    ResponseEntity<byte[]> contract() {
        return serve("openapi.yaml", "application/yaml; charset=UTF-8");
    }

    private ResponseEntity<byte[]> serve(String name, String contentType) {
        byte[] body = files.computeIfAbsent(name, DeveloperPortalController::read);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .header("Content-Security-Policy", CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length)
                .body(body);
    }

    private static byte[] read(String name) {
        try (InputStream in = DeveloperPortalController.class.getResourceAsStream("/developer/" + name)) {
            if (in == null) throw ApiException.notFound("developer portal file " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
