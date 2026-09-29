package com.corebanking.platform.web;

import com.corebanking.platform.ApiException;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 errors. Business-rule violations raised by database triggers (SQLSTATE 23xxx, 42501) become 409 with
 * the trigger's message, which is written for users. Anything unexpected is 500 with no internal detail.
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
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return p;
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
