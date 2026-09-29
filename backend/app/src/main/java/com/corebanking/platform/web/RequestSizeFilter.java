package com.corebanking.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies before anything buffers them (ASVS V4/V5). Bodies are read as JSON or CSV into memory, so
 * a declared Content-Length above the limit is refused with 413 at once, and a body without a declared length
 * (chunked) fails as soon as it passes the limit. The ingress applies the same limit in front
 * (nginx {@code proxy-body-size}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    RequestSizeFilter(@Value("${corebanking.http.max-body-bytes:6291456}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            response.setStatus(413);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":413,\"title\":\"Content Too Large\",\"detail\":\"the request body is larger than "
                    + (maxBytes / (1024 * 1024)) + " MB\"}");
            return;
        }
        chain.doFilter(declared >= 0 ? request : new Limited(request, maxBytes), response);
    }

    /** Counts bytes of a body without a declared length and fails past the limit. */
    static final class Limited extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new Counting(super.getInputStream(), max);
            return stream;
        }
    }

    static final class Counting extends ServletInputStream {
        private final ServletInputStream in;
        private final long max;
        private long count;

        Counting(ServletInputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        private void add(long n) throws IOException {
            if (n > 0) count += n;
            if (count > max) throw new IOException("request body exceeds " + max + " bytes");
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            add(n);
            return n;
        }

        @Override public boolean isFinished() { return in.isFinished(); }
        @Override public boolean isReady() { return in.isReady(); }
        @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
    }
}
