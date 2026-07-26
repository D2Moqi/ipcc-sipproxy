package cn.ipcc.sipproxy.core.annotation;

import java.lang.annotation.*;

/**
 * SIP 方法注解
 * 用于标记 SIP 请求处理器处理的 SIP 方法类型
 *
 * 使用示例：
 * @SipMethod("INVITE")
 * public class InviteRequestHandler extends AbstractSipRequestHandler { }
 *
 * @author 芋道源码
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SipMethod {

    /**
     * SIP 方法名称（如 INVITE、REGISTER、OPTIONS 等）
     */
    String value();
}
