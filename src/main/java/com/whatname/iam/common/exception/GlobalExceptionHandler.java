package com.whatname.iam.common.exception;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.api.ErrorCode;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        return response(exception.errorCode());
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalidArgument(Exception exception) {
        return response(ErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException exception) {
        return response(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(Exception exception) {
        return response(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedMediaType(Exception exception) {
        return response(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiResponse<Void>> database(RuntimeException exception) {
        // 不记录可能包含密码、手机号或SQL参数的异常消息。
        log.warn("Database operation failed: {}", exception.getClass().getSimpleName());
        return response(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        log.error("Unhandled error: {}", exception.getClass().getSimpleName());
        return response(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> response(ErrorCode code) {
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code.name(), code.message()));
    }
}
