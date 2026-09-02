package cn.ipcc.sipproxy.core.register;

import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import cn.ipcc.sipproxy.support.model.GatewayRegisterInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关注册过期清理任务
 * <p>
 * 设计意图：与 {@link GatewayRegistry#get} 的惰性校验互补，每 60 秒扫描一次注册模式网关，
 * 触发过期绑定的惰性清理（删除 Redis 绑定与索引），并记录离线日志。
 * 仅扫描 registerEnabled=1 的网关，避免对静态直连网关产生噪音日志。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class GatewayRegisterCleaner {

    /** 清理任务执行间隔：60 秒 */
    private static final long CLEAN_INTERVAL_MS = 60_000L;

    @Resource
    private GatewayRegistry gatewayRegistry;

    @Resource
    private GatewayProvider gatewayProvider;

    /**
     * 定时清理过期注册绑定
     * <p>
     * 处理逻辑：遍历启用网关中 registerEnabled=1 的注册模式网关，
     * 调用 GatewayRegistry.get 触发过期校验（过期自动惰性清除并返回 null）。
     * 异常仅记录日志不中断扫描（下次周期重试）。
     */
    @Scheduled(fixedDelay = CLEAN_INTERVAL_MS)
    public void cleanExpiredRegisters() {
        try {
            List<GatewayInfo> gateways = gatewayProvider.listEnabledGateways();
            if (gateways == null) {
                return;
            }
            for (GatewayInfo gw : gateways) {
                if (gw == null || !Integer.valueOf(1).equals(gw.getRegisterEnabled())) {
                    continue;
                }
                GatewayRegisterInfo reg = gatewayRegistry.get(Long.valueOf(gw.getId()));
                if (reg == null) {
                    log.info("[cleanExpiredRegisters][网关注册已过期或未注册] gatewayId={}, name={}",
                            gw.getId(), gw.getName());
                }
            }
        } catch (Exception e) {
            log.warn("[cleanExpiredRegisters][扫描异常,下次重试] msg={}", e.getMessage());
        }
    }
}