package com.port.scheduler.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import com.google.genai.errors.ServerException;

import java.util.stream.Collectors;

/**
 * Error replies for the console's and the operator's routes, each with a
 * message a person can act on. The DCSA route answers in the standard's own
 * format through its controller's handler, which takes precedence there.
 */
@ControllerAdvice
public class GlobalExceptionHandler {
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

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static ResponseEntity<BerthErrorResponse> reply(HttpStatusCode status, String message) {
        BerthErrorResponse body = new BerthErrorResponse();
        body.setStatusCode(status.value());
        body.setMessage(message);
        body.setTimeStamp(System.currentTimeMillis());
        return new ResponseEntity<>(body, status);
    }
}
