package cn.ipcc.sipproxy.core.handler.request.sip;

import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * 传入BYE请求处理器
 * 处理来自FreeSWITCH/第三方SIP的BYE请求
 *
 * 需求: FreeSWITCH或第三方SIP发起BYE挂断时,SIP代理作为B2BUA需将BYE转发到坐席WebSocket客户端,
 *       通知坐席端释放媒体与UI状态(如JsSIP关闭本地媒体流、更新通话界面)
 * 预期结果: BYE按被叫(坐席)注册状态转发:已注册坐席转发到WebSocket,未注册转发到第三方SIP
 * 处理逻辑:
 *   1. 从请求中提取Call-ID
 *   2. 校验To头完整性(To头指向被挂断的坐席分机)
 *   3. 通过forwardRequestByRegistration按To头用户注册状态转发:
 *      - 已注册(坐席在线): forwardToWebSocketByUser转发到坐席WebSocket
 *      - 未注册: forwardToThirdParty转发到第三方SIP服务
 *
 * B2BUA两段BYE协调说明:
 *   - 本处理器负责"FS→坐席"段BYE转发(对端挂断后FS通知坐席场景)
 *   - "坐席→FS"段BYE(坐席主动挂断)由WsByeRequestHandler处理
 *   - 两段BYE相互独立,FS与坐席端各自处理媒体释放
 *   - CallInfo/会话信息的清理由CHANNEL_HANGUP_COMPLETE事件处理器统一完成,BYE处理器不参与清理
 *
 * @author ipcc
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.BYE)
public class SipByeRequestHandler extends AbstractSipRequestHandler {

    /**
     * 处理传入BYE请求
     *
     * 需求: FS/第三方SIP发起BYE挂断,B2BUA将BYE转发到坐席WebSocket(或第三方SIP)
     * 预期结果: BYE按To头用户注册状态转发到对应目标
     * 处理逻辑: 校验To头后调用forwardRequestByRegistration按注册状态转发;
     *          To头不完整时回送400 BAD_REQUEST
     * 异常场景: To头信息不完整时回送BAD_REQUEST响应;转发失败由底层抛出异常
     * 前置条件: 调用方已从请求中提取callId并判定消息来源source
     *
     * @param request SIP BYE请求
     * @param callId  Call-ID(由调用方提取,非空)
     * @param source  消息来源(FREESWITCH或THIRD_PARTY),BYE转发逻辑不依赖此参数,
     *                统一按To头用户注册状态决定转发目标
     * @throws Exception To头校验失败时回送错误响应,转发失败时抛出异常
     */
    @Override
    public void handle(Request request, String callId, String source) throws Exception {
        log.info("[handleIncomingRequest][处理传入BYE请求] callId={}, source={}, 挂断方向=FreeSWITCH→坐席",
                callId, source);

        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);

        if (!validateToHeader(toUser, toDomain)) {
            log.warn("[handleIncomingRequest][BYE To头不完整,回送错误响应] callId={}", callId);
            sendErrorResponse(callId, request, Response.BAD_REQUEST);
            return;
        }

        // 按被叫(坐席)注册状态转发BYE:已注册→WebSocket,未注册→第三方SIP
        forwardRequestByRegistration(request, callId, toUser, toDomain);
        log.info("[handleIncomingRequest][BYE已转发到坐席WebSocket] callId={}, toUser={}, 挂断方向=FreeSWITCH→坐席",
                callId, toUser);
    }
}
