package com.airline.reservation.common.error;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.core.JacksonException;

import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.logging.RequestIdFilter;

/**
 * The single place where exceptions become HTTP responses. Every error is an RFC 9457
 * ProblemDetail ({@code application/problem+json}) with {@code status}, {@code title},
 * {@code detail}, a machine-readable {@code code} and the {@code requestId}. Responses never carry
 * stack traces, SQL, constraint or class names.
 * <p>
 * Logging: client errors (4xx) at WARN on one line without a stack trace; a lock timeout (503) at
 * WARN; any other server error at ERROR with the stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	static final String RETRY_LATER_DETAIL =
			"The booking could not be completed right now; nothing was booked. Please retry";

	private final AirlineProperties properties;

	public GlobalExceptionHandler(AirlineProperties properties) {
		this.properties = properties;
	}

	/** Expected use-case failures: unknown ids, conflicts, broken rules. */
	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
		logWarning(request, ex.getCode(), ex.getMessage());
		if (ex.getCode() == ErrorCode.RETRY_LATER) {
			return retryLater(ex.getCode(), ex.getMessage());
		}
		ProblemDetail problem = problem(ex.getCode(), ex.getMessage());
		ex.extraProperties().forEach(problem::setProperty);
		return ResponseEntity.status(ex.getCode().status()).body(problem);
	}

	/**
	 * A unique constraint stopped a write. The service checks first, so this happens only when two
	 * requests race; the database constraint is the last line of defence and gives a clean 409.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
		String constraint = constraintName(ex);
		ErrorCode code;
		String detail;
		if ("uq_active_seat".equals(constraint)) {
			code = ErrorCode.SEAT_UNAVAILABLE;
			detail = "One or more requested seats were just booked by another request";
		}
		else if ("uq_schedule_flight_number".equals(constraint)) {
			code = ErrorCode.DUPLICATE_FLIGHT_NUMBER;
			detail = "A schedule with this flight number already exists";
		}
		else if ("uq_booking_reference".equals(constraint)) {
			// Two bookings drew the same random reference at the same moment (no shared lock between
			// different flights). The transaction rolled back, so nothing was booked: tell the client
			// to retry, which draws a new reference.
			log.warn("{} {} -> {} (constraint {})", request.getMethod(), request.getRequestURI(),
					ErrorCode.RETRY_LATER, constraint);
			return retryLater(ErrorCode.RETRY_LATER, RETRY_LATER_DETAIL);
		}
		else {
			return handleUnexpected(ex, request);
		}
		log.warn("{} {} -> {} (constraint {})", request.getMethod(), request.getRequestURI(), code, constraint);
		return ResponseEntity.status(code.status()).body(problem(code, detail));
	}

	/** The flight lock was not granted within lock_timeout: the flight is busy, try again shortly. */
	@ExceptionHandler(PessimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> handleLockTimeout(PessimisticLockingFailureException ex,
			HttpServletRequest request) {
		String detail = "The flight is busy with other requests; please retry";
		logWarning(request, ErrorCode.LOCK_TIMEOUT, detail);
		return retryLater(ErrorCode.LOCK_TIMEOUT, detail);
	}

	/**
	 * No database connection could be obtained within the pool's connection-timeout, so the
	 * transaction never started and nothing was changed: the client may retry.
	 * <p>
	 * A connection that breaks later ({@code DataAccessResourceFailureException}) is deliberately
	 * not mapped here: if it breaks during the commit the booking may have been stored, so
	 * promising "nothing was changed" could lead a client to book twice. It stays a 500.
	 */
	@ExceptionHandler(CannotCreateTransactionException.class)
	ResponseEntity<ProblemDetail> handlePoolExhausted(CannotCreateTransactionException ex,
			HttpServletRequest request) {
		String detail = "The service is busy; nothing was changed. Please retry";
		logWarning(request, ErrorCode.RETRY_LATER, detail);
		return retryLater(ErrorCode.RETRY_LATER, detail);
	}

	/** A 503 with Retry-After (airline.retry-after): a temporary condition, nothing was changed. */
	private ResponseEntity<ProblemDetail> retryLater(ErrorCode code, String detail) {
		return ResponseEntity.status(code.status())
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(properties.retryAfter().toSeconds()))
				.body(problem(code, detail));
	}

	/** Anything else is a bug: full stack trace in the log, a generic message to the client. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("{} {} -> unexpected error", request.getMethod(), request.getRequestURI(), ex);
		ErrorCode code = ErrorCode.INTERNAL_ERROR;
		return ResponseEntity.status(code.status()).body(problem(code, "An unexpected error occurred"));
	}

	/**
	 * Framework errors (malformed JSON, bean or parameter validation, unknown path, wrong method,
	 * media type) arrive here from the inherited handlers. Adds the code, request id and, where
	 * needed, a safe detail.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ErrorCode code = codeFor(statusCode);
		ProblemDetail problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(statusCode);
		problem.setProperty("code", code.name());
		addRequestId(problem);
		if (ex instanceof HttpMessageNotReadableException) {
			problem.setDetail(malformedBodyDetail(ex));
		}
		else if (code == ErrorCode.RESOURCE_NOT_FOUND || problem.getDetail() == null) {
			problem.setDetail(defaultDetail(code));
		}
		if (ex instanceof MethodArgumentNotValidException invalid) {
			problem.setProperty("errors", fieldErrors(invalid));
		}
		else if (ex instanceof HandlerMethodValidationException invalid) {
			problem.setProperty("errors", parameterErrors(invalid));
		}

		HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
		if (statusCode.is5xxServerError()) {
			log.error("{} {} -> {}", servletRequest.getMethod(), servletRequest.getRequestURI(), code, ex);
		}
		else {
			logWarning(servletRequest, code, problem.getDetail());
		}
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

	// ---------------------------------------------------------------------------------------

	private static ProblemDetail problem(ErrorCode code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setProperty("code", code.name());
		addRequestId(problem);
		return problem;
	}

	private static void addRequestId(ProblemDetail problem) {
		String requestId = MDC.get(RequestIdFilter.MDC_KEY);
		if (requestId != null) {
			problem.setProperty("requestId", requestId);
		}
	}

	private static void logWarning(HttpServletRequest request, ErrorCode code, String detail) {
		log.warn("{} {} -> {}: {}", request.getMethod(), request.getRequestURI(), code, detail);
	}

	private static ErrorCode codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
			case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
			case 406 -> ErrorCode.NOT_ACCEPTABLE;
			case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
			default -> status.is4xxClientError() ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
		};
	}

	private static String defaultDetail(ErrorCode code) {
		return switch (code) {
			case RESOURCE_NOT_FOUND -> "No endpoint matches this path";
			case METHOD_NOT_ALLOWED -> "This HTTP method is not supported on this path";
			case NOT_ACCEPTABLE -> "The response can only be produced as JSON";
			case UNSUPPORTED_MEDIA_TYPE -> "The request body must be application/json";
			case VALIDATION_ERROR -> "The request is invalid";
			default -> "An unexpected error occurred";
		};
	}

	/**
	 * Our own text for an unreadable body. Jackson's message can name internal classes, so it is never
	 * shown; only the path of the offending field, e.g. {@code daysOfOperation[2]}.
	 */
	private static String malformedBodyDetail(Exception ex) {
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
				return "Invalid value for field '" + fieldPath(jackson.getPath()) + "'";
			}
		}
		return "The request body is not valid JSON";
	}

	private static String fieldPath(List<JacksonException.Reference> path) {
		StringBuilder field = new StringBuilder();
		for (JacksonException.Reference reference : path) {
			if (reference.getPropertyName() != null) {
				if (!field.isEmpty()) {
					field.append('.');
				}
				field.append(reference.getPropertyName());
			}
			else if (reference.getIndex() >= 0) {
				field.append('[').append(reference.getIndex()).append(']');
			}
		}
		return field.toString();
	}

	/** The name of the violated constraint, as reported by Hibernate; null if there is none. */
	private static String constraintName(DataIntegrityViolationException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
		}
		return null;
	}

	private static List<Map<String, String>> fieldErrors(MethodArgumentNotValidException ex) {
		return ex.getBindingResult().getFieldErrors().stream()
				.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
				.toList();
	}

	/** Constraint failures on request parameters, e.g. a malformed airport code in a query string. */
	private static List<Map<String, String>> parameterErrors(HandlerMethodValidationException ex) {
		return ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> Map.of("field", String.valueOf(result.getMethodParameter().getParameterName()),
								"message", String.valueOf(error.getDefaultMessage()))))
				.toList();
	}

}
