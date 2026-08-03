package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Request;

/**
 * BYE请求处理器(WebSocket来源)
 *
 * 需求: 坐席通过JsSIP发起BYE挂断通话时,SIP代理作为B2BUA需将BYE转发到FreeSWITCH,
 *       由FS拆除其上锚定的媒体通道并触发CHANNEL_HANGUP_COMPLETE事件
 * 预期结果: BYE转发到原INVITE锚定的FS节点,FS收到后释放媒体资源并上报挂断事件
 * 处理逻辑:
 *   1. 从请求中提取Call-ID(由模板方法handle统一提取并传入doHandle)
 *   2. 选择FS节点: selectFreeSwitchNode内部优先返回原INVITE通过cacheSessionNode写入的会话绑定节点,
 *      确保BYE发到同一FS以正确拆除该FS上的通道;仅当会话节点缺失或离线时才按callId哈希重选
 *   3. 通过SipMessageForwarder.forwardToFreeSwitch转发BYE到选定FS节点
 *
 * B2BUA两段BYE协调说明:
 *   - 本处理器负责"坐席→FS"段BYE转发(坐席主动挂断场景)
 *   - "FS→坐席"段BYE(对端挂断后FS通知坐席)由SipByeRequestHandler处理
 *   - 两段BYE相互独立,FS与坐席端各自处理媒体释放
 *   - CallInfo/会话信息的清理由CHANNEL_HANGUP_COMPLETE事件处理器统一完成,BYE处理器不参与清理
 *
 * @author ipcc
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.BYE)
public class WsByeRequestHandler extends AbstractWsSipRequestHandler {

    /**
     * 处理WebSocket来源的BYE请求
     *
     * 需求: 坐席发起BYE挂断,B2BUA将BYE转发到FS拆除FS侧通道
     * 预期结果: BYE成功转发到会话绑定的FS节点
     * 处理逻辑: 选择会话绑定的FS节点并转发BYE;节点不可用时抛出异常,
     *          由模板方法handle捕获后回送500错误响应
     * 异常场景: 没有可用FS节点时抛出Exception,模板方法会捕获并发送SERVER_INTERNAL_ERROR响应
     * 前置条件: 请求已通过模板方法的Call-ID校验与validateRequest校验
     *
     * @param sessionId WebSocket会话ID
     * @param request   SIP BYE请求
     * @param callId    Call-ID(由模板方法从请求中提取,非空)
     * @throws Exception 没有可用FS节点或转发失败时抛出
     */
    @Override
    protected void doHandle(String sessionId, Request request, String callId) throws Exception {
        log.info("[doHandle][BYE] callId={}, 挂断方向=坐席→FreeSWITCH", callId);

        FsNodeInfo freeSwitchNode = nodeManager.selectFreeSwitchNode(callId);
        if (freeSwitchNode == null) {
            log.error("[doHandle][没有可用的FreeSWITCH节点] callId={}", callId);
            throw new Exception("没有可用的FreeSWITCH节点");
        }

        messageForwarder.forwardToFreeSwitch(request, freeSwitchNode);
        log.info("[doHandle][BYE已转发到FreeSWITCH] callId={}, fs={}:{}, 挂断方向=坐席→FreeSWITCH",
                callId, freeSwitchNode.getSipIp(), freeSwitchNode.getSipPort());
    }
}
