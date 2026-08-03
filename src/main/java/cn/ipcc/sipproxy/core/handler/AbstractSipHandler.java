package cn.ipcc.sipproxy.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.core.forwarder.SipMessageForwarder;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * SIP 处理器公共基类
 * 包含所有 SIP 处理器共用的方法和依赖
 *
 * @author ipcc
 */
@Slf4j
public abstract class AbstractSipHandler {

    /**
     * 坐席信息查询扩展点（替代原 SysAgentService）
     * <p>
     * 通过 AgentInfoProvider 间接获取坐席信息，避免 sipproxy 直接依赖父程序的 SysAgentService。
     */
    @Resource
    protected AgentInfoProvider agentInfoProvider;

    @Resource
    protected SipMessageForwarder messageForwarder;

    @Resource
    protected SipSessionManager sessionManager;

    /**
     * 验证用户是否已注册
     *
     * @param username 用户名
     * @param domain   域名
     * @return 是否已注册
     */
    protected boolean isRegisteredUser(String username, String domain) {
        try {
            // 原调用 sysAgentService.listByNameAndDomainNoTenant(username, domain) 返回 List
            // 新接口 AgentInfoProvider.getAgent 返回单个 AgentInfo，非 null 即视为已注册
            AgentInfo agent = agentInfoProvider.getAgent(username, domain);
            return agent != null;
        } catch (Exception e) {
            log.error("[isRegisteredUser][查询坐席表失败] username={}, domain={}", username, domain, e);
            return false;
        }
    }

    /**
     * 判断坐席是否在线(WebSocket连接是否存活)
     *
     * 需求: 路由决策时需要判断目标坐席当前是否可接听,避免向离线坐席发起呼叫
     * 预期结果: 坐席WebSocket连接存活时返回true,否则false
     * 处理逻辑: 查询Redis中的username:domain→sessionId映射,sessionId存在即在线
     *
     * @param username 坐席用户名(分机号)
     * @param domain   域名
     * @return 在线返回true,离线返回false
     */
    protected boolean isAgentOnline(String username, String domain) {
        try {
            String sessionId = sessionManager.getSessionIdByUser(username, domain);
            return StrUtil.isNotBlank(sessionId);
        } catch (Exception e) {
            log.error("[isAgentOnline][查询坐席在线状态失败] username={}, domain={}", username, domain, e);
            return false;
        }
    }
}
