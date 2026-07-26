package cn.ipcc.sipproxy.api.authentication;

/**
 * SIP Digest 认证扩展点
 * <p>
 * 父程序实现该接口，为 sipproxy 提供坐席密码校验能力。
 * 用于 REGISTER 请求的 Digest 认证流程，sipproxy 不直接访问父程序的坐席表，
 * 仅通过此接口委托父程序完成凭证校验。
 */
public interface SipAuthenticationProvider {

    /**
     * 校验坐席凭证
     *
     * @param extension 分机号（坐席用户名）
     * @param domain    域名（可空，空表示不区分域名）
     * @param password  明文密码或 HA1 摘要（由父程序决定校验方式）
     * @return 校验通过返回 true，失败返回 false
     */
    boolean authenticate(String extension, String domain, String password);
}
