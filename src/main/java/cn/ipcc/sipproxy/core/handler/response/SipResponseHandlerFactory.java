package cn.ipcc.sipproxy.core.handler.response;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Response;

/**
 * SIP响应处理器工厂
 * 使用统一的响应处理器处理所有SIP响应
 *
 * @author ipcc
 */
@Slf4j
@Component
public class SipResponseHandlerFactory {

    @Resource
    private UnifiedResponseHandler unifiedResponseHandler;

    /**
     * 获取响应处理器
     * 使用统一处理器处理所有响应，内部根据消息来源和呼叫类型自动决定转发目标
     *
     * @param response SIP响应
     * @return 响应处理器
     */
    public AbstractSipResponseHandler getHandler(Response response) {
        return unifiedResponseHandler;
    }
}
