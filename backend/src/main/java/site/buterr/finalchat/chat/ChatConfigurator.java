package site.buterr.finalchat.chat;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import jakarta.servlet.http.HttpSession;

/**
 * WebSocket 握手配置器：在握手阶段拿到与该连接关联的 HTTP 会话（浏览器握手时会带上
 * JSESSIONID Cookie），从而把“这条 WebSocket 属于哪个登录用户”确定下来。
 *
 * <p>官方示例直接把 HttpSession 塞进 {@code ServerEndpointConfig#getUserProperties()}，
 * 但那张表是所有握手共享的，并发握手时会串号。Tomcat 对同一次握手保证先调
 * {@link #modifyHandshake} 再调 {@link #getEndpointInstance}，且两次调用发生在
 * 同一个请求线程上，因此这里用 synchronized + pending 字段把会话精确传递给
 * 本次连接的端点实例。</p>
 */
public class ChatConfigurator extends ServerEndpointConfig.Configurator {

    private HttpSession pending;

    @Override
    public synchronized void modifyHandshake(ServerEndpointConfig sec,
                                             HandshakeRequest request, HandshakeResponse response) {
        pending = (HttpSession) request.getHttpSession();
    }

    @Override
    public synchronized <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        HttpSession httpSession = pending;
        pending = null;
        @SuppressWarnings("unchecked")
        T instance = (T) new ChatEndpoint(httpSession);
        return instance;
    }
}
