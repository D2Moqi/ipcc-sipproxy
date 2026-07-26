package cn.ipcc.sipproxy.support;

import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 网关类型枚举
 * <p>
 * 设计意图：从原 {@code cn.iocoder.yudao.module.cc.esl.enums.GatewayTypeEnum} 拷贝至 sipproxy 模块，
 * 避免 sipproxy 反向依赖 cc-server 的 esl 包。原枚举使用 hutool ObjectUtil，
 * 此处改为 JDK 标准库 {@link Objects#equals} 以解除 hutool 依赖。
 * <p>
 * 用于 {@code GatewayInfo.type} 字段标识，区分注册型网关与 IP 型网关。
 */
@RequiredArgsConstructor
@Getter
public enum GatewayTypeEnum {

    /** 1- 内部网关 */
    INTERNAL(1, "internal"),

    /** 2- 外部网关 */
    EXTERNAL(2, "external"),

    ;

    private final Integer type;

    private final String desc;

    /**
     * 根据类型获取枚举
     *
     * @param type 类型值（1 或 2）
     * @return 对应的枚举值
     * @throws IllegalArgumentException 当传入的类型值无对应枚举时抛出
     */
    public static GatewayTypeEnum getByType(Integer type) {
        for (GatewayTypeEnum gatewayTypeEnum : GatewayTypeEnum.values()) {
            if (Objects.equals(gatewayTypeEnum.getType(), type)) {
                return gatewayTypeEnum;
            }
        }
        throw new IllegalArgumentException(String.format("[%s]不是有效的枚举类型", type));
    }
}
