package com.gustler.backend.api.http;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
        ApiException exception
    ) {
        ErrorCode code = exception.code();
        HttpHeaders headers = noStore();
        exception.retryAfter().ifPresent(retryAfter ->
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds())));

        return new ResponseEntity<>(bodyOf(code, exception.getMessage()), headers, code.status());
    }

    @ExceptionHandler({
        CannotCreateTransactionException.class,
        JpaSystemException.class
    })
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(
        RuntimeException exception
    ) {
        return respond(
            ErrorCode.SERVICE_UNAVAILABLE,
            ErrorCode.SERVICE_UNAVAILABLE.message(),
            ErrorCode.SERVICE_UNAVAILABLE.status()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
        Exception exception
    ) {
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(),
            ErrorCode.INTERNAL_ERROR.status());
    }

    /**
     * 스프링이 먼저 잡는 예외의 응답도 우리 오류 봉투로 낸다.
     *
     * <p>여기서 {@code Content-Type} 을 직접 박는다. 안 박으면 요청의 {@code Accept} 로 협상해서
     * 쓸 변환기를 고르는데, {@code Accept} 가 JSON 을 안 받는 요청은 고를 변환기가 없어
     * <b>406 이 본문 0바이트로 나간다.</b> 오류 코드도 메시지도 실리지 못한다.
     *
     * <p>클라이언트가 JSON 을 안 받겠다고 했는데 JSON 을 주는 셈인데, 그래도 이쪽이 낫다.
     * api 문서가 오류를 {@code code} · {@code message} · {@code requestId} 로 못박아 뒀고,
     * 빈 본문은 그 형식을 못 지킨다. 무엇이 잘못됐는지 못 알려 주는 응답보다 낫다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception exception,
        Object body,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorCode code = ErrorCode.of(status);

        return new ResponseEntity<>(bodyOf(code, code.message()), asJson(noStore(headers)), status);
    }

    private HttpHeaders asJson(
        HttpHeaders headers
    ) {
        headers.setContentType(MediaType.APPLICATION_JSON);

        return headers;
    }

    private ResponseEntity<ErrorResponse> respond(
        ErrorCode code,
        String message,
        HttpStatusCode status
    ) {
        return new ResponseEntity<>(bodyOf(code, message), noStore(), status);
    }

    private ErrorResponse bodyOf(
        ErrorCode code,
        String message
    ) {
        return new ErrorResponse(
            code,
            message,
            UUID.randomUUID().toString()
        );
    }

    private HttpHeaders noStore() {
        return noStore(HttpHeaders.EMPTY);
    }

    private HttpHeaders noStore(
        HttpHeaders original
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(original);
        headers.setCacheControl(CacheControl.noStore());

        return headers;
    }
}
