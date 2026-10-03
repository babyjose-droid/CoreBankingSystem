package com.corebanking.platform.web;

import com.corebanking.platform.ApiException;
import java.sql.SQLException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * RFC 9457 errors. Business-rule violations raised by database triggers (SQLSTATE 23xxx, 42501) become 409 with
 * the trigger's message, which is written for users. A request that cannot be bound (a required parameter left
 * out, a value of the wrong type, an unreadable body, an unsupported Content-Type) names what is wrong without
 * repeating the value sent. Anything unexpected is 500 with no internal detail.
 */
@RestControllerAdvice
class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        e.properties().forEach(p::setProperty);
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, safeMessage(e.getMessage()));
    }

    /** Validation messages are written for users; JDK/framework messages that name internal classes are not shown. */
    static String safeMessage(String message) {
        if (message == null || message.isBlank() || message.contains("No enum constant")
                || message.contains("com.corebanking.") || message.contains("java.") || message.contains("jakarta.")) {
            return "a value in the request is not valid";
        }
        return message;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "validation failed");
        p.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList());
        return p;
    }

    // ---- requests that never reach a controller: say what is wrong, never repeat what was sent ----------------

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missingParameter(MissingServletRequestParameterException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "query parameter '" + e.getParameterName() + "' is required");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "header '" + e.getHeaderName() + "' is required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail wrongType(MethodArgumentTypeMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "'" + e.getName() + "' must be " + expected(e.getRequiredType()));
    }

    /** What a parameter of the type looks like, in the contract's words (the Java type name means nothing to a caller). */
    static String expected(Class<?> type) {
        if (type == null) return "a valid value";
        if (type == java.time.LocalDate.class) return "a date (YYYY-MM-DD)";
        if (type == java.util.UUID.class) return "a UUID";
        if (type == boolean.class || type == Boolean.class) return "true or false";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) return "a whole number";
        if (type == java.math.BigDecimal.class) return "a number";
        if (type.isEnum()) {
            return "one of " + java.util.Arrays.stream(type.getEnumConstants()).map(String::valueOf).toList();
        }
        return "a valid value";
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        // the parser's message quotes the body, so it is not passed on
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "the request body is missing or cannot be read: send it as documented for this operation (for JSON, well-formed and with values of the documented types)");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail mediaType(HttpMediaTypeNotSupportedException e) {
        java.util.List<String> supported = e.getSupportedMediaTypes().stream().map(String::valueOf).sorted().toList();
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, supported.isEmpty()
                ? "the Content-Type of the request is not supported for this operation"
                : "the Content-Type of the request is not supported for this operation; send " + String.join(" or ", supported));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        // ASVS V16.3: authorisation failures are security events.
        log.warn("access denied: user={} tenant={}", com.corebanking.platform.CurrentUser.username(),
                com.corebanking.platform.tenancy.TenantContext.currentOrNull());
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "you do not have permission for this action");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail data(DataAccessException e) {
        String state = sqlState(e);
        if (state != null && (state.startsWith("23") || state.equals("42501") || state.equals("P0002"))) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, userMessage(e));
        }
        log.error("database error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected database error");
    }

    static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
        }
        return null;
    }

    /** First line of the PostgreSQL message, without the "ERROR:" prefix and context lines. */
    static String userMessage(Throwable e) {
        String m = null;
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLException) m = t.getMessage();
        if (m == null) m = e.getMessage();
        if (m == null) return "rule violated";
        m = m.replaceFirst("^ERROR:\\s*", "");
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
