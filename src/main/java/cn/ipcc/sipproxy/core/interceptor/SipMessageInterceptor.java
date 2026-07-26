package cn.ipcc.sipproxy.core.interceptor;

import javax.sip.message.Message;

/**
 * SIP 消息拦截器扩展点
 * <p>
 * 父程序可实现该接口，在 SIP 消息转发前后插入自定义逻辑。
 * 核心用途：REFER 转接的 ESL 编排（父程序在拦截器内调用 FsClient / FsCallCacheService 完成
 * originate/bridge/hold/kill 等话务操作），sipproxy 仅负责 SIP 信令转发。
 * <p>
 * 设计约束：sipproxy 不连接 FreeSWITCH，所有 ESL 操作通过此拦截器委托父程序实现，
 * 避免将 FsClient、CallInfo、ChannelInfo 等 cc-server 内部类暴露给 sipproxy。
 * <p>
 * 拦截器返回值约定：
 * <ul>
 *   <li>{@code true} 表示父程序已接管该消息，sipproxy 不再转发；</li>
 *   <li>{@code false} 表示继续走 sipproxy 默认转发逻辑。</li>
 * </ul>
 */
public interface SipMessageInterceptor {

    /**
     * WS → SIP 转发前拦截
     * <p>
     * 触发场景：坐席通过 WebSocket 发送 SIP 消息（如 REFER 转接请求），sipproxy 准备转发到 FS/第三方前。
     * 父程序可在此阶段检测 REFER 方法，调用 FsClient 完成 ESL 编排后返回 true 接管。
     *
     * @param message SIP 消息（坐席发出的 Request）
     * @return true 表示已接管（sipproxy 不再转发），false 表示继续转发
     */
    boolean preWsToSip(Message message);

    /**
     * SIP → WS 转发前拦截
     * <p>
     * 触发场景：FS/第三方发送 SIP 消息到坐席，sipproxy 准备转发到 WebSocket 前。
     * 父程序可在此阶段插入自定义逻辑（如话单记录、状态同步）。
     *
     * @param message SIP 消息（FS/第三方发来的 Request 或 Response）
     * @return true 表示已接管（sipproxy 不再转发），false 表示继续转发
     */
    boolean preSipToWs(Message message);
}
