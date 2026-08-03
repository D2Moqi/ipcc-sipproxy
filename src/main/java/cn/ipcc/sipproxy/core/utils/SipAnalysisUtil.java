package cn.ipcc.sipproxy.core.utils;

import cn.ipcc.sipproxy.support.SipProxyConstants;
import lombok.extern.slf4j.Slf4j;

import javax.sip.PeerUnavailableException;
import javax.sip.SipFactory;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.*;
import javax.sip.message.Message;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.ListIterator;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * sip消息解析工具类
 */
@Slf4j
public class SipAnalysisUtil {
    /**
     * SIP over WebSocket 协议的子协议名称
     */
    public static final String SIP = "sip";
    public static final String SIPS = "sips";
    /**
     * SIP请求方法 常量
     */
    public static final String INVITE = "INVITE";
    public static final String ACK = "ACK";
    public static final String BYE = "BYE";
    public static final String CANCEL = "CANCEL";
    public static final String REGISTER = "REGISTER";
    public static final String OPTIONS = "OPTIONS";
    public static final String PRACK = "PRACK";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String NOTIFY = "NOTIFY";
    public static final String PUBLISH = "PUBLISH";
    public static final String INFO = "INFO";
    public static final String REFER = "REFER";
    public static final String MESSAGE = "MESSAGE";
    public static final String UPDATE = "UPDATE";

    /**
     * 本工具类专用的 SIP 栈名称(仅用于创建 SipStack 解析消息,不实际运行)
     */
    private static final String STACK_NAME = "SipAnalysisUtilStack";


    private static final MessageFactory messageFactory;
    private static final HeaderFactory headerFactory;
    private static final AddressFactory addressFactory;

    static {
        try {
            // 初始化SIP工厂和相关组件
            SipFactory sipFactory = SipFactory.getInstance();
            sipFactory.setPathName(SipProxyConstants.SIP_STACK_PATH);

            // 设置SIP栈属性
            Properties properties = new Properties();
            properties.setProperty("javax.sip.STACK_NAME", STACK_NAME);
            properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", SipProxyConstants.AUTOMATIC_DIALOG_SUPPORT_OFF);

            // 创建SIP栈（不需要实际运行，仅用于解析）
            sipFactory.createSipStack(properties);

            // 创建消息工厂、头工厂和地址工厂
            messageFactory = sipFactory.createMessageFactory();
            headerFactory = sipFactory.createHeaderFactory();
            addressFactory = sipFactory.createAddressFactory();
        } catch (PeerUnavailableException e) {
            throw new RuntimeException("初始化SIP工厂失败", e);
        }
    }

    /**
     * 判断文本消息是否为sip信息
     *
     * @param text 文本消息
     * @return true表示是sip信息，false表示不是
     */
    public static boolean isSipMessage(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本消息不能为空");
        }
        // SIP消息以SIP/2.0开头，后跟状态码或请求方法
        return isRequest(text) || isResponse(text);
    }

    /**
     * 判断文本消息是否为SIP请求
     *
     * @param text 文本消息
     * @return true表示是SIP请求，false表示不是
     */
    public static boolean isRequest(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本消息不能为空");
        }
        // 正则表达式匹配SIP请求行
        String regex = "^(" + INVITE + "|" + ACK + "|" + BYE + "|"
                + CANCEL + "|" + REGISTER + "|" + OPTIONS + "|" + PRACK + "|" + SUBSCRIBE + "|" + NOTIFY + "|"
                + PUBLISH + "|" + INFO + "|" + REFER + "|" + MESSAGE + "|" + UPDATE + ")";
        return Pattern.compile(regex, Pattern.MULTILINE).matcher(text).find();
    }

    /**
     * 判断SIP文本消息是否为响应
     *
     * @param text 文本消息
     * @return true表示是SIP响应，false表示不是
     */
    public static boolean isResponse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本消息不能为空");
        }
        // SIP响应以SIP/开头，后跟状态码
        String regex = "^SIP/";
        return Pattern.compile(regex, Pattern.MULTILINE).matcher(text).find();
    }

    /**
     * 将SIP文本消息解析为SIP对象
     *
     * @param sipText SIP文本消息
     * @return 解析后的SIP对象（Request或Response）
     * @throws ParseException 如果解析失败
     */
    public static Message parseSipMessage(String sipText) throws ParseException {
        if (sipText == null || sipText.trim().isEmpty()) {
            throw new IllegalArgumentException("SIP文本消息不能为空");
        }
        try {
            if (isRequest(sipText)) {
                return messageFactory.createRequest(sipText);
            } else if (isResponse(sipText)) {
                return messageFactory.createResponse(sipText);
            } else {
                throw new ParseException("无法识别的SIP消息格式", 0);
            }
        } catch (Exception e) {
            // 抛出原始异常
            throw new ParseException("解析SIP消息失败: " + e.getMessage(), 0);
        }
    }

    /**
     * 将SIP文本消息解析为SIP对象Response
     *
     * @param sipText SIP文本消息
     * @return 解析后的SIP对象
     * @throws ParseException 如果解析失败
     */
    public static Response parseSipMessageResponse(String sipText) throws ParseException {
        return (Response) parseSipMessage(sipText);
    }

    /**
     * 将SIP文本消息解析为SIP对象Request
     *
     * @param sipText SIP文本消息
     * @return 解析后的SIP对象
     * @throws ParseException 如果解析失败
     */
    public static Request parseSipMessageRequest(String sipText) throws ParseException {
        return (Request) parseSipMessage(sipText);
    }

    /**
     * 将SIP对象转换回标准SIP文本格式
     *
     * @param message SIP对象
     * @return 标准SIP文本格式
     */
    public static String toSipText(Message message) {
        verifyNullMessage(message);
        try {
            // 使用SIP对象的toString方法获取标准SIP文本
            return message.toString();
        } catch (Exception e) {
            throw new RuntimeException("转换SIP对象为文本失败", e);
        }
    }

    /**
     * 获取SIP请求方法
     *
     * @param request SIP请求
     * @return 请求方法（如INVITE、REGISTER、ACK等）
     */
    public static String getRequestMethod(Request request) {
        if (request == null) {
            throw new IllegalArgumentException("SIP请求不能为空");
        }
        return request.getMethod();
    }

    /**
     * 获取SIP响应状态码
     *
     * @param response SIP响应
     * @return 状态码（如200、404、500等）
     */
    public static int getResponseStatusCode(Response response) {
        if (response == null) {
            throw new IllegalArgumentException("SIP响应不能为空");
        }
        return response.getStatusCode();
    }

    /**
     * 获取SIP消息的Call-ID头值
     *
     * @param message SIP消息
     * @return Call-ID头值
     */
    public static String getCallId(Message message) {
        verifyNullMessage(message);
        CallIdHeader callIdHeader = (CallIdHeader) message.getHeader(CallIdHeader.NAME);
        return callIdHeader != null ? callIdHeader.getCallId() : null;
    }

    /**
     * 获取SIP消息的Branch头值
     *
     * @param message SIP消息
     * @return BranchId头值
     */
    public static String getBranch(Message message) {
        verifyNullMessage(message);
        ViaHeader viaHeader = (ViaHeader) message.getHeader(ViaHeader.NAME);
        if (viaHeader == null) {
            log.warn("[getBranchId][未找到Via头]");
            return null;
        }

        return viaHeader.getBranch();
    }

    /**
     * 获取SIP消息的From头值
     *
     * @param message SIP消息
     * @return From头值
     */
    public static String getFrom(Message message) {
        verifyNullMessage(message);
        FromHeader fromHeader = (FromHeader) message.getHeader(FromHeader.NAME);
        return fromHeader != null ? fromHeader.getAddress().toString() : null;
    }

    /**
     * 获取SIP消息的To头值
     *
     * @param message SIP消息
     * @return To头值
     */
    public static String getTo(Message message) {
        verifyNullMessage(message);
        ToHeader toHeader = (ToHeader) message.getHeader(ToHeader.NAME);
        return toHeader != null ? toHeader.getAddress().toString() : null;
    }

    /**
     * 获取SIP消息的Via头数量
     *
     * @param message SIP消息
     * @return Via头数量
     */
    public static int getViaCount(Message message) {
        verifyNullMessage(message);
        ListIterator<ViaHeader> viaHeaders = message.getHeaders(ViaHeader.NAME);
        int count = 0;
        while (viaHeaders != null && viaHeaders.hasNext()) {
            viaHeaders.next();
            count++;
        }
        return count;
    }

    /**
     * 修改SIP消息的From头
     *
     * @param message SIP消息
     * @param newFrom 新的From头值
     * @return 修改后的SIP消息
     * @throws ParseException 如果解析新From头失败
     */
    public static Message modifyFrom(Message message, String newFrom) throws ParseException {
        verifyNullMessage(message);
        if (newFrom == null || newFrom.trim().isEmpty()) {
            throw new IllegalArgumentException("新的From头值不能为空");
        }

        try {
            // 创建新的From头
            javax.sip.address.Address fromAddress = addressFactory.createAddress(newFrom);
            FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, null);

            // 替换原有的From头
            message.removeHeader(FromHeader.NAME);
            message.addHeader(fromHeader);

            return message;
        } catch (ParseException e) {
            throw new ParseException("修改From头失败: " + e.getMessage(), e.getErrorOffset());
        }
    }

    /**
     * 修改SIP消息的To头
     *
     * @param message SIP消息
     * @param newTo   新的To头值
     * @return 修改后的SIP消息
     * @throws ParseException 如果解析新To头失败
     */
    public static Message modifyTo(Message message, String newTo) throws ParseException {
        verifyNullMessage(message);
        if (newTo == null || newTo.trim().isEmpty()) {
            throw new IllegalArgumentException("新的To头值不能为空");
        }

        try {
            // 创建新的To头
            javax.sip.address.Address toAddress = addressFactory.createAddress(newTo);
            ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

            // 替换原有的To头
            message.removeHeader(ToHeader.NAME);
            message.addHeader(toHeader);

            return message;
        } catch (ParseException e) {
            throw new ParseException("修改To头失败: " + e.getMessage(), e.getErrorOffset());
        }
    }

    /**
     * 构建SIP响应消息
     *
     * @param request    原始请求
     * @param statusCode 状态码
     * @return 构建的响应消息
     * @throws ParseException 如果构建失败
     */
    public static Response buildResponse(Request request, int statusCode) throws ParseException {
        if (request == null) {
            throw new IllegalArgumentException("SIP请求不能为空");
        }

        try {
            // JAIN SIP API 的 createResponse 会自动使用标准原因短语
            return messageFactory.createResponse(statusCode, request);
        } catch (Exception e) {
            throw new ParseException("构建SIP响应失败: " + e.getMessage(), 0);
        }
    }

    /**
     * 从SIP消息中提取Authorization头
     *
     * @param message SIP消息
     * @return Authorization头
     */
    public static AuthorizationHeader getAuthorization(Message message) {
        verifyNullMessage(message);
        return (AuthorizationHeader) message.getHeader(AuthorizationHeader.NAME);
    }

    /**
     * 从SIP消息中提取From头的SIP URI
     *
     * @param message SIP消息
     * @return From头的SIP URI
     */
    public static SipURI getFromSipUri(Message message) {
        verifyNullMessage(message);
        FromHeader fromHeader = (FromHeader) message.getHeader(FromHeader.NAME);
        Address fromAddress = fromHeader.getAddress();
        URI fromUri = fromAddress.getURI();
        return (SipURI) fromUri;
    }

    /**
     * 从SIP消息中提取From头的用户名
     *
     * @param message SIP消息
     * @return From头的用户名
     */
    public static String getFromUser(Message message) {
        verifyNullMessage(message);
        FromHeader fromHeader = (FromHeader) message.getHeader(FromHeader.NAME);
        Address fromAddress = fromHeader.getAddress();
        URI fromUri = fromAddress.getURI();
        SipURI sipUri = (SipURI) fromUri;
        return sipUri.getUser();
    }

    /**
     * 从SIP消息中提取From头的域名
     *
     * @param message SIP消息
     * @return From头的域名
     */
    public static String getFromDomain(Message message) {
        verifyNullMessage(message);
        FromHeader fromHeader = (FromHeader) message.getHeader(FromHeader.NAME);
        Address fromAddress = fromHeader.getAddress();
        URI fromUri = fromAddress.getURI();
        SipURI sipUri = (SipURI) fromUri;
        // JAIN-SIP SipURI.getPort() 在 URI 未显式声明端口时返回 -1，
        // 直接拼接会得到 "domain:-1" 的非法域名，需在端口<=0 时仅返回 host
        int port = sipUri.getPort();
        return port > 0 ? sipUri.getHost() + ":" + port : sipUri.getHost();
    }

    /**
     * 验证SIP消息是否为空
     *
     * @param message SIP消息
     */
    private static void verifyNullMessage(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("SIP消息不能为空");
        }
    }

    /**
     * 提取被叫用户名
     */
    public static String extractToUser(Request request) {
        try {
            ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
            if (toHeader == null) {
                return null;
            }
            javax.sip.address.SipURI toUri = (javax.sip.address.SipURI) toHeader.getAddress().getURI();
            return toUri.getUser();
        } catch (Exception e) {
            log.error("[extractToUser][提取To头用户名失败]", e);
            return null;
        }
    }

    /**
     * 提取被叫域名
     */
    public static String extractToDomain(Request request) {
        try {
            ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
            if (toHeader == null) {
                return null;
            }
            javax.sip.address.SipURI toUri = (javax.sip.address.SipURI) toHeader.getAddress().getURI();
            // 与 getFromDomain 保持一致：SipURI.getPort() 在 URI 无显式端口时返回 -1，
            // 此时仅返回 host，避免拼出 "domain:-1" 的非法域名
            int port = toUri.getPort();
            return port > 0 ? toUri.getHost() + ":" + port : toUri.getHost();
        } catch (Exception e) {
            log.error("[extractToDomain][提取To头域名失败]", e);
            return null;
        }
    }

    /**
     * 提取主叫用户名
     */
    public static String extractFromUser(Request request) {
        try {
            FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
            if (fromHeader == null) {
                return null;
            }
            javax.sip.address.SipURI fromUri = (javax.sip.address.SipURI) fromHeader.getAddress().getURI();
            return fromUri.getUser();
        } catch (Exception e) {
            log.error("[extractFromUser][提取From头用户名失败]", e);
            return null;
        }
    }

    /**
     * 提取主叫域名
     */
    public static String extractFromDomain(Request request) {
        try {
            FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
            if (fromHeader == null) {
                return null;
            }
            javax.sip.address.SipURI fromUri = (javax.sip.address.SipURI) fromHeader.getAddress().getURI();
            // 与 getFromDomain 保持一致：SipURI.getPort() 在 URI 无显式端口时返回 -1，
            // 此时仅返回 host，避免拼出 "domain:-1" 的非法域名
            int port = fromUri.getPort();
            return port > 0 ? fromUri.getHost() + ":" + port : fromUri.getHost();
        } catch (Exception e) {
            log.error("[extractFromDomain][提取From头域名失败]", e);
            return null;
        }
    }

    /**
     * 验证To头信息
     */
    public static boolean validateToHeader(String toUser, String toDomain) {
        if (toUser == null || toDomain == null) {
            log.error("[validateToHeader][To头信息不完整] toUser={}, toDomain={}", toUser, toDomain);
            return false;
        }
        return true;
    }

    /**
     * 从SIP消息中提取来源IP地址
     * 优先从Via头的received参数获取，如果没有则使用Via头的host
     *
     * @param message SIP消息
     * @return 来源IP地址，如果获取失败返回null
     */
    public static String getSourceIpFromMessage(Message message) {
        verifyNullMessage(message);
        try {
            ViaHeader viaHeader = (ViaHeader) message.getHeader(ViaHeader.NAME);
            if (viaHeader == null) {
                log.warn("[getSourceIpFromResponse][未找到Via头]");
                return null;
            }

            String received = viaHeader.getReceived();
            if (received != null && !received.isEmpty()) {
                log.debug("[getSourceIpFromResponse][从received参数获取IP] received={}", received);
                return received;
            }

            String host = viaHeader.getHost();
            log.debug("[getSourceIpFromResponse][从Via头host获取IP] host={}", host);
            return host;
        } catch (Exception e) {
            log.error("[getSourceIpFromResponse][提取来源IP失败]", e);
            return null;
        }
    }

    /**
     * 从SIP消息的Via头中提取来源端口
     * <p>
     * 优先级:
     * <ol>
     *   <li>Via 头的 rport 参数: 对端返回响应时,FS 等服务会回写真实接收端口(Response 场景下对应请求方的对外端口,
     *       但在 Request 场景下对应发送方对外端口;对于 UDP Response,rport 记录的是请求发送方的源端口)</li>
     *   <li>Via 头的 sent-by port: Via host:port 中的显式端口(如 Via: SIP/2.0/UDP 1.2.3.4:5060)</li>
     *   <li>默认 5060: 无法获取时返回标准 SIP 端口</li>
     * </ol>
     * <p>
     * 设计说明: 在 Request 场景中通过 (sourceIp, sourcePort) 与已注册的 FS 节点/网关列表精确匹配,
     * 避免仅靠 User-Agent 误判(第三方网关也可能是 FreeSWITCH 部署,UA 相同)。
     * Response 场景下此方法仅做兜底,响应来源识别优先依赖 SessionInfo 上下文。
     *
     * @param message SIP消息
     * @return 来源端口号,无法获取时返回默认 5060
     */
    public static int getSourcePortFromMessage(Message message) {
        verifyNullMessage(message);
        try {
            ViaHeader viaHeader = (ViaHeader) message.getHeader(ViaHeader.NAME);
            if (viaHeader == null) {
                log.warn("[getSourcePortFromMessage][未找到Via头,返回默认5060]");
                return 5060;
            }
            // rport 参数: Response 场景由 FS 回写请求方的真实源端口
            int rport = viaHeader.getRPort();
            if (rport > 0) {
                log.debug("[getSourcePortFromMessage][从rport参数获取端口] rport={}", rport);
                return rport;
            }
            // sent-by port: Via host:port
            int port = viaHeader.getPort();
            if (port > 0) {
                log.debug("[getSourcePortFromMessage][从Via头sent-by port获取] port={}", port);
                return port;
            }
            log.debug("[getSourcePortFromMessage][Via头无端口,返回默认5060]");
            return 5060;
        } catch (Exception e) {
            log.error("[getSourcePortFromMessage][提取来源端口失败]", e);
            return 5060;
        }
    }

    /**
     * 从SIP消息的Via头中提取transport参数
     * Via头格式: SIP/2.0/UDP 或 SIP/2.0/TCP
     *
     * @param message SIP消息
     * @return transport参数值（udp/tcp），默认返回udp
     */
    public static String getTransportFromVia(Message message) {
        verifyNullMessage(message);
        try {
            ViaHeader viaHeader = (ViaHeader) message.getHeader(ViaHeader.NAME);
            if (viaHeader == null) {
                log.warn("[getTransportFromVia][未找到Via头]");
                return SipProxyConstants.TRANSPORT_UDP;
            }

            String transport = viaHeader.getTransport();
            if (transport != null && !transport.isEmpty()) {
                String transportLower = transport.toLowerCase();
                log.debug("[getTransportFromVia][从Via头获取transport] transport={}", transportLower);
                return transportLower;
            }

            log.debug("[getTransportFromVia][Via头无transport参数，默认返回udp]");
            return SipProxyConstants.TRANSPORT_UDP;
        } catch (Exception e) {
            log.error("[getTransportFromVia][提取transport失败]", e);
            return SipProxyConstants.TRANSPORT_UDP;
        }
    }

    /**
     * 判断SIP消息的Via头是否指定TCP传输
     *
     * @param message SIP消息
     * @return true表示使用TCP，false表示使用UDP
     */
    public static boolean isTcpTransport(Message message) {
        return SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(getTransportFromVia(message));
    }

    /**
     * 提取Contact头信息
     */
    public static SipURI extractContact(Message message) {
        try {
            ContactHeader contactHeader = (ContactHeader) message.getHeader(ContactHeader.NAME);
            if (contactHeader == null) {
                return null;
            }
            return (SipURI) contactHeader.getAddress().getURI();
        } catch (Exception e) {
            log.error("[extractContact][提取Contact头失败]", e);
            return null;
        }
    }
}
