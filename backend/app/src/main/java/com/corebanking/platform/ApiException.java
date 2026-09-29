package com.corebanking.platform;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error the API reports as RFC 9457 problem+json with the given status. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final transient Map<String, Object> properties;

    public ApiException(HttpStatus status, String detail) {
        this(status, detail, Map.of());
    }

    public ApiException(HttpStatus status, String detail, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public HttpStatus status() { return status; }
    public Map<String, Object> properties() { return properties; }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, what + " not found"); }
    public static ApiException conflict(String detail) { return new ApiException(HttpStatus.CONFLICT, detail); }
    public static ApiException invalid(String detail) { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, detail); }
    public static ApiException forbidden(String detail) { return new ApiException(HttpStatus.FORBIDDEN, detail); }
}
