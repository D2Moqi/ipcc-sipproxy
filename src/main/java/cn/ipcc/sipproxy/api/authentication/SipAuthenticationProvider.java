package cn.ipcc.sipproxy.api.authentication;

/**
 * SIP Digest 认证扩展点（可选实现）
 * <p>
 * 设计意图：父程序完全接管 Digest 认证时实现此接口，适用于已有完整认证体系
 * （如对接外部鉴权系统、RADIUS 服务器等）的场景。
 * <p>
 * 责任划分（对齐架构设计 5.1 节）：
 * <ul>
 *   <li>默认场景（父程序未实现本接口）：sipproxy 内部通过 {@code AgentInfoProvider}
 *       获取坐席密码，本地完成 Digest HA1/HA2/response 计算与校验</li>
 *   <li>覆盖场景（父程序实现本接口）：sipproxy 委托本接口做完整认证校验，
 *       跳过本地 Digest 计算，父程序可对接已有认证体系</li>
 * </ul>
 * <p>
 * 调用时机：JSSIP 发起 REGISTER 请求，sipproxy 返回 401 挑战后，
 * 客户端携带 Authorization 头再次请求时调用此方法验证。
 *
 * @author ipcc
 */
public interface SipAuthenticationProvider {

    /**
     * 校验坐席 Digest 凭证
     * <p>
     * 父程序可基于完整的 Digest 认证参数进行校验，包括：
     * <ul>
     *   <li>查询坐席密码后本地计算 HA1/HA2/response 比对</li>
     *   <li>对接外部鉴权系统完成校验</li>
     * </ul>
     *
     * @param extension 分机号（Authorization 头的 username）
     * @param domain    认证域（Authorization 头的 realm）
     * @param nonce     401 响应下发的 nonce
     * @param uri       Authorization 头的 URI（ Digest HA2 计算入参）
     * @param response  客户端计算的 Digest response 值
     * @param method    SIP 方法（REGISTER / INVITE 等，Digest HA2 计算入参）
     * @return 校验通过返回 true，失败返回 false
     */
    boolean authenticate(String extension, String domain, String nonce,
                         String uri, String response, String method);
}
