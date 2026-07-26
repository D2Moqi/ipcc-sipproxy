package cn.ipcc.sipproxy.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIP 消息分片重组器
 * <p>
 * 从原 {@code cc.websocket.core.handler.SipWebSocketMessageHandler.handleMessageFragment} 迁移。
 * 基于 {@code \r\n\r\n} 头部结束标记 + Content-Length 计算完整消息长度，循环提取完整 SIP 消息。
 * <p>
 * 设计思路：
 * <ul>
 *   <li>每个 WebSocket 会话维护独立缓冲区，避免跨会话污染</li>
 *   <li>缓冲区上限 1MB，超过则丢弃并告警（防止恶意客户端撑爆内存）</li>
 *   <li>支持单帧多消息（pipelining）与单消息多帧（分片）两种场景</li>
 * </ul>
 */
public class SipFrameReassembler {

    /** 单会话缓冲区上限：1MB，超过则视为异常并丢弃 */
    private static final int MAX_BUFFER_SIZE = 1024 * 1024;

    /** 会话级缓冲区：sessionId → StringBuilder */
    private final Map<String, StringBuilder> messageBuffers = new ConcurrentHashMap<>();

    /**
     * 重组 SIP 消息分片
     * <p>
     * 执行流程：
     * <ol>
     *   <li>将分片追加到会话缓冲区</li>
     *   <li>缓冲区超限则清空并返回空列表</li>
     *   <li>循环检测 {@code \r\n\r\n} 头部结束标记，按 Content-Length 提取完整消息</li>
     *   <li>从缓冲区移除已提取部分，继续检测下一条消息</li>
     * </ol>
     *
     * @param sessionId WebSocket 会话 ID
     * @param fragment  消息分片
     * @return 完整的 SIP 消息列表（可能为空，表示尚未重组完成）
     */
    public List<String> reassemble(String sessionId, String fragment) {
        List<String> completeMessages = new ArrayList<>();
        StringBuilder buffer = messageBuffers.computeIfAbsent(sessionId, k -> new StringBuilder());
        buffer.append(fragment);

        // 缓冲区超限保护：丢弃并告警（此处无 log 依赖，调用方应通过返回空列表感知异常）
        if (buffer.length() > MAX_BUFFER_SIZE) {
            buffer.setLength(0);
            return completeMessages;
        }

        // 循环提取完整 SIP 消息（支持单帧多消息 pipelining）
        while (true) {
            String buf = buffer.toString();
            int headerEnd = buf.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                break;  // 头部未收齐
            }
            String headers = buf.substring(0, headerEnd);
            int contentLength = parseContentLength(headers);
            int messageEnd = headerEnd + 4 + contentLength;
            if (buf.length() < messageEnd) {
                break;  // 消息体未收齐
            }
            String completeMessage = buf.substring(0, messageEnd);
            completeMessages.add(completeMessage);
            // 移除已提取部分，继续解析下一条
            buffer.delete(0, messageEnd);
        }
        return completeMessages;
    }

    /**
     * 解析 SIP 头部 Content-Length
     * <p>
     * 头部字段名大小写不敏感（RFC 3261），缺失或解析失败返回 0。
     *
     * @param headers SIP 头部文本（不含 \r\n\r\n 结束标记）
     * @return Content-Length 值，缺失/异常返回 0
     */
    private int parseContentLength(String headers) {
        String[] lines = headers.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /**
     * 清理会话缓冲区（连接关闭时调用）
     *
     * @param sessionId WebSocket 会话 ID
     */
    public void cleanup(String sessionId) {
        messageBuffers.remove(sessionId);
    }
}
