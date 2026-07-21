package com.fido.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fido.server.context.RequestContext;
import com.fido.server.dto.common.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 供 servlet filter（尚未進入 Spring MVC / {@code GlobalExceptionHandler} 範圍）直接寫出
 * api-contract.md 1.4 通用錯誤格式的輔助工具。
 */
@Component
public class FilterErrorWriter {

    private final ObjectMapper objectMapper;

    public FilterErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int httpStatus, String code, String message) throws IOException {
        write(response, httpStatus, code, message, Map.of());
    }

    public void write(HttpServletResponse response, int httpStatus, String code, String message,
                       Map<String, Object> details) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = ErrorResponse.of(code, message, RequestContext.getRequestId(), details);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
