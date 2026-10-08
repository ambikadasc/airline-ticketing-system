package com.airline.reservation.common.error;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place where exceptions become HTTP responses. Every error is an RFC 9457
 * ProblemDetail with an added machine-readable {@code code}.
 * <p>
 * Framework errors (malformed JSON, bean validation, unknown path, wrong method, media type)
 * are handled by the inherited {@link ResponseEntityExceptionHandler} methods;
 * {@link #handleExceptionInternal} adds the code to all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getCode().status(), ex.getMessage());
		problem.setProperty("code", ex.getCode().name());
		return ResponseEntity.status(ex.getCode().status()).body(problem);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unexpected error", ex);
		ErrorCode code = ErrorCode.INTERNAL_ERROR;
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), "An unexpected error occurred.");
		problem.setProperty("code", code.name());
		return ResponseEntity.status(code.status()).body(problem);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ProblemDetail problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(statusCode);
		problem.setProperty("code", codeFor(statusCode).name());
		if (ex instanceof MethodArgumentNotValidException invalid) {
			problem.setProperty("errors", fieldErrors(invalid));
		}
		else if (ex instanceof HandlerMethodValidationException invalid) {
			problem.setProperty("errors", parameterErrors(invalid));
		}
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
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
