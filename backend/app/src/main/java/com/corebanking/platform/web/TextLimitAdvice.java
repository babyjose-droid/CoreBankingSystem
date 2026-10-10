package com.corebanking.platform.web;

import com.corebanking.kernel.TextLimits;
import com.corebanking.platform.ApiException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Applies {@link TextLimits} to every JSON request body once it is read: the string components of the request records
 * (and of records nested in lists or other records) whose names are in the table are checked, and a too-long value is
 * answered 422 naming the field and the limit, never echoing the text. Free-form maps (custom fields, job parameters)
 * are not walked: they have their own validation.
 */
@ControllerAdvice
class TextLimitAdvice extends RequestBodyAdviceAdapter {

    private static final int MAX_DEPTH = 6;

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        check(body, 0);
        return body;
    }

    static void check(Object o, int depth) {
        if (o == null || depth > MAX_DEPTH) return;
        if (o instanceof Collection<?> c) {
            for (Object x : c) check(x, depth + 1);
            return;
        }
        Class<?> k = o.getClass();
        if (!k.isRecord()) return;
        for (RecordComponent rc : k.getRecordComponents()) {
            Object v;
            try {
                java.lang.reflect.Method accessor = rc.getAccessor();
                accessor.setAccessible(true);               // request records are often package-private
                v = accessor.invoke(o);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;                                   // a record that is not accessible is left to its own validation
            }
            if (v instanceof String s) {
                String problem = TextLimits.violation(rc.getName(), s);
                if (problem != null) throw ApiException.invalid(problem);
            } else {
                check(v, depth + 1);
            }
        }
    }
}
