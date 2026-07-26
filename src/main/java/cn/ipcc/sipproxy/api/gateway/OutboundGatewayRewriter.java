package cn.ipcc.sipproxy.api.gateway;

import cn.ipcc.sipproxy.support.model.GatewayInfo;
import javax.sip.message.Request;

/**
 * 出局信令改写扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，自定义出局 INVITE 的头域改写逻辑（From、PAI、Authorization 等）。
 * 默认实现执行标准 6 步改写（From User/Domain、Contact、PAI、Authorization、Route、User-Agent）。
 * <p>
 * 改写时机：sipproxy 在通过 GatewayRouteService 选定目标网关后、转发 INVITE 到第三方网关前调用。
 */
public interface OutboundGatewayRewriter {

    /**
     * 改写出局请求
     *
     * @param request     出局 SIP 请求（INVITE 为主）
     * @param gatewayInfo 目标网关信息（提供 proxy、externalLineNumber、fromDomain、realm 等改写参数）
     */
    void rewrite(Request request, GatewayInfo gatewayInfo);
}
