package cn.ipcc.sipproxy.api.agent;

import cn.ipcc.sipproxy.support.model.AgentInfo;

/**
 * 坐席信息查询扩展点
 * <p>
 * 父程序实现该接口，为 sipproxy 提供坐席信息查询能力。
 * 用于 INVITE/REGISTER 等请求处理时获取坐席的显示名称、租户归属等元数据，
 * 避免 sipproxy 直接依赖父程序的 SysAgentService。
 */
public interface AgentInfoProvider {

    /**
     * 查询坐席信息
     *
     * @param extension 分机号
     * @param domain    域名（可空，空表示不区分域名）
     * @return 坐席信息（不存在返回 null，由调用方按业务场景处理）
     */
    AgentInfo getAgent(String extension, String domain);
}
