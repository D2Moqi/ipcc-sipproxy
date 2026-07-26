package cn.ipcc.sipproxy.core.handler.response;

import cn.ipcc.sipproxy.support.SipProxyConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * SIP 响应转发策略
 * 定义不同消息来源和呼叫类型下的响应转发目标
 * 
 * 策略说明：
 * - WEBSOCKET 来源：无论什么呼叫类型，都转发到 FreeSWITCH
 * - FREESWITCH 来源：内部/外呼转发到 WebSocket，入呼转发到第三方
 * - THIRD_PARTY 来源：无论什么呼叫类型，都转发到 FreeSWITCH
 *
 * @author 芋道源码
 */
@Slf4j
@Component
public class ResponseForwardingStrategy {

    private final Map<String, Map<String, String>> forwardingStrategy = new HashMap<>();

    public ResponseForwardingStrategy() {
        initializeStrategy();
    }

    /**
     * 初始化转发策略
     */
    private void initializeStrategy() {
        // WebSocket 来源的响应转发策略
        Map<String, String> websocketStrategy = new HashMap<>();
        websocketStrategy.put(SipProxyConstants.CALL_TYPE_INTERNAL, SipProxyConstants.FREESWITCH);
        websocketStrategy.put(SipProxyConstants.CALL_TYPE_OUTBOUND, SipProxyConstants.FREESWITCH);
        websocketStrategy.put(SipProxyConstants.CALL_TYPE_INBOUND, SipProxyConstants.FREESWITCH);
        forwardingStrategy.put(SipProxyConstants.WEBSOCKET, websocketStrategy);

        // FreeSWITCH 来源的响应转发策略
        Map<String, String> freeswitchStrategy = new HashMap<>();
        freeswitchStrategy.put(SipProxyConstants.CALL_TYPE_INTERNAL, SipProxyConstants.WEBSOCKET);
        freeswitchStrategy.put(SipProxyConstants.CALL_TYPE_OUTBOUND, SipProxyConstants.WEBSOCKET);
        freeswitchStrategy.put(SipProxyConstants.CALL_TYPE_INBOUND, SipProxyConstants.THIRD_PARTY);
        forwardingStrategy.put(SipProxyConstants.FREESWITCH, freeswitchStrategy);

        // 第三方 SIP 来源的响应转发策略
        Map<String, String> thirdPartyStrategy = new HashMap<>();
        thirdPartyStrategy.put(SipProxyConstants.CALL_TYPE_INTERNAL, SipProxyConstants.FREESWITCH);
        thirdPartyStrategy.put(SipProxyConstants.CALL_TYPE_OUTBOUND, SipProxyConstants.FREESWITCH);
        thirdPartyStrategy.put(SipProxyConstants.CALL_TYPE_INBOUND, SipProxyConstants.FREESWITCH);
        forwardingStrategy.put(SipProxyConstants.THIRD_PARTY, thirdPartyStrategy);
    }

    /**
     * 根据消息来源和呼叫类型获取转发目标
     *
     * @param source   消息来源（WEBSOCKET、FREESWITCH、THIRD_PARTY）
     * @param callType 呼叫类型（INTERNAL、OUTBOUND、INBOUND）
     * @return 转发目标
     */
    public String getForwardingTarget(String source, String callType) {
        Map<String, String> sourceStrategy = forwardingStrategy.get(source);
        if (sourceStrategy == null) {
            log.warn("[getForwardingTarget][未知的消息来源] source={}", source);
            return SipProxyConstants.FREESWITCH;
        }

        String target = sourceStrategy.get(callType);
        if (target == null) {
            log.warn("[getForwardingTarget][未知的呼叫类型] callType={}, source={}", callType, source);
            return SipProxyConstants.FREESWITCH;
        }

        return target;
    }
}
