package cn.ipcc.sipproxy.api.fs;

import cn.ipcc.sipproxy.support.model.FsNodeInfo;

import java.util.List;

/**
 * FS 节点查询扩展点
 * <p>
 * 父程序实现该接口，为 sipproxy 提供在线 FreeSWITCH 节点列表。
 * sipproxy 在选择 SIP 信令转发目标时调用此接口，避免直接依赖父程序的 FsConfigService。
 * <p>
 * 设计约束：sipproxy 不连接 FreeSWITCH，仅承担信令转发；ESL 由父程序通过
 * {@code cn.ipcc.sipproxy.core.interceptor.SipMessageInterceptor} 实现。
 */
public interface FsNodeProvider {

    /**
     * 获取所有在线 FS 节点
     *
     * @return 在线 FS 节点列表（空列表表示无在线节点，由调用方按"无可用节点"异常处理）
     */
    List<FsNodeInfo> listFsNodes();
}
