package cn.ipcc.sipproxy.core.session;

import cn.hutool.core.text.StrPool;
import cn.ipcc.sipproxy.support.RedisConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * SIP会话管理器（重构版）
 * 统一使用 SESSION_INFO_PREFIX 管理所有会话信息
 *
 * @author 芋道源码
 */
@Slf4j
@Component
public class SipSessionManager {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 缓存会话信息
     *
     * @param sessionInfo 会话信息
     */
    public void cacheSessionInfo(SessionInfo sessionInfo) {
        log.debug("[cacheSessionInfo][缓存会话信息] callId={}, callType={}", sessionInfo.getCallId(), sessionInfo.getCallType());
        try {
            String sessionInfoJson = objectMapper.writeValueAsString(sessionInfo);
            stringRedisTemplate.opsForValue().set(RedisConstants.SESSION_INFO_PREFIX + sessionInfo.getCallId(), sessionInfoJson, RedisConstants.REFRESH_TIME, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("[cacheSessionInfo][序列化会话信息失败] callId={}", sessionInfo.getCallId(), e);
        }
    }

    /**
     * 获取会话信息
     *
     * @param callId Call-ID
     * @return 会话信息
     */
    public SessionInfo getSessionInfo(String callId) {
        log.debug("[getSessionInfo][获取会话信息] callId={}", callId);
        try {
            String sessionInfoJson = stringRedisTemplate.opsForValue().get(RedisConstants.SESSION_INFO_PREFIX + callId);
            if (sessionInfoJson != null) {
                return objectMapper.readValue(sessionInfoJson, SessionInfo.class);
            }
        } catch (Exception e) {
            log.error("[getSessionInfo][反序列化会话信息失败] callId={}", callId, e);
        }
        return null;
    }

    /**
     * 更新会话信息
     *
     * @param sessionInfo 会话信息
     */
    public void updateSessionInfo(SessionInfo sessionInfo) {
        log.debug("[updateSessionInfo][更新会话信息] callId={}", sessionInfo.getCallId());
        cacheSessionInfo(sessionInfo);
    }


    /**
     * 缓存注册信息
     *
     * 需求: REGISTER 注册成功后，缓存 WebSocket 会话ID与用户名的双向映射，供后续 INVITE 转发到 WebSocket 时查找会话
     * 预期结果: Redis 中缓存 sessionId→username:domain 和 username:domain→sessionId 两个映射
     * 处理逻辑:
     *   1. 缓存 sessionId → "username:domain" 映射，用于 WebSocket 断开时反查 username/domain 清理
     *   2. 缓存 "username:domain" → sessionId 映射，用于 forwardToWebSocketByUser 查找 WebSocket 会话
     *   3. TTL 使用 REGISTER_REFRESH_TIME(3600秒)，大于 JsSIP 默认 REGISTER Expires(1800秒)，
     *      避免 WebSocket 连接存活但 Redis 缓存过期导致 INVITE 转发失败
     * 异常场景: Redis 不可用时抛出异常，由调用方处理
     *
     * @param sessionId WebSocket会话ID
     * @param username  用户名
     * @param domain    域名
     */
    public void cacheRegisterInfo(String sessionId, String username, String domain) {
        log.debug("[cacheRegisterInfo][缓存注册信息] sessionId={}, username={}, domain={}", sessionId, username, domain);
        String registerKey = RedisConstants.SESSION_REGISTER_MAPPING_PREFIX + sessionId;
        String registerValue = username + ":" + domain;
        stringRedisTemplate.opsForValue().set(registerKey, registerValue, RedisConstants.REGISTER_REFRESH_TIME, TimeUnit.SECONDS);

        String userSessionKey = RedisConstants.USER_SESSION_MAPPING_PREFIX + username + ":" + domain;
        stringRedisTemplate.opsForValue().set(userSessionKey, sessionId, RedisConstants.REGISTER_REFRESH_TIME, TimeUnit.SECONDS);
    }

    /**
     * 根据用户获取会话ID
     *
     * @param username 用户名
     * @param domain   域名
     * @return WebSocket会话ID
     */
    public String getSessionIdByUser(String username, String domain) {
        log.debug("[getSessionIdByUser][根据用户名和域名获取WebSocket会话ID] username={}, domain={}", username, domain);
        String userSessionKey = RedisConstants.USER_SESSION_MAPPING_PREFIX + username + ":" + domain;
        return stringRedisTemplate.opsForValue().get(userSessionKey);
    }

    /**
     * 清理注册信息（供WebSocket连接关闭时调用）
     *
     * @param sessionId WebSocket会话ID
     */
    public void cleanupRegisterInfo(String sessionId) {
        try {
            String[] registerInfo = getCachedRegisterInfo(sessionId);
            if (registerInfo != null && registerInfo.length == 2) {
                String username = registerInfo[0];
                String domain = registerInfo[1];

                log.info("[cleanupRegisterInfo][开始清理注册信息] sessionId={}, username={}, domain={}",
                        sessionId, username, domain);

                stringRedisTemplate.delete(RedisConstants.SESSION_REGISTER_MAPPING_PREFIX + sessionId);
                stringRedisTemplate.delete(RedisConstants.USER_SESSION_MAPPING_PREFIX + username + ":" + domain);
            } else {
                log.debug("[cleanupRegisterInfo][未找到缓存的注册信息] sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.error("[cleanupRegisterInfo][清理注册信息异常] sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取缓存的注册信息（内部方法）
     *
     * @param sessionId WebSocket会话ID
     * @return 注册信息数组 [username, domain]
     */
    private String[] getCachedRegisterInfo(String sessionId) {
        String registerKey = RedisConstants.SESSION_REGISTER_MAPPING_PREFIX + sessionId;
        String registerValue = stringRedisTemplate.opsForValue().get(registerKey);
        if (registerValue != null) {
            return registerValue.split(StrPool.COLON);
        }
        return null;
    }
}
