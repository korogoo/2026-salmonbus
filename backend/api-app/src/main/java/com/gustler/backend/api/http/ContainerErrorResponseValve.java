package com.gustler.backend.api.http;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * 톰캣이 스프링을 거치지 않고 내는 응답도 우리 오류 봉투로 낸다.
 *
 * <p>요청이 늘 컨트롤러까지 오는 것은 아니다. 주소의 퍼센트 인코딩이 깨져 있으면 톰캣이 주소를
 * 해석하다 400 으로 거절하는데, 그때는 {@link ApiExceptionHandler} 가 아예 안 불린다.
 * 기본값으로 두면 <b>{@code Content-Type: text/html} 로 톰캣 오류 화면이 나간다.</b>
 * api 문서는 오류를 {@code code} · {@code message} · {@code requestId} 로 못박아 뒀고,
 * FE 는 그 응답에 {@code response.json()} 을 부른다.
 *
 * <p>오류 코드는 {@link ErrorCode#of} 가 정한다. 컨트롤러를 거친 오류와 같은 표를 써야
 * 같은 상태 코드에 두 가지 오류 코드가 나가는 일이 없다.
 *
 * <p>본문을 여기서 손으로 적는다. 이 자리에는 스프링의 메시지 변환기가 없다. 실어 보내는 값
 * 셋이 전부 우리가 만든 것이라 따옴표나 역슬래시가 낄 자리가 없지만, 값이 바뀌어도 깨지지
 * 않도록 최소한의 escape 는 한다.
 */
public class ContainerErrorResponseValve extends ErrorReportValve {

    private static final int SMALLEST_ERROR_STATUS = 400;

    public ContainerErrorResponseValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(
        Request request,
        Response response,
        Throwable throwable
    ) {
        final int status = response.getStatus();
        if (status < SMALLEST_ERROR_STATUS || response.getContentWritten() > 0) {
            return;
        }
        if (!response.setErrorReported()) {
            return;
        }

        ErrorCode code = ErrorCode.of(HttpStatus.valueOf(status));
        write(response, body(code));
    }

    private String body(
        ErrorCode code
    ) {
        return "{\"code\":\"%s\",\"message\":\"%s\",\"requestId\":\"%s\"}".formatted(
            escaped(code.name()),
            escaped(code.message()),
            UUID.randomUUID());
    }

    private static String escaped(
        String value
    ) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void write(
        Response response,
        String body
    ) {
        try {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body);
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException error) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
