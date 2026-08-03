package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.ipcc.sipproxy.core.annotation.SipMethod;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket SIP请求处理器工厂
 * 根据请求方法选择合适的处理器
 * 使用 @SipMethod 注解自动注册处理器，新增处理器只需添加注解即可
 *
 * @author ipcc
 */
@Slf4j
@Component
public class WsSipRequestHandlerFactory {

    private final Map<String, AbstractWsSipRequestHandler> handlerMap = new HashMap<>();

    @Resource
    private Map<String, AbstractWsSipRequestHandler> handlerBeans;

    @Resource
    private WsDefaultRequestHandler defaultRequestHandler;

    @Setter
    private HeaderFactory headerFactory;

    @Setter
    private MessageFactory messageFactory;

    /**
     * 注册处理器
     *
     * @param method  SIP方法
     * @param handler 处理器
     */
    public void registerHandler(String method, AbstractWsSipRequestHandler handler) {
        handlerMap.put(method, handler);
    }

    /**
     * 根据方法获取处理器
     *
     * @param method SIP方法
     * @return 处理器
     */
    public AbstractWsSipRequestHandler getHandler(String method) {
        return handlerMap.get(method);
    }

    /**
     * 获取默认处理器
     *
     * @return 默认请求处理器
     */
    public AbstractWsSipRequestHandler getDefaultHandler() {
        return defaultRequestHandler;
    }

    /**
     * 初始化所有处理器
     * 使用 @SipMethod 注解自动扫描并注册处理器
     */
    public void init() {
        log.info("[init][开始初始化WebSocket SIP请求处理器工厂]");

        // 设置工厂实例到所有处理器
        handlerBeans.values().forEach(this::setFactoriesToHandler);

        // 自动扫描并注册带有 @SipMethod 注解的处理器
        handlerBeans.forEach((beanName, handler) -> {
            SipMethod annotation = handler.getClass().getAnnotation(SipMethod.class);
            if (annotation != null) {
                String method = annotation.value();
                registerHandler(method, handler);
                log.debug("[init][注册处理器] method={}, handler={}", method, handler.getClass().getSimpleName());
            }
        });

        log.info("[init][WebSocket SIP请求处理器工厂初始化完成] 注册处理器数量={}", handlerMap.size());
    }

    /**
     * 设置工厂实例到处理器
     *
     * @param handler 处理器
     */
    private void setFactoriesToHandler(AbstractWsSipRequestHandler handler) {
        handler.setHeaderFactory(headerFactory);
        handler.setMessageFactory(messageFactory);
    }
}
