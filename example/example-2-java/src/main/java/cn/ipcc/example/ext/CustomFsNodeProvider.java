package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.fs.FsNodeProvider;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * FS 节点查询扩展点自定义实现（替代 {@code DefaultFsNodeProvider}）。
 * <p>
 * 用途：为 sipproxy 提供在线 FreeSWITCH 节点列表，用于 SIP 信令转发目标选择。
 * <p>
 * 数据来源：硬编码单条 FS 节点（不依赖数据库），用于演示集成与本地联调。
 * <ul>
 *   <li>id=1, name=fs-test</li>
 *   <li>sipIp=127.0.0.1, sipPort=5060（sipproxy 转发 SIP 消息的目标地址）</li>
 *   <li>eslIp=127.0.0.1, eslPort=8021（sipproxy 不直接使用，预留父程序拦截器调用 FsClient）</li>
 *   <li>status=1（启用）</li>
 * </ul>
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultFsNodeProvider} 通过 JdbcTemplate 查询 H2 seed 数据；</li>
 *   <li>本实现直接硬编码返回单节点列表，去除数据库依赖。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomFsNodeProvider implements FsNodeProvider {

    /**
     * 返回硬编码 FS 节点列表。
     * <p>
     * 设计说明：每次返回新构造的 FsNodeInfo 避免外部修改污染硬编码数据；
     * 列表不可变（{@link Collections#singletonList}），调用方不应修改返回结果。
     *
     * @return 包含单条硬编码 FS 节点（status=1）的不可变列表
     */
    @Override
    public List<FsNodeInfo> listFsNodes() {
        FsNodeInfo node = new FsNodeInfo();
        node.setId(1L);
        node.setName("fs-test");
        node.setSipIp("127.0.0.1");
        node.setSipPort(5060);
        node.setEslIp("127.0.0.1");
        node.setEslPort(8021);
        node.setStatus(1);
        log.debug("[listFsNodes][返回硬编码 FS 节点] name={}, sipIp={}, sipPort={}", node.getName(), node.getSipIp(), node.getSipPort());
        return Collections.singletonList(node);
    }
}
