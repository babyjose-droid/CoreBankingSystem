package com.corebanking.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class ProblemHandlerTest {

    @Test
    void database_rule_messages_are_shown_to_the_user_without_postgres_noise() {
        var sql = new SQLException("ERROR: branch MUM has 1 live loan accounts; transfer them before closing\n  Where: PL/pgSQL function", "23514");
        var e = new DataIntegrityViolationException("could not execute statement", sql);
        assertEquals("23514", ProblemHandler.sqlState(e));
        assertEquals("branch MUM has 1 live loan accounts; transfer them before closing", ProblemHandler.userMessage(e));
        assertEquals(409, new ProblemHandler().data(e).getStatus());
    }

    @Test
    void a_request_that_cannot_be_bound_names_what_is_wrong_and_never_the_value_sent() {
        var h = new ProblemHandler();
        var missing = h.missingParameter(new MissingServletRequestParameterException("asOf", "LocalDate"));
        assertEquals(400, missing.getStatus());
        assertEquals("query parameter 'asOf' is required", missing.getDetail());

        var wrong = h.wrongType(new MethodArgumentTypeMismatchException("CLAUDE-TEST-31/02/2026", LocalDate.class, "asOf", null,
                new IllegalArgumentException("CLAUDE-TEST-31/02/2026")));
        assertEquals(400, wrong.getStatus());
        assertEquals("'asOf' must be a date (YYYY-MM-DD)", wrong.getDetail());
        assertEquals("a UUID", ProblemHandler.expected(UUID.class));
        assertEquals("true or false", ProblemHandler.expected(boolean.class));
        assertEquals("a whole number", ProblemHandler.expected(Integer.class));
        assertEquals("one of [DAYS, HOURS]", ProblemHandler.expected(Sample.class));
        assertEquals("a valid value", ProblemHandler.expected(null));

        var body = h.unreadable(new HttpMessageNotReadableException("JSON parse error near CLAUDE-TEST-BODY", (HttpInputMessage) null));
        assertEquals(400, body.getStatus());
        assertFalse(body.getDetail().contains("CLAUDE-TEST"));

        var media = h.mediaType(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_PDF, MediaType.IMAGE_PNG)));
        assertEquals(415, media.getStatus());
        assertTrue(media.getDetail().endsWith("send application/pdf or image/png"));
        assertFalse(media.getDetail().contains("text/plain"));
    }

    enum Sample { DAYS, HOURS }

    @Test
    void internal_exception_text_is_not_echoed() {
        assertEquals("a value in the request is not valid",
                ProblemHandler.safeMessage("No enum constant com.corebanking.lending.engine.PrepaymentMode.X"));
        assertEquals("a value in the request is not valid", ProblemHandler.safeMessage(null));
        assertEquals("side must be DR or CR", ProblemHandler.safeMessage("side must be DR or CR"));
    }
}
