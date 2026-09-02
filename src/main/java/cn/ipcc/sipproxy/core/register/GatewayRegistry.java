package cn.ipcc.sipproxy.core.register;

import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.support.RedisConstants;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import cn.ipcc.sipproxy.support.model.GatewayRegisterInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 网关注册绑定管理器
 * <p>
 * 设计意图：仿照 {@code SipSessionManager} 的 Redis 读写模式，管理「注册型网关」的
 * REGISTER 绑定（gatewayId → 可达地址/有效期）。呼出目标解析、呼入来源识别、
 * 管理后台注册状态均经此查询。
 * <p>
 * 处理逻辑：
 * <ol>
 *   <li>bind：REGISTER 校验通过后写入绑定（TTL=Expires+余量，周期刷新自然续期）+ 账号反查索引</li>
 *   <li>get：查询并惰性校验过期（过期即清除并返回 null）</li>
 *   <li>unbind：REGISTER Expires=0 注销或过期清理时删除绑定与索引</li>
 * </ol>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class GatewayRegistry {

    /** 过期余量（秒）：绑定 TTL = 注册有效期 + 余量，避免边界抖动导致绑定提前失效 */
    private static final long EXPIRE_GRACE_SECONDS = 60L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 绑定网关注册（REGISTER 校验通过后调用）
     * <p>
     * 预期结果：Redis 写入 gatewayId→绑定 JSON（TTL=expires+60s）与 username→gatewayId 索引。
     * 网关周期 REGISTER 刷新时重复调用，Redis set 幂等覆盖并续期。
     *
     * @param gateway        网关信息（提供 id/username）
     * @param contactIp      注册 Contact host（可空：网关未携带 Contact 时退化为源 IP）
     * @param contactPort    注册 Contact port（null 按 5060）
     * @param transport      传输协议（null 按 udp）
     * @param expiresSeconds 注册有效期（秒，已受网关 registerMaxExpires 上限约束）
     * @param sourceIp       REGISTER 报文来源 IP
     */
    public void bind(GatewayInfo gateway, String contactIp, Integer contactPort,
                     String transport, long expiresSeconds, String sourceIp) {
        GatewayRegisterInfo info = new GatewayRegisterInfo();
        info.setGatewayId(Long.valueOf(gateway.getId()));
        info.setUsername(gateway.getUsername());
        // Contact 缺失时回退源 IP（网关未带 Contact 或 Contact host 不可达的兜底）
        info.setContactIp(StrUtil.isNotBlank(contactIp) ? contactIp : sourceIp);
        info.setContactPort(contactPort != null ? contactPort : 5060);
        info.setTransport(StrUtil.isBlank(transport) ? "udp" : transport.toLowerCase());
        info.setExpiresAt(System.currentTimeMillis() + expiresSeconds * 1000);
        info.setSourceIp(sourceIp);
        try {
            String json = objectMapper.writeValueAsString(info);
            long ttl = expiresSeconds + EXPIRE_GRACE_SECONDS;
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.GATEWAY_REGISTER_PREFIX + gateway.getId(), json, ttl, TimeUnit.SECONDS);
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.GATEWAY_REGISTER_USER_PREFIX + gateway.getUsername(),
                    gateway.getId(), ttl, TimeUnit.SECONDS);
            log.info("[GatewayRegistry][网关注册绑定成功] gatewayId={}, username={}, contact={}:{}, expiresAt={}",
                    gateway.getId(), gateway.getUsername(), info.getContactIp(), info.getContactPort(), info.getExpiresAt());
        } catch (Exception e) {
            log.error("[GatewayRegistry][绑定序列化失败] gatewayId={}", gateway.getId(), e);
        }
    }

    /**
     * 查询注册绑定（外部统一入口）
     * <p>
     * 处理逻辑：读取 Redis 绑定 JSON，反序列化后校验 expiresAt 是否超出宽限期；
     * 超出则惰性清除绑定与索引并返回 null（与 GatewayRegisterCleaner 定时清理互补）。
     *
     * <p>过期判断带宽限（EXPIRE_GRACE_SECONDS，与 Redis TTL 余量语义一致）：
     * 网关周期性 REGISTER 续订存在时序抖动（FreeSWITCH sofia gateway 等在到期前后才发起下一轮
     * REGISTER），严格按 expiresAt 失效会把"续订空窗"误判为离线，导致该窗口内的呼出目标解析失败
     * （实测 fs3 模拟网关场景：INVITE 恰落空窗被拒，呼叫 CANCEL）。expiresAt+宽限内仍视为在线，
     * 超出宽限仍未续订才判定离线。</p>
     *
     * @param gatewayId 网关 ID
     * @return 注册绑定信息，未注册/超宽限期返回 null
     */
    public GatewayRegisterInfo get(Long gatewayId) {
        if (gatewayId == null) {
            return null;
        }
        String json = stringRedisTemplate.opsForValue()
                .get(RedisConstants.GATEWAY_REGISTER_PREFIX + gatewayId);
        if (json == null) {
            return null;
        }
        try {
            GatewayRegisterInfo info = objectMapper.readValue(json, GatewayRegisterInfo.class);
            // 宽限判断：expiresAt + 余量 仍未到期 → 视为在线（容忍续订抖动空窗）
            if (info.getExpiresAt() != null) {
                long graceEnd = info.getExpiresAt() + EXPIRE_GRACE_SECONDS * 1000;
                if (graceEnd < System.currentTimeMillis()) {
                    log.warn("[GatewayRegistry][注册绑定超出宽限期仍未续订,判定离线] gatewayId={}, expiresAt={}, graceSeconds={}",
                            gatewayId, info.getExpiresAt(), EXPIRE_GRACE_SECONDS);
                    unbind(gatewayId);
                    return null;
                }
                if (info.getExpiresAt() < System.currentTimeMillis()) {
                    log.debug("[GatewayRegistry][注册绑定已过期,续订宽限期内仍视为在线] gatewayId={}, expiresAt={}",
                            gatewayId, info.getExpiresAt());
                }
            }
            return info;
        } catch (Exception e) {
            log.warn("[GatewayRegistry][反序列化失败,清除异常绑定] gatewayId={}", gatewayId, e);
            stringRedisTemplate.delete(RedisConstants.GATEWAY_REGISTER_PREFIX + gatewayId);
            return null;
        }
    }

    /**
     * 注销注册绑定（Expires=0 或过期清理）
     *
     * @param gatewayId 网关 ID
     */
    public void unbind(Long gatewayId) {
        if (gatewayId == null) {
            return;
        }
        GatewayRegisterInfo info = get(gatewayId);
        if (info != null && StrUtil.isNotBlank(info.getUsername())) {
            stringRedisTemplate.delete(
                    RedisConstants.GATEWAY_REGISTER_USER_PREFIX + info.getUsername());
        }
        stringRedisTemplate.delete(RedisConstants.GATEWAY_REGISTER_PREFIX + gatewayId);
        log.info("[GatewayRegistry][网关注册绑定已清除] gatewayId={}", gatewayId);
    }

    /**
     * 按注册账号反查网关 ID（REGISTER 来源识别/认证反查用）
     *
     * @param username 注册账号
     * @return 网关 ID，未注册返回 null
     */
    public Long getGatewayIdByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        String id = stringRedisTemplate.opsForValue()
                .get(RedisConstants.GATEWAY_REGISTER_USER_PREFIX + username);
        return id != null ? Long.valueOf(id) : null;
    }
}