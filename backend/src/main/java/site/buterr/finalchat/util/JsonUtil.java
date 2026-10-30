package site.buterr.finalchat.util;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JSON 读写的小封装。ObjectMapper 线程安全，全局共享一个实例。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {
    }

    /**
     * 读取请求体并反序列化；请求体缺失或不是合法 JSON 时返回 null，由调用方决定 400。
     */
    public static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        try (InputStream in = request.getInputStream()) {
            return MAPPER.readValue(in, type);
        } catch (JacksonException e) {
            return null;
        }
    }

    public static void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), body);
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
