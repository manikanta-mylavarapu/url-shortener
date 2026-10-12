package com.example.shortener.api;

import com.example.shortener.error.AliasTakenException;
import com.example.shortener.config.RequestIdFilter;
import com.example.shortener.error.CodeAllocationException;
import com.example.shortener.error.ErrorCode;
import com.example.shortener.error.IdempotencyKeyReuseException;
import com.example.shortener.error.InvalidAliasException;
import com.example.shortener.error.InvalidIdempotencyKeyException;
import com.example.shortener.error.InvalidUrlException;
import com.example.shortener.error.LinkGoneException;
import com.example.shortener.error.LinkNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error leaves the service as RFC 7807 {@code application/problem+json}. Unexpected
 * exceptions are logged server-side and returned as a generic 500, never leaking internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({InvalidUrlException.class, InvalidAliasException.class, InvalidIdempotencyKeyException.class})
    ProblemDetail badRequest(RuntimeException e) {
        ErrorCode code = e instanceof InvalidUrlException ? ErrorCode.INVALID_URL
                : e instanceof InvalidAliasException ? ErrorCode.INVALID_ALIAS
                : ErrorCode.INVALID_IDEMPOTENCY_KEY;
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), code);
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail idempotencyKeyReuse(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", e.getMessage(),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ProblemDetail notFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", e.getMessage(), ErrorCode.LINK_NOT_FOUND);
    }

    @ExceptionHandler(AliasTakenException.class)
    ProblemDetail conflict(AliasTakenException e) {
        return problem(HttpStatus.CONFLICT, "Alias not available", e.getMessage(), ErrorCode.ALIAS_UNAVAILABLE);
    }

    @ExceptionHandler(LinkGoneException.class)
    ProblemDetail gone(LinkGoneException e) {
        return problem(HttpStatus.GONE, "Link is no longer available", e.getMessage(), ErrorCode.LINK_GONE);
    }

    @ExceptionHandler(CodeAllocationException.class)
    ResponseEntity<ProblemDetail> unavailable(CodeAllocationException e) {
        log.error("code allocation exhausted", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable", e.getMessage(),
                        ErrorCode.CODE_ALLOCATION_FAILED));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred.", ErrorCode.INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Invalid request", "request validation failed",
                ErrorCode.VALIDATION_FAILED);
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Framework-raised errors (malformed JSON, 405, 415, ...) take this path too; make sure they carry the
     * same {@code code}/{@code requestId} members as our own errors so clients see one uniform contract.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            Map<String, Object> props = pd.getProperties(); // may be null: read once, then test the local
            if (props == null || !props.containsKey("code")) {
                pd.setProperty("code", ErrorCode.HTTP_ERROR.name());
            }
            addRequestId(pd);
        }
        return response;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, ErrorCode code) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setProperty("code", code.name());
        addRequestId(pd);
        return pd;
    }

    private static void addRequestId(ProblemDetail pd) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
    }
}
