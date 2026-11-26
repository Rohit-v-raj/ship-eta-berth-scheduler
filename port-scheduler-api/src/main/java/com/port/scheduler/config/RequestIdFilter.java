package com.port.scheduler.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id, so its log lines can be found together and a
 * person can quote it. The id is kept in the logging context ({@code MDC},
 * key {@value #MDC_KEY}) while the request runs, and returned in the
 * {@value #HEADER} header of every answer, errors included. A caller may send
 * its own id in the same header; one that is not a short plain token is
 * replaced.
 *
 * One line per request records its method, path, status and duration: at
 * INFO for a request that changes something, at DEBUG for a read (the
 * console polls its reads every few seconds). The query string is never
 * logged: it can carry free text, such as the agent's prompt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = idFor(request.getHeader(HEADER));
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (READS.contains(request.getMethod())) {
                log.debug("{} {} {} in {} ms", request.getMethod(), request.getRequestURI(), response.getStatus(), ms);
            } else {
                log.info("{} {} {} in {} ms", request.getMethod(), request.getRequestURI(), response.getStatus(), ms);
            }
        } catch (IOException | ServletException | RuntimeException e) {
            // the error handlers answer almost everything; this is what got past them
            log.error("{} {} failed", request.getMethod(), request.getRequestURI(), e);
            throw e;
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The caller's id if it is a short plain token, otherwise a new one. */
    static String idFor(String sent) {
        if (sent != null && ACCEPTED.matcher(sent).matches()) {
            return sent;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
