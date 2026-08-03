package cn.ipcc.sipproxy.defaults.fs;

import cn.ipcc.sipproxy.api.fs.FsNodeProvider;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

/**
 * FS 节点查询默认实现
 * <p>
 * 设计意图：父程序未实现 {@link FsNodeProvider} 时的兜底实现。
 * 当容器中存在 {@link JdbcTemplate}（即父程序配置了数据源）时，查询 H2 seed 数据表 sip_fs_node，
 * 返回 status=1（启用）的节点列表，使 sipproxy 在无父程序扩展实现时仍可基于内置示例数据转发信令；
 * 当 JdbcTemplate 为 null（父程序未配置数据源）时返回空列表，保持向后兼容，服务可启动但呼叫功能不可用。
 * <p>
 * 父程序实现 {@link FsNodeProvider} 接口并注册为 Bean 即可覆盖此默认实现，
 * 为 sipproxy 提供在线 FreeSWITCH 节点列表。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultFsNodeProvider implements FsNodeProvider {

    /** 可选的 JDBC 查询模板，为 null 表示父程序未配置数据源，此时退化为返回空列表 */
    private final JdbcTemplate jdbcTemplate;

    public DefaultFsNodeProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<FsNodeInfo> listFsNodes() {
        if (jdbcTemplate == null) {
            log.debug("[listFsNodes][默认实现返回空列表，父程序未提供 FS 节点且未配置数据源]");
            return Collections.emptyList();
        }
        try {
            // 查询 H2 seed 数据表 sip_fs_node，仅返回 status=1（启用）的节点
            // BeanPropertyRowMapper 自动完成下划线列名到驼峰字段的映射
            return jdbcTemplate.query(
                    "SELECT * FROM sip_fs_node WHERE status = 1",
                    new BeanPropertyRowMapper<>(FsNodeInfo.class));
        } catch (Exception e) {
            // 表不存在或查询异常时降级为空列表，避免阻断 sipproxy 信令转发链路
            log.warn("[listFsNodes][H2 查询异常，降级返回空列表] msg={}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
