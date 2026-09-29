package com.corebanking.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

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
    void internal_exception_text_is_not_echoed() {
        assertEquals("a value in the request is not valid",
                ProblemHandler.safeMessage("No enum constant com.corebanking.lending.engine.PrepaymentMode.X"));
        assertEquals("a value in the request is not valid", ProblemHandler.safeMessage(null));
        assertEquals("side must be DR or CR", ProblemHandler.safeMessage("side must be DR or CR"));
    }
}
