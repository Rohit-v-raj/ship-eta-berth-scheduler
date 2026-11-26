package com.port.scheduler.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import com.google.genai.errors.ServerException;
import com.port.scheduler.config.RequestIdFilter;

import java.util.stream.Collectors;

/**
 * Error replies for the console's and the operator's routes, each with a
 * message a person can act on. The DCSA route answers in the standard's own
 * format through its controller's handler, which takes precedence there.
 *
 * Every reply is logged: a refusal (4xx) at INFO with its reason, a failure
 * (5xx) at WARN. Anything no other handler expects is logged at ERROR with
 * its cause, and the caller is told only that something went wrong, with the
 * request's id to quote.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getBerthNotFoundResponse(BerthNotFoundException e)
    {
        return reply(HttpStatus.NOT_FOUND, "Berth not found: " + e.getMessage());
    }

    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getVisitNotFoundResponse(VisitNotFoundException e)
    {
        return reply(HttpStatus.NOT_FOUND, "Visit not found: " + e.getMessage());
    }
    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getVisitConflictResponse(VisitConflictException e)
    {
        return reply(HttpStatus.CONFLICT, "Conflict: " + e.getMessage());
    }
    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getAiServiceErrorResponse(ServerException e)
    {
        return reply(HttpStatus.BAD_GATEWAY, "AI service error: " + e.getMessage());
    }

    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getEtaServiceUnavailableResponse(EtaServiceUnavailableException e)
    {
        // the deepest cause names what happened (a refused connection has no message, only its type)
        log.warn("The ETA model service did not answer: {}", NestedExceptionUtils.getMostSpecificCause(e).toString());
        return reply(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** A refusal a controller raised itself, for example a window that ends before it starts. */
    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getStatusResponse(ResponseStatusException e)
    {
        String reason = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();
        return reply(e.getStatusCode(), reason);
    }

    /** A request body that breaks a field's rule, for example a reason that is too long. */
    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getInvalidBodyResponse(MethodArgumentNotValidException e)
    {
        String problems = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        return reply(HttpStatus.BAD_REQUEST, problems.isEmpty() ? "The request body is not valid" : problems);
    }

    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getUnreadableBodyResponse(HttpMessageNotReadableException e)
    {
        return reply(HttpStatus.BAD_REQUEST, "The request body could not be read: check that it is JSON and that "
                + "every time is ISO 8601 with a zone, for example 2026-10-14T12:00:00Z");
    }

    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getBadParameterResponse(MethodArgumentTypeMismatchException e)
    {
        return reply(HttpStatus.BAD_REQUEST, "The value of '" + e.getName() + "' could not be read");
    }

    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getMissingParameterResponse(MissingServletRequestParameterException e)
    {
        return reply(HttpStatus.BAD_REQUEST, "The parameter '" + e.getParameterName() + "' is missing");
    }

    /**
     * Everything the handlers above do not expect. Spring's own refusals (no
     * such route, a method or media type the route does not take) keep their
     * status. Anything else is a failure of ours: logged at ERROR with its
     * cause, and answered 500 with the request's id, never with the cause's
     * own text.
     */
    @ExceptionHandler
    public ResponseEntity<BerthErrorResponse> getUnexpectedResponse(Exception e)
    {
        if (e instanceof ErrorResponse refusal) {
            String detail = refusal.getBody().getDetail();
            return reply(refusal.getStatusCode(), detail != null ? detail : refusal.getStatusCode().toString(),
                    refusal.getHeaders());
        }
        log.error("Unexpected failure", e);
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        String message = "Something went wrong in the scheduler" + (id == null ? "" : ". Reference: " + id);
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, message, HttpHeaders.EMPTY);
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static ResponseEntity<BerthErrorResponse> reply(HttpStatusCode status, String message) {
        return reply(status, message, HttpHeaders.EMPTY);
    }

    /** The reply, logged: a refusal at INFO, a failure at WARN. */
    private static ResponseEntity<BerthErrorResponse> reply(HttpStatusCode status, String message, HttpHeaders headers) {
        if (status.is5xxServerError()) {
            log.warn("Answered {}: {}", status.value(), message);
        } else {
            log.info("Refused with {}: {}", status.value(), message);
        }
        return answer(status, message, headers);
    }

    private static ResponseEntity<BerthErrorResponse> answer(HttpStatusCode status, String message, HttpHeaders headers) {
        BerthErrorResponse body = new BerthErrorResponse();
        body.setStatusCode(status.value());
        body.setMessage(message);
        body.setTimeStamp(System.currentTimeMillis());
        return new ResponseEntity<>(body, headers, status);
    }
}
