package cn.ipcc.sipproxy.core.session;

import gov.nist.javax.sip.stack.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FS 入站连接注册表
 * <p>
 * RFC 3261 §18.2.2 规定连接导向传输(TCP)的响应必须沿请求到达的同一连接回送。
 * 本环境 CC FS→代理的 TCP INVITE 经 nps 隧道进入(连接对端为 127.0.0.1 隧道出口),
 * 而响应若走 SipProvider.sendResponse 按 Via 路由会新建连接到 Via sent-by 公网地址,
 * 在隧道拓扑下该地址不可达 FS(且 cleanViaHeaderForTcpRequest 已剥离 received/rport,
 * Via 上无任何指向入站连接的锚点)→ 响应实测未达 FS,FS 腿 Timer B 408。
 * <p>
 * 解决方式: INVITE 到达时在 {@code SipProxyService.processRequest} 将该请求的入站
 * MessageChannel 按 callId 注册到本表;回送 FS 方向响应时优先取缓存 channel 直接
 * sendResponse,复用 FS 主动建立的持久 TCP 连接原路返回(隧道出口→隧道→FS),
 * 失败再回退现有 Via 路由直发逻辑。
 * <p>
 * 清理策略: 当前无统一的会话清理钩子,采用惰性清理——注册/读取时顺带清理超过
 * TTL(30分钟,覆盖 SIP 会话最长生命周期)的过期条目,防止呼叫结束后的条目泄漏。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class FsInboundChannelRegistry {

    /** 条目存活上限: 30分钟(覆盖单通呼叫生命周期,过期惰性清理) */
    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    /** callId → 入站连接条目 */
    private final Map<String, Entry> channels = new ConcurrentHashMap<>();

    /**
     * 注册指定呼叫的入站连接
     *
     * @param callId  呼叫 Call-ID
     * @param channel 该呼叫 INVITE 到达时使用的入站 MessageChannel
     */
    public void register(String callId, MessageChannel channel) {
        if (callId == null || channel == null) {
            return;
        }
        evictExpired();
        channels.put(callId, new Entry(channel, System.currentTimeMillis()));
    }

    /**
     * 获取指定呼叫的入站连接(未注册或已过期返回 null)
     *
     * @param callId 呼叫 Call-ID
     * @return 入站 MessageChannel,不存在时返回 null
     */
    public MessageChannel get(String callId) {
        if (callId == null) {
            return null;
        }
        Entry entry = channels.get(callId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp > TTL_MILLIS) {
            channels.remove(callId);
            return null;
        }
        return entry.channel;
    }

    /**
     * 移除指定呼叫的入站连接记录(呼叫结束时可显式调用,非必须)
     *
     * @param callId 呼叫 Call-ID
     */
    public void remove(String callId) {
        if (callId != null) {
            channels.remove(callId);
        }
    }

    /**
     * 惰性清理过期条目(每次注册时顺带执行,避免呼叫结束后的条目长期驻留)
     */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        channels.entrySet().removeIf(e -> now - e.getValue().timestamp > TTL_MILLIS);
    }

    /** 缓存条目: 入站连接 + 注册时间戳 */
    private static class Entry {
        final MessageChannel channel;
        final long timestamp;

        Entry(MessageChannel channel, long timestamp) {
            this.channel = channel;
            this.timestamp = timestamp;
        }
    }
}
