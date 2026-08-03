<template>
  <div class="soft-phone-wrapper">
    <el-row :gutter="16" class="main-row">
      <!-- 左侧：配置区 + 功能区（约 40%） -->
      <el-col :span="10">
        <!-- 配置区 -->
        <el-card class="config-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>配置</span>
            </div>
          </template>
          <el-form label-position="top" size="small">
            <el-form-item label="WS 接口地址">
              <el-input
                v-model="wsUrl"
                data-testid="ws-url"
                placeholder="ws://host:port/sipproxy/ws?token=xxx"
                clearable
              />
            </el-form-item>
            <el-form-item label="SIP 域名">
              <el-input
                v-model="sipDomain"
                data-testid="sip-domain"
                placeholder="sipproxy.example"
                clearable
              />
            </el-form-item>
            <el-form-item label="坐席分机号">
              <el-input
                v-model="extension"
                data-testid="extension"
                placeholder="1001"
                clearable
              />
            </el-form-item>
            <el-form-item label="坐席密码">
              <el-input
                v-model="password"
                data-testid="password"
                type="password"
                placeholder="123456"
                show-password
                clearable
              />
            </el-form-item>
            <el-form-item label="STUN 服务器地址">
              <el-input
                v-model="stunServer"
                data-testid="stun-server"
                placeholder="可为空，如 39.107.224.184:3478"
                clearable
              />
            </el-form-item>
            <el-form-item label="TURN 服务器地址">
              <el-input
                v-model="turnServer"
                data-testid="turn-server"
                placeholder="可为空，如 39.107.224.184:3478"
                clearable
              />
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 功能区（软电话） -->
        <el-card class="phone-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>软电话</span>
            </div>
          </template>

          <div class="soft-phone-bar">
            <div class="bar-section bar-auth">
              <el-button
                :type="isOnline ? 'primary' : 'default'"
                circle
                size="default"
                data-testid="login-btn"
                @click="toggleLogin"
                :loading="isLoginLoading"
              >
                <el-icon><Avatar /></el-icon>
              </el-button>
            </div>

            <div class="bar-divider"></div>

            <div class="bar-section bar-status">
              <span v-if="!isOnline" class="offline-tag" data-testid="status-offline">
                <span class="offline-dot"></span>
                离线
              </span>

              <div v-else class="switch-wrap">
                <el-switch
                  :model-value="switchValue"
                  data-testid="status-online"
                  @change="handleSwitchChange"
                  :disabled="isInCall"
                  style="--el-switch-on-color: #67C23A; --el-switch-off-color: #E6A23C"
                />
                <span :class="['switch-label', switchValue ? 'switch-label--ready' : 'switch-label--busy']">
                  {{ switchValue ? '就绪' : '忙碌' }}
                </span>
              </div>
            </div>

            <template v-if="isOnline">
              <div class="bar-divider"></div>
              <div class="bar-section bar-dial">
                <el-button
                  type="success"
                  circle
                  size="default"
                  data-testid="dial-btn"
                  :disabled="isInCall"
                  @click="showDialPopup = !showDialPopup"
                >
                  <el-icon><PhoneFilled /></el-icon>
                </el-button>
              </div>
            </template>
          </div>

          <!-- 拨号弹窗 (非通话中) -->
          <div v-if="showDialPopup && !isInCall" class="popover">
            <div class="popover-header">
              <span class="popover-title">拨号</span>
              <button class="popover-close" @click="showDialPopup = false">
                <el-icon><Close /></el-icon>
              </button>
            </div>
            <div class="popover-body">
              <!-- 呼叫类型单选：外呼(0,默认) / 内部呼叫(1) -->
              <el-radio-group v-model="callType" size="small" style="width: 100%; margin-bottom: 8px">
                <el-radio-button :value="0">外呼</el-radio-button>
                <el-radio-button :value="1">内部呼叫</el-radio-button>
              </el-radio-group>
              <!-- 外呼时显示网关选择（非必填）；内部呼叫时隐藏 -->
              <el-select
                v-if="callType === 0"
                v-model="gatewayId"
                placeholder="选择网关(非必填,可直接拨打)"
                clearable
                size="default"
                style="width: 100%; margin-bottom: 8px"
              >
                <el-option
                  v-for="gw in gatewayList"
                  :key="gw.id"
                  :label="gw.name"
                  :value="gw.id"
                />
              </el-select>
              <input
                class="dial-input"
                placeholder="输入号码"
                :value="phoneNumber"
                @input="handlePhoneNumberChange(($event.target as HTMLInputElement).value)"
              />
              <div class="dialpad-grid">
                <button
                  v-for="key in ['1','2','3','4','5','6','7','8','9','*','0','#']"
                  :key="key"
                  class="dialpad-key"
                  @click="handleDialInput(key)"
                >
                  {{ key }}
                </button>
              </div>
              <div class="dialpad-actions">
                <button class="dialpad-act dialpad-act--default" @click="handleDelete">
                  <el-icon><Delete /></el-icon>
                  回退
                </button>
                <button
                  class="dialpad-act dialpad-act--primary"
                  data-testid="dial-submit"
                  @click="makeCall(); showDialPopup = false"
                >
                  <el-icon><PhoneFilled /></el-icon>
                  拨打
                </button>
              </div>
            </div>
          </div>

          <!-- 通话中弹窗 -->
          <div v-if="isInCall" class="popover popover--incall">
            <!-- 多通话列表 (有多个会话时显示) -->
            <div v-if="sessionList.length > 1" class="session-list">
              <div class="session-list-header">通话列表</div>
              <div
                v-for="item in sessionList"
                :key="item.callId"
                :class="['session-item', { 'session-item--active': item.callId === activeCallId }]"
                @click="switchActiveSession(item.callId)"
              >
                <span class="session-item-number">{{ item.number }}</span>
                <span :class="['session-item-status', `session-item-status--${item.status}`]">
                  {{ sessionStatusText(item.status) }}
                </span>
              </div>
            </div>

            <!-- 当前焦点会话信息 -->
            <div class="active-call">
              <div class="active-call-timer">{{ statusDuration }}</div>
              <div class="active-call-number">{{ activeSessionNumber }}</div>

              <!-- 通话控制按钮: 保持/静音/转接 -->
              <div class="call-controls">
                <button
                  :class="['call-ctrl-btn', isHeld ? 'call-ctrl-btn--active' : '']"
                  :disabled="!canHold"
                  @click="toggleHold"
                >
                  {{ isHeld ? '恢复' : '保持' }}
                </button>
                <button
                  :class="['call-ctrl-btn', isMuted ? 'call-ctrl-btn--active' : '']"
                  :disabled="!canMute"
                  @click="toggleMute"
                >
                  {{ isMuted ? '取消静音' : '静音' }}
                </button>
                <button
                  class="call-ctrl-btn call-ctrl-btn--transfer"
                  :disabled="!canTransfer"
                  @click="showTransferPopup = !showTransferPopup"
                >
                  转接
                </button>
              </div>

              <!-- 转接弹窗(通话中) -->
              <div v-if="showTransferPopup && isInCall" class="transfer-panel">
                <div class="popover-header">
                  <span class="popover-title">咨询转接</span>
                  <button class="popover-close" @click="showTransferPopup = false">
                    <el-icon><Close /></el-icon>
                  </button>
                </div>
                <div class="popover-body">
                  <!-- 转接类型: 咨询转接(默认) / 盲转 -->
                  <el-radio-group v-model="transferType" size="small" style="width: 100%; margin-bottom: 8px">
                    <el-radio-button value="attended">咨询转接</el-radio-button>
                    <el-radio-button value="blind">盲转</el-radio-button>
                  </el-radio-group>
                  <!-- 出局网关选择(非必填,未选则走号码路由) -->
                  <el-select
                    v-model="transferGatewayId"
                    placeholder="选择网关(非必填)"
                    clearable
                    size="default"
                    style="width: 100%; margin-bottom: 8px"
                  >
                    <el-option
                      v-for="gw in gatewayList"
                      :key="gw.id"
                      :label="gw.name"
                      :value="gw.id"
                    />
                  </el-select>
                  <input
                    class="dial-input"
                    placeholder="输入转接目标号码"
                    :value="transferTarget"
                    @input="transferTarget = ($event.target as HTMLInputElement).value"
                    @keyup.enter="handleTransfer"
                  />
                  <div class="dialpad-actions">
                    <button class="dialpad-act dialpad-act--default" @click="showTransferPopup = false">
                      取消
                    </button>
                    <button
                      class="dialpad-act dialpad-act--primary"
                      :disabled="!transferTarget.trim()"
                      @click="handleTransfer"
                    >
                      确认转接
                    </button>
                  </div>
                </div>
              </div>

              <button class="hangup-full" @click="handleHangup">
                <el-icon><Close /></el-icon>
                挂断
              </button>

              <!-- DTMF拨号盘 (通话中发送DTMF信号) -->
              <div class="dialpad-grid dialpad-grid--incall">
                <button
                  v-for="key in ['1','2','3','4','5','6','7','8','9','*','0','#']"
                  :key="key"
                  class="dialpad-key dialpad-key--incall"
                  @click="sendDTMF(key)"
                >
                  {{ key }}
                </button>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：上边操作日志面板 + 下边 WS 消息面板（约 60%） -->
      <el-col :span="14">
        <!-- 上：操作日志面板 -->
        <el-card class="panel-card panel-card--log" shadow="never">
          <template #header>
            <div class="panel-toolbar">
              <span class="panel-toolbar-title">操作日志（{{ logs.length }}）</span>
              <el-button size="small" data-testid="clear-log" @click="clearLogs">清空</el-button>
            </div>
          </template>
          <div data-testid="log-panel" ref="logScrollRef" class="panel-content log-panel">
            <div v-if="logs.length === 0" class="panel-empty">暂无日志</div>
            <div v-for="(log, idx) in logs" :key="idx" class="log-item">
              <span class="log-time">{{ formatTime(log.time) }}</span>
              <span class="log-content">{{ log.content }}</span>
            </div>
          </div>
        </el-card>

        <!-- 下：WS 消息面板 -->
        <el-card class="panel-card panel-card--ws" shadow="never">
          <template #header>
            <div class="panel-toolbar">
              <span class="panel-toolbar-title">WS 消息（{{ wsMessages.length }}）</span>
              <div class="panel-toolbar-legend">
                <span class="legend-item"><i class="legend-dot legend-dot--send"></i>发送</span>
                <span class="legend-item"><i class="legend-dot legend-dot--recv"></i>接收</span>
              </div>
              <el-button size="small" data-testid="clear-ws" @click="clearWsMessages">清空</el-button>
            </div>
          </template>
          <div data-testid="ws-panel" ref="wsScrollRef" class="panel-content ws-panel">
            <div v-if="wsMessages.length === 0" class="panel-empty">暂无 WS 消息</div>
            <div
              v-for="(msg, idx) in wsMessages"
              :key="idx"
              :class="['ws-item', msg.direction === 'send' ? 'ws-item--send' : 'ws-item--recv']"
            >
              <div class="ws-item-header">
                <el-tag
                  :type="msg.direction === 'send' ? 'success' : 'primary'"
                  size="small"
                  effect="light"
                >
                  {{ msg.direction === 'send' ? '发送' : '接收' }}
                </el-tag>
                <span class="ws-time">{{ formatTime(msg.time) }}</span>
              </div>
              <div class="ws-summary">{{ msg.summary }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 来电弹窗 -->
    <Teleport to="body">
      <div v-if="incomingCallFlag" class="incoming-overlay"></div>
      <div v-if="incomingCallFlag" class="incoming-dialog">
        <div class="incoming-header">
          <div class="incoming-avatar">
            <el-icon :size="24"><PhoneFilled /></el-icon>
          </div>
          <div class="incoming-title">来电</div>
          <div class="incoming-number">{{ incomingCallNumber }}</div>
        </div>
        <div class="incoming-actions">
          <button class="incoming-btn incoming-btn--reject" @click="handleRejectIncoming">
            <el-icon><Close /></el-icon>
            挂断
          </button>
          <button class="incoming-btn incoming-btn--accept" @click="handleAnswer">
            <el-icon><PhoneFilled /></el-icon>
            接听
          </button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import JsSIP from 'jssip'
import { ElMessage } from 'element-plus'
import { Avatar, PhoneFilled, Delete, Close } from '@element-plus/icons-vue'

defineOptions({ name: 'SoftPhone' })

// ==================== 配置区数据 ====================

/** WS 接口地址（含 token 参数） */
const wsUrl = ref<string>('ws://localhost:8081/sipproxy/ws?token=test')
/** SIP 域名 */
const sipDomain = ref<string>('sipproxy.example')
/** 坐席分机号 */
const extension = ref<string>('1001')
/** 坐席密码 */
const password = ref<string>('123456')
/** STUN 服务器地址（可空） */
const stunServer = ref<string>('')
/** TURN 服务器地址（可空） */
const turnServer = ref<string>('')

// ==================== 软电话状态 ====================

/** 在线状态码：1-离线，2-在线 */
const loginValue = ref<string>('1')
/** 坐席业务状态码：1-就绪，2-忙碌，4-离线，5-通话中，6-振铃中，7-话后 */
const agentStatusValue = ref<string>('4')
const isLoginLoading = ref<boolean>(false)
const statusDuration = ref<string>('00:00:00')
const callTimer = ref<any>(null)
const phoneNumber = ref<string>('')
const incomingCallFlag = ref<boolean>(false)
const incomingCallNumber = ref<string>('')

/** 网关列表（示例项目无后端 API，保持为空数组，下拉框可留空） */
const gatewayList = ref<any[]>([])
const gatewayId = ref<any>(null)
/** 呼叫类型：0-外呼（默认），1-内部呼叫；外呼显示网关下拉框，内部呼叫隐藏 */
const callType = ref<number>(0)
const showDialPopup = ref(false)

/** 转接弹窗显示状态 */
const showTransferPopup = ref(false)
/** 转接目标号码 */
const transferTarget = ref<string>('')
/** 转接类型: attended-咨询转接(默认), blind-盲转 */
const transferType = ref<string>('attended')
/** 转接出局网关ID(非必填,未选则走号码路由) */
const transferGatewayId = ref<any>(null)

const isOnline = computed(() => loginValue.value === '2')
const switchValue = computed(() => agentStatusValue.value === '1')

// ==================== 多会话管理 ====================

/** 会话信息接口，存储每个通话的完整状态 */
interface SessionInfo {
  session: any           // JsSIP RTCSession 对象
  number: string         // 对方号码
  status: 'ringing' | 'active' | 'held'  // 会话状态
  isMuted: boolean       // 静音状态
  isHeld: boolean        // 保持状态
  direction: 'incoming' | 'outgoing'  // 呼入/呼出
  startTime: Date | null // 通话确认时间，用于计时
  stream: MediaStream | null  // 远端音频流
  iceController: IceGatheringController  // ICE收集优化控制器
}

/** 多会话Map: callId -> SessionInfo，支持多路通话 */
const sessions = ref(new Map<string, SessionInfo>())

/** 当前操作焦点的会话ID */
const activeCallId = ref<string | null>(null)

/** 来电弹窗对应的会话ID，用于区分来电拒绝和活跃会话挂断 */
const incomingCallId = ref<string | null>(null)

/** 向后兼容: 返回当前焦点会话的JsSIP Session对象 */
const currentSession = computed(() => {
  if (!activeCallId.value) return null
  return sessions.value.get(activeCallId.value)?.session ?? null
})

/** 当前焦点会话的SessionInfo */
const currentSessionInfo = computed(() => {
  if (!activeCallId.value) return null
  return sessions.value.get(activeCallId.value) ?? null
})

/** 是否在通话中: 有活跃会话即为通话中 */
const isInCall = computed(() => sessions.value.size > 0)

/** 当前焦点会话的对方号码 */
const activeSessionNumber = computed(() => currentSessionInfo.value?.number ?? '')

/** 当前焦点会话是否保持 */
const isHeld = computed(() => currentSessionInfo.value?.isHeld ?? false)

/** 当前焦点会话是否静音 */
const isMuted = computed(() => currentSessionInfo.value?.isMuted ?? false)

/** 是否可以操作保持: 通话已确认(非振铃)才可保持 */
const canHold = computed(() => {
  const info = currentSessionInfo.value
  return info != null && (info.status === 'active' || info.status === 'held')
})

/** 是否可以操作静音: 通话已确认(非振铃)才可静音 */
const canMute = computed(() => {
  const info = currentSessionInfo.value
  return info != null && (info.status === 'active' || info.status === 'held')
})

/** 是否可以操作转接: 通话已确认(非振铃)才可转接 */
const canTransfer = computed(() => {
  const info = currentSessionInfo.value
  return info != null && (info.status === 'active' || info.status === 'held')
})

/** 会话列表，用于UI渲染多通话切换 */
const sessionList = computed(() => {
  const list: Array<{ callId: string; number: string; status: string }> = []
  sessions.value.forEach((info, callId) => {
    list.push({ callId, number: info.number, status: info.status })
  })
  return list
})

/** 会话状态文本映射 */
const sessionStatusText = (status: string): string => {
  const map: Record<string, string> = {
    ringing: '振铃中',
    active: '通话中',
    held: '保持中'
  }
  return map[status] || status
}

// ==================== ICE收集优化控制器 ====================

interface IceGatheringController {
  timeout: any
  readyTriggered: boolean
}

const createIceGatheringController = (): IceGatheringController => ({
  timeout: null,
  readyTriggered: false
})

/**
 * ICE 候选收集优化处理器
 * 设计意图：收到 srflx/relay 类型候选后延迟触发 ready，避免等待全部候选收集完成
 */
const handleIceCandidate = (
  data: { candidate: any; ready: () => void },
  controller: IceGatheringController,
  context: string = '',
  timeoutMs: number = 2000
) => {
  const { candidate, ready } = data

  if (candidate) {
    console.log(`${context} ICE candidate:`, candidate.candidate)

    if (!controller.timeout && !controller.readyTriggered) {
      const candidateStr = candidate.candidate as string
      if (candidateStr.includes('typ relay') || candidateStr.includes('typ srflx')) {
        controller.timeout = setTimeout(() => {
          if (!controller.readyTriggered) {
            controller.readyTriggered = true
            ready()
          }
        }, timeoutMs)
      }
    }
  } else {
    if (controller.timeout) {
      clearTimeout(controller.timeout)
      controller.timeout = null
    }
    controller.readyTriggered = false
  }
}

const cleanupIceController = (controller: IceGatheringController) => {
  if (controller.timeout) {
    clearTimeout(controller.timeout)
    controller.timeout = null
  }
  controller.readyTriggered = false
}

// ==================== 操作日志面板 ====================

interface LogEntry {
  time: Date
  content: string
}

/** 操作日志列表 */
const logs = ref<LogEntry[]>([])
/** 日志面板滚动容器引用 */
const logScrollRef = ref<HTMLElement | null>(null)

/**
 * 追加一条操作日志并自动滚动到底部
 * @param message 日志内容
 */
const addLog = (message: string) => {
  logs.value.push({ time: new Date(), content: message })
  nextTick(() => {
    const panel = logScrollRef.value
    if (panel) panel.scrollTop = panel.scrollHeight
  })
}

const clearLogs = () => {
  logs.value = []
}

// ==================== WS 消息面板 ====================

interface WsMessage {
  time: Date
  direction: 'send' | 'recv'
  raw: string
  summary: string
}

/** WS 消息列表 */
const wsMessages = ref<WsMessage[]>([])
/** WS 消息面板滚动容器引用 */
const wsScrollRef = ref<HTMLElement | null>(null)

/**
 * 从 SIP 原文中提取指定头域的值
 * @param lines SIP 消息按行拆分后的数组
 * @param name 头域名（不区分大小写）
 * @returns 头域值，未找到返回空字符串
 */
const getSipHeader = (lines: string[], name: string): string => {
  const prefix = name.toLowerCase() + ':'
  for (const line of lines) {
    if (line.toLowerCase().startsWith(prefix)) {
      return line.substring(name.length + 1).trim()
    }
  }
  return ''
}

/**
 * 解析 SIP 原文，提取关键信息（方法/状态码、Call-ID、From/To、CSeq）
 * @param raw SIP 原文字符串
 * @returns 解析后的摘要字符串
 */
const parseSipMessage = (raw: string): string => {
  const lines = raw.split('\r\n')
  if (lines.length === 0) return raw
  const firstLine = lines[0]
  let methodOrStatus = ''
  if (firstLine.startsWith('SIP/')) {
    // 响应: SIP/2.0 200 OK
    methodOrStatus = firstLine
  } else {
    // 请求: METHOD sip:... SIP/2.0
    const parts = firstLine.split(' ')
    methodOrStatus = parts.length >= 2 ? `${parts[0]} ${parts[1]}` : firstLine
  }
  const callId = getSipHeader(lines, 'Call-ID')
  const from = getSipHeader(lines, 'From')
  const to = getSipHeader(lines, 'To')
  const cseq = getSipHeader(lines, 'CSeq')
  const parts = [methodOrStatus]
  if (callId) parts.push(`Call-ID: ${callId}`)
  if (from) parts.push(`From: ${from}`)
  if (to) parts.push(`To: ${to}`)
  if (cseq) parts.push(`CSeq: ${cseq}`)
  return parts.join(' | ')
}

/**
 * 追加一条 WS 消息并自动滚动到底部
 * @param direction 消息方向：send-发送，recv-接收
 * @param raw SIP 原文
 */
const addWsMessage = (direction: 'send' | 'recv', raw: string) => {
  if (typeof raw !== 'string') return
  const summary = parseSipMessage(raw)
  wsMessages.value.push({ time: new Date(), direction, raw, summary })
  nextTick(() => {
    const panel = wsScrollRef.value
    if (panel) panel.scrollTop = panel.scrollHeight
  })
}

const clearWsMessages = () => {
  wsMessages.value = []
}

/** 格式化时间为 HH:MM:SS.mmm */
const formatTime = (date: Date): string => {
  const h = String(date.getHours()).padStart(2, '0')
  const m = String(date.getMinutes()).padStart(2, '0')
  const s = String(date.getSeconds()).padStart(2, '0')
  const ms = String(date.getMilliseconds()).padStart(3, '0')
  return `${h}:${m}:${s}.${ms}`
}

// ==================== WebSocket 拦截 ====================

/**
 * 标志位：标识 window.WebSocket 是否已被拦截包装
 * 设计意图：避免多次签入导致嵌套包装，只包装最原始的 WebSocket 一次
 */
let isWebSocketIntercepted = false
/** 保存原始 WebSocket 构造器，用于恢复（当前保持拦截，不恢复） */
let OriginalWebSocket: typeof WebSocket | null = null

/**
 * 拦截全局 WebSocket，捕获 JsSIP 通过 WebSocket 收发的 SIP 原文
 * 需求：在创建 JsSIP UA 之前调用，确保 UA 内部创建的 WebSocket 实例使用被拦截的构造器
 * 实现方式：继承原始 WebSocket，重写 send 方法捕获发送消息，监听 message 事件捕获接收消息
 */
const installWebSocketInterceptor = () => {
  if (isWebSocketIntercepted) return
  OriginalWebSocket = window.WebSocket
  const Orig = OriginalWebSocket as any

  /**
   * 拦截版 WebSocket：在原始构造器基础上增加收发消息捕获
   */
  class InterceptedWebSocket extends Orig {
    constructor(url: string | URL, protocols?: string | string[]) {
      super(url, protocols)
      // 拦截接收的消息
      this.addEventListener('message', (event: MessageEvent) => {
        addWsMessage('recv', event.data)
      })
    }
    send(data: string) {
      // 拦截发送的消息
      addWsMessage('send', data)
      return super.send(data)
    }
  }

  window.WebSocket = InterceptedWebSocket as any
  isWebSocketIntercepted = true
  addLog('已启用 WebSocket 消息拦截')
}

// ==================== 其他状态和工具函数 ====================

let ua: JsSIP.UA | undefined
let audioView = new Audio()
let keepAliveTimer: any = null
const KEEP_ALIVE_INTERVAL = 300000

watch(isInCall, (val) => {
  if (val) {
    showDialPopup.value = false
  }
})

/**
 * 监听呼叫类型切换：
 * - 切到内部呼叫时清空已选网关（内部呼叫不走网关）
 * - 切回外呼时不自动选择网关（由用户手动选择或留空）
 */
watch(callType, (val) => {
  if (val === 1) {
    gatewayId.value = null
  }
})

/**
 * 切换签入/签出状态
 *
 * 需求: 点击头像按钮触发 SIP REGISTER 注册/取消注册流程
 * 预期结果:
 *   - 签入: SIP REGISTER 成功后 UI 切换到在线状态
 *   - 签出: SIP unregister 后 UI 切换到离线状态
 * 处理逻辑:
 *   1. 签入时不立即设置 loginValue='2', 而在 registered 事件回调中设置
 *      设计意图: UI 状态必须反映真实 REGISTER 状态, 避免 UI 显示在线但实际未注册
 *   2. 签入失败(STUN 超时/WebSocket 错误/REGISTER 403)时 isLoginLoading 恢复 false, UI 保持离线
 *   3. 签出时立即设置离线, 然后异步 unregister
 */
const toggleLogin = async () => {
  if (loginValue.value === '1') {
    // 签入前校验必填配置
    if (!wsUrl.value.trim()) {
      ElMessage.warning('请填写 WS 接口地址')
      return
    }
    if (!sipDomain.value.trim()) {
      ElMessage.warning('请填写 SIP 域名')
      return
    }
    if (!extension.value.trim()) {
      ElMessage.warning('请填写坐席分机号')
      return
    }
    isLoginLoading.value = true
    computeStatusDuration()
    try {
      await initSipClient()
    } catch (e) {
      console.error('SIP 客户端初始化失败:', e)
      ElMessage.error('SIP 签入失败, 请检查配置或网络')
      addLog(`SIP 签入失败: ${e instanceof Error ? e.message : String(e)}`)
    } finally {
      isLoginLoading.value = false
    }
  } else if (loginValue.value === '2') {
    loginValue.value = '1'
    isLoginLoading.value = true
    clearCallTimer()
    agentStatusValue.value = '4'
    if (ua) {
      try {
        ua.unregister()
      } catch (e) {
        console.error('取消注册异常:', e)
      }
      addLog('签出成功')
    }
    unregisterSipClient()
    stopKeepAlive()
    isLoginLoading.value = false
  }
}

const clearCallTimer = () => {
  if (callTimer.value) {
    clearInterval(callTimer.value)
    statusDuration.value = '00:00:00'
  }
}

/**
 * 计算当前焦点会话的通话时长
 * 根据活跃会话的startTime持续更新显示
 */
const computeStatusDuration = () => {
  if (callTimer.value) {
    statusDuration.value = '00:00:00'
    clearInterval(callTimer.value)
  }
  const info = currentSessionInfo.value
  if (!info?.startTime) return

  const startTime = info.startTime
  callTimer.value = setInterval(() => {
    const now = new Date()
    const diff = now.getTime() - startTime.getTime()
    const hours = Math.floor(diff / 3600000)
      .toString()
      .padStart(2, '0')
    const minutes = Math.floor((diff % 3600000) / 60000)
      .toString()
      .padStart(2, '0')
    const seconds = Math.floor((diff % 60000) / 1000)
      .toString()
      .padStart(2, '0')
    statusDuration.value = `${hours}:${minutes}:${seconds}`
  }, 1000)
}

const handleDialInput = (digit: string) => {
  phoneNumber.value += digit
}

const handleDelete = () => {
  phoneNumber.value = phoneNumber.value.slice(0, -1)
}

/**
 * 处理号码输入框的输入事件
 * 设计意图：过滤非法字符（仅允许数字、*、#），并通过对比长度判断是新增还是删除
 */
const handlePhoneNumberChange = (value: string) => {
  const oldVal = phoneNumber.value
  const sanitizedInput = value.replace(/[^0-9*#]/g, '')
  if (sanitizedInput === '') {
    phoneNumber.value = ''
    return
  }
  if (sanitizedInput.length > oldVal.length) {
    const addedChar = sanitizedInput.slice(-1)
    handleDialInput(addedChar)
  } else if (sanitizedInput.length < oldVal.length) {
    handleDelete()
  }
}

const handleSwitchChange = (val: boolean) => {
  agentStatusValue.value = val ? '1' : '2'
}

// ==================== 会话管理辅助方法 ====================

/**
 * 从sessions Map中移除会话，并处理后续状态切换
 * 需求: 会话结束后清理Map，若为活跃会话则自动切换到下一个
 */
const removeSession = (callId: string | null) => {
  if (!callId) return
  const info = sessions.value.get(callId)
  if (info) {
    cleanupIceController(info.iceController)
  }
  sessions.value.delete(callId)

  // 如果删除的是活跃会话，切换到另一个会话
  if (activeCallId.value === callId) {
    if (sessions.value.size > 0) {
      const nextCallId = sessions.value.keys().next().value
      switchActiveSession(nextCallId)
    } else {
      activeCallId.value = null
      agentStatusValue.value = '7'
      clearCallTimer()
    }
  }

  // 如果删除的是来电会话，关闭来电弹窗
  if (incomingCallId.value === callId) {
    incomingCallFlag.value = false
    incomingCallId.value = null
    console.log('[振铃音] 停止播放（来电会话结束）')
  }

  // 根据剩余会话更新坐席状态
  updateAgentStatusBySessions()
}

/**
 * 根据当前所有会话状态更新坐席状态值
 * 需求: 有通话中会话为'5'，有振铃中会话为'6'，无会话不修改(由调用方处理)
 */
const updateAgentStatusBySessions = () => {
  if (sessions.value.size === 0) return
  let hasActive = false
  let hasRinging = false
  sessions.value.forEach(info => {
    if (info.status === 'active' || info.status === 'held') hasActive = true
    if (info.status === 'ringing') hasRinging = true
  })
  if (hasActive) {
    agentStatusValue.value = '5'
  } else if (hasRinging) {
    agentStatusValue.value = '6'
  }
}

/**
 * 切换当前操作焦点的会话
 * 需求: 点击会话列表项切换焦点，更新音频播放和计时器
 */
const switchActiveSession = (callId: string) => {
  activeCallId.value = callId
  const info = sessions.value.get(callId)
  // 切换音频流到新焦点会话
  if (info?.stream) {
    audioView.srcObject = info.stream
    audioView.play().catch(() => {})
    audioView.volume = 1
  } else {
    audioView.srcObject = null
  }
  // 重新计算当前会话的通话时长
  computeStatusDuration()
}

/**
 * 为会话注册通用的生命周期事件处理器
 * 需求: 统一处理confirmed/failed/ended/hold/unhold/muted/unmuted事件，更新SessionInfo
 */
const registerSessionEvents = (callId: string, direction: 'incoming' | 'outgoing') => {
  const info = sessions.value.get(callId)
  if (!info) return
  const session = info.session

  // 通话确认: 更新状态为active，记录开始时间
  session.on('confirmed', (e: any) => {
    console.log(`${direction === 'incoming' ? '呼入' : '拨打'}会话确认: 通话中`, e)
    const currentInfo = sessions.value.get(callId)
    if (currentInfo) {
      currentInfo.status = 'active'
      currentInfo.startTime = new Date()
    }
    agentStatusValue.value = '5'
    cleanupIceController(info.iceController)
    // 如果是当前焦点会话，启动计时器
    if (activeCallId.value === callId) {
      computeStatusDuration()
    }
  })

  // 会话失败: 清理会话，外呼时显示错误提示
  session.on('failed', (e: any) => {
    console.error(`${direction === 'incoming' ? '呼入' : '拨打'}会话失败:`, e)
    if (direction === 'outgoing') {
      ElMessage.error(`呼叫失败: ${e.cause || '未知原因'}`)
    }
    removeSession(callId)
  })

  // 会话被拒绝: 仅外呼场景
  session.on('rejected', (e: any) => {
    console.error('拨打呼叫被拒绝:', e)
    ElMessage.error(`呼叫被拒绝: ${e.cause || '未知原因'}`)
    removeSession(callId)
  })

  // 会话结束: 清理会话
  session.on('ended', (e: any) => {
    console.log(`${direction === 'incoming' ? '呼入' : '拨打'}会话结束`, e)
    removeSession(callId)
  })

  // 保持/恢复事件: 同步SessionInfo中的isHeld状态
  session.on('hold', () => {
    const currentInfo = sessions.value.get(callId)
    if (currentInfo) {
      currentInfo.isHeld = true
      currentInfo.status = 'held'
    }
    updateAgentStatusBySessions()
  })

  session.on('unhold', () => {
    const currentInfo = sessions.value.get(callId)
    if (currentInfo) {
      currentInfo.isHeld = false
      currentInfo.status = 'active'
    }
    updateAgentStatusBySessions()
  })

  // 静音/取消静音事件: 同步SessionInfo中的isMuted状态
  session.on('muted', () => {
    const currentInfo = sessions.value.get(callId)
    if (currentInfo) {
      currentInfo.isMuted = true
    }
  })

  session.on('unmuted', () => {
    const currentInfo = sessions.value.get(callId)
    if (currentInfo) {
      currentInfo.isMuted = false
    }
  })
}

// ==================== ICE 配置构建 ====================

/**
 * 根据配置区动态构建 ICE 服务器列表
 * 需求：STUN/TURN 为空时不配置对应服务器，全为空则返回空数组（不配置 iceServers）
 * @returns RTCIceServer 配置数组
 */
const buildIceServers = (): any[] => {
  const iceServers: any[] = []
  const stun = stunServer.value.trim()
  if (stun) {
    const stunUrl = stun.startsWith('stun:') ? stun : `stun:${stun}`
    iceServers.push({ urls: stunUrl })
  }
  const turn = turnServer.value.trim()
  if (turn) {
    const turnUrl = turn.startsWith('turn:') ? turn : `turn:${turn}`
    iceServers.push({ urls: turnUrl })
  }
  return iceServers
}

// ==================== SIP客户端初始化 ====================

/**
 * 初始化 SIP 客户端
 * 需求：从配置区读取参数构建 JsSIP UA，完成注册
 * 处理逻辑：
 *   1. 在创建 UA 前拦截 WebSocket，捕获收发的 SIP 原文
 *   2. STUN 为空时跳过 STUN 收集，直接用 127.0.0.1 作为 contact IP
 *   3. 构建 UA configuration 并启动
 */
const initSipClient = async () => {
  console.log('初始化SIP客户端')
  addLog('开始初始化 SIP 客户端')

  // 在创建 UA 前拦截 WebSocket，确保 UA 内部创建的 WS 实例使用被拦截的构造器
  installWebSocketInterceptor()

  // 获取公网地址（STUN 为空时返回本地兜底地址）
  const result = await getPublicIPAndPort()
  const publicIP = result.ip
  const publicPort = result.port
  console.log('发现公网地址：', publicIP, publicPort)
  addLog(`公网地址: ${publicIP}:${publicPort}`)

  console.log('开始启动jssip')
  const socket = new JsSIP.WebSocketInterface(wsUrl.value.trim())
  JsSIP.debug.enable('JsSIP:*')
  const uri = `sip:${extension.value.trim()}@${sipDomain.value.trim()}`

  const cleanedIP = publicIP.replace(/^\[|\]$/g, '')
  const isIPv6 = cleanedIP.includes(':')
  const contactIP = isIPv6 ? `[${cleanedIP}]` : cleanedIP

  // 构建 ICE 服务器配置
  const iceServers = buildIceServers()
  const pcConfig = iceServers.length > 0
    ? { iceServers, iceTransportPolicy: 'all' as const }
    : {}

  const configuration = {
    sockets: [socket],
    uri: uri,
    contact_uri: `sip:${extension.value.trim()}@${contactIP}:${publicPort};transport=ws`,
    password: password.value,
    register: true,
    register_expires: 1800,
    connection_recovery_max_interval: 60,
    connection_recovery_min_interval: 5,
    no_answer_timeout: 60,
    pcConfig,
    mediaConstraints: {
      audio: true,
      video: false
    },
    rtcOfferConstraints: {
      offerToReceiveAudio: true,
      offerToReceiveVideo: false
    },
    sessionTimersExpires: 180
  }
  ua = new JsSIP.UA(configuration as any)
  registerSipEvents()
  ua.start()
  addLog(`UA 已启动，URI: ${uri}`)
}

const unregisterSipClient = () => {
  if (!ua) {
    console.warn('SIP客户端未初始化')
    return
  }
  try {
    if (ua.isConnected()) {
      ua.stop()
    }
  } catch (error) {
    console.error('SIP登出失败:', error)
  }
  ua = undefined
}

/**
 * 启动 SIP 心跳保活
 * 设计意图：定期发送 OPTIONS 请求保持注册有效性，失败时提示重新注册
 */
const startKeepAlive = () => {
  if (keepAliveTimer) {
    clearInterval(keepAliveTimer)
  }

  keepAliveTimer = setInterval(() => {
    if (ua && ua.isRegistered()) {
      const target = (ua as any).configuration.uri
      ;(ua as any).sendOptions(target, null, {
        contentType: '',
        eventHandlers: {
          onSuccessResponse: (response: any) => {
            console.log('[SIP心跳] OPTIONS 保活成功')
          },
          onErrorResponse: (response: any) => {
            console.error('[SIP心跳] OPTIONS 保活失败', response)
            ElMessage.warning('SIP心跳保活失败，重新注册，请检查您的网络')
            addLog('SIP心跳保活失败')
          }
        }
      })
    }
  }, KEEP_ALIVE_INTERVAL)
}

const stopKeepAlive = () => {
  if (keepAliveTimer) {
    clearInterval(keepAliveTimer)
    keepAliveTimer = null
    console.log('[SIP心跳] 已停止保活')
  }
}

/**
 * 注册SIP UA事件
 * 需求: 处理注册/取消注册/新会话事件，newRTCSession中将新会话保存到sessions Map
 */
const registerSipEvents = () => {
  if (!ua) return

  ua.on('registered', () => {
    // REGISTER 成功后才设置在线状态,确保 UI 状态与实际注册状态同步
    loginValue.value = '2'
    computeStatusDuration()
    console.log('SIP注册成功')
    addLog('注册成功')
    agentStatusValue.value = '2'
    startKeepAlive()
  })

  ua.on('unregistered', () => {
    clearCallTimer()
    stopKeepAlive()
    console.log('SIP取消注册成功')
    addLog('已取消注册')
  })

  ua.on('registrationFailed', (response: any) => {
    // REGISTER 失败时恢复离线状态,确保 UI 反映真实注册状态
    loginValue.value = '1'
    console.error('SIP注册失败:', JSON.stringify(response))
    const cause = response?.cause || response?.reason_phrase || '未知原因'
    addLog(`注册失败: ${cause}`)
    ElMessage.error('SIP 注册失败, 请检查坐席配置或网络后重试')
  })

  ua.on('newRTCSession', (data: any) => {
    const session = data.session
    const callId = session.id

    // 创建ICE收集控制器
    const iceController = createIceGatheringController()
    session.on('icecandidate', (candidateData: any) => {
      handleIceCandidate(candidateData, iceController, data.originator === 'local' ? '外呼' : '呼入')
    })

    // 获取对方号码
    const remoteNumber = session.remote_identity?.uri?.user || ''

    // 将新会话保存到sessions Map，而非覆盖
    // 使用markRaw包裹JsSIP RTCSession对象,避免Vue响应式系统对其创建Proxy
    sessions.value.set(callId, {
      session: markRaw(session),
      number: remoteNumber,
      status: 'ringing',
      isMuted: false,
      isHeld: false,
      direction: data.originator === 'local' ? 'outgoing' : 'incoming',
      startTime: null,
      stream: null,
      iceController
    })

    // 设置为活跃会话(如果没有活跃会话，或这是外呼会话)
    if (!activeCallId.value || data.originator === 'local') {
      activeCallId.value = callId
    }

    // 注册通用会话事件
    registerSessionEvents(callId, data.originator === 'local' ? 'outgoing' : 'incoming')

    if (data.originator === 'local') {
      // 外呼: 设置远端音频轨道
      session.connection.addEventListener('track', (event: any) => {
        const info = sessions.value.get(callId)
        if (info) {
          info.stream = event.streams[0]
        }
        // 如果是当前焦点会话，立即播放
        if (activeCallId.value === callId) {
          audioView.srcObject = event.streams[0]
          audioView.play()
          audioView.volume = 1
        }
      })

      // 外呼特有事件
      session.on('connecting', (e: any) => {
        console.log('拨打会话连接中...:', e)
        agentStatusValue.value = '6'
      })

      session.on('progress', (e: any) => {
        console.log('拨打会话进度更新: 振铃中:', e)
        agentStatusValue.value = '6'
      })
    }

    if (data.originator === 'remote') {
      // 来电: 显示来电弹窗
      console.log('收到来电，显示对话框')
      addLog(`收到来电: ${remoteNumber}`)
      incomingCallFlag.value = true
      incomingCallId.value = callId
      incomingCallNumber.value = remoteNumber

      session.on('connecting', (e: any) => {
        console.log('呼入会话连接中...', e)
      })

      session.on('progress', (e: any) => {
        console.log('呼入会话进度更新: 振铃中', e)
        agentStatusValue.value = '6'
        // 振铃音用 console.log 代替（示例项目省略音频资源）
        console.log('[振铃音] 播放振铃音（示例项目以日志代替）')
      })

      session.on('sdp', (e: any) => {
        console.log('呼入通话sdp:', e)
      })
    }
  })
}

// ==================== 外呼 ====================

/**
 * 发起呼叫（测试示例）
 * 需求：测试示例项目仅支持注册测试，makeCall 不调用 ua.call()，
 *       无论是否填写号码都仅显示固定提示信息并记录日志，
 *       避免空号校验分支导致测试场景下提示文案不一致
 */
const makeCall = () => {
  ElMessage.warning('测试示例，仅支持注册测试')
  addLog('拨打提示: 测试示例，仅支持注册测试')
}

// ==================== 接听/挂断/拒绝 ====================

/**
 * 接听来电
 * 需求: 接听来电弹窗对应的会话，设置远端音频并切换为活跃会话
 */
const handleAnswer = () => {
  const callId = incomingCallId.value
  if (callId) {
    const info = sessions.value.get(callId)
    if (info?.session) {
      const iceServers = buildIceServers()
      const pcConfig = iceServers.length > 0
        ? { iceServers, iceTransportPolicy: 'all' as const }
        : {}
      info.session.answer({
        sessionTimersExpires: 180,
        pcConfig,
        mediaConstraints: {
          audio: true,
          video: false
        }
      })
      // 接听后切换为活跃会话
      activeCallId.value = callId
      agentStatusValue.value = '5'
      console.log('[振铃音] 停止播放（接听来电）')

      // 设置远端音频轨道
      info.session.connection.addEventListener('track', (event: any) => {
        info.stream = event.streams[0]
        if (activeCallId.value === callId) {
          audioView.srcObject = event.streams[0]
          audioView.play()
          audioView.volume = 1
        }
      })

      computeStatusDuration()
      addLog(`接听来电: ${info.number}`)
    }
  } else {
    ElMessage.warning('没有正在进行的通话')
  }
  incomingCallFlag.value = false
  incomingCallId.value = null
}

/**
 * 挂断当前焦点会话
 * 需求: 终止活跃会话，若有其他会话则自动切换，否则进入话后状态
 */
const handleHangup = () => {
  const callId = activeCallId.value
  if (!callId) {
    ElMessage.warning('没有正在进行的通话')
    return
  }

  const info = sessions.value.get(callId)
  if (info?.session) {
    console.log('[振铃音] 停止播放（挂断）')
    info.session.terminate()
    // removeSession会处理Map清理和状态切换
    removeSession(callId)
    ElMessage.success('通话已挂断')
    addLog(`挂断通话: ${info.number}`)
  } else {
    ElMessage.warning('没有正在进行的通话')
  }
}

/**
 * 拒绝来电(来电弹窗的挂断按钮)
 * 需求: 仅终止来电会话，不影响其他活跃会话
 */
const handleRejectIncoming = () => {
  const callId = incomingCallId.value
  if (callId) {
    const info = sessions.value.get(callId)
    if (info?.session) {
      info.session.terminate()
    }
    console.log('[振铃音] 停止播放（拒绝来电）')
    removeSession(callId)
    addLog(`拒绝来电: ${info?.number || ''}`)
  }
  incomingCallFlag.value = false
  incomingCallId.value = null
}

// ==================== 保持/静音/DTMF ====================

/**
 * 切换当前焦点会话的保持/恢复状态
 * 需求: 调用JsSIP toggleHold API发送re-INVITE切换sendonly/sendrecv,
 *       状态通过hold/unhold事件同步到SessionInfo
 * 异常处理: 捕获JsSIP内部异常(如session状态非CONFIRMED),输出到控制台便于排查
 * JsSIP API说明:
 *   - isOnHold() 返回对象 {local: boolean, remote: boolean},而非布尔值
 *   - local=true表示本端已发起hold,remote=true表示对端已发起hold
 *   - 判断"是否已保持"需检查 local || remote
 */
const toggleHold = () => {
  const session = currentSession.value
  if (!session) {
    console.warn('[toggleHold] 当前无焦点会话,无法操作保持')
    return
  }
  try {
    const holdState = session.isOnHold ? session.isOnHold() : { local: false, remote: false }
    const isHeldNow = holdState.local || holdState.remote
    console.log('[toggleHold] 开始切换保持状态, isOnHold:', holdState,
                'isHeldNow:', isHeldNow, 'callId:', activeCallId.value)
    const cb = (err: any, response?: any) => {
      if (err) {
        const statusCode = response?.status_code || 0
        const cause = response?.reason_phrase || (err as Error)?.message || '未知'
        console.warn(`[toggleHold] ${isHeldNow ? '恢复' : '保持'}协商失败, status=${statusCode}, cause=${cause}, 但会话未终止`)
        if (isHeldNow) {
          const info = sessions.value.get(activeCallId.value || '')
          if (info) { info.isHeld = true }
        } else {
          const info = sessions.value.get(activeCallId.value || '')
          if (info) { info.isHeld = false }
        }
      } else {
        console.log(`[toggleHold] ${isHeldNow ? '恢复' : '保持'}协商成功`)
      }
    }
    if (isHeldNow) {
      console.log('[toggleHold] 当前已保持,调用unhold()发送re-INVITE恢复')
      session.unhold({ eventHandlers: {} }, cb)
    } else {
      console.log('[toggleHold] 当前未保持,调用hold()发送re-INVITE保持')
      session.hold({ eventHandlers: {} }, cb)
    }
    console.log('[toggleHold] 保持状态切换调用完成')
  } catch (e) {
    console.error('[toggleHold] 切换保持状态异常:', e)
  }
}

/**
 * 切换当前焦点会话的静音/取消静音
 * 需求: 调用JsSIP mute/unmute API控制本地音频流，
 *       状态通过muted/unmuted事件同步到SessionInfo
 */
const toggleMute = () => {
  const session = currentSession.value
  if (session) {
    if (isMuted.value) {
      session.unmute({ audio: true })
    } else {
      session.mute({ audio: true })
    }
    // isMuted状态将通过muted/unmuted事件自动同步
  }
}

/**
 * 通话中发送DTMF信号
 * 需求: 在通话中对当前焦点会话发送DTMF按键音
 * @param digit - DTMF按键值(0-9, *, #)
 */
const sendDTMF = (digit: string) => {
  const session = currentSession.value
  if (session) {
    session.sendDTMF(digit)
  }
}

/**
 * 发起咨询转接/盲转
 * 需求: 通话中通过JsSIP refer()发送REFER请求,后端WsReferRequestHandler处理转接逻辑
 * 处理逻辑:
 *   1. 校验转接目标号码非空
 *   2. 构造自定义头域: X-Transfer-Type(attended/blind), X-Gateway-Id(可选)
 *   3. 调用session.refer()发送REFER请求
 *   4. 关闭转接弹窗
 * 业务说明:
 *   - 咨询转接(attended): hold住A-B通话 → originate呼叫C → A与C咨询 → A挂断确认 → B与C桥接
 *   - 盲转(blind): originate呼叫C → C应答后kill A → bridge B和C
 */
const handleTransfer = () => {
  const session = currentSession.value
  const target = transferTarget.value.trim()
  console.log('[handleTransfer] 开始转接, session存在:', !!session, 'target:', target, 'transferType:', transferType.value)
  if (!session || !target) {
    console.warn('[handleTransfer] session或target为空, 退出')
    return
  }
  // 构造REFER自定义头域
  const extraHeaders: string[] = [
    `X-Transfer-Type: ${transferType.value}`
  ]
  if (transferGatewayId.value) {
    extraHeaders.push(`X-Gateway-Id: ${transferGatewayId.value}`)
  }
  try {
    // 调用JsSIP refer()发送REFER请求
    const referResult = session.refer(target, { extraHeaders })
    if (!referResult) {
      console.error('[handleTransfer] refer()返回false,REFER未发送!')
      ElMessage.error('转接失败: 通话状态不允许转接')
      return
    }
    console.log('[handleTransfer] refer()调用成功(已发送REFER请求)')
    addLog(`发起${transferType.value === 'attended' ? '咨询转接' : '盲转'}到 ${target}`)
    ElMessage.success(`已发起${transferType.value === 'attended' ? '咨询转接' : '盲转'}到 ${target}`)
  } catch (e) {
    console.error('[handleTransfer] 发送REFER失败:', e)
    ElMessage.error(`转接失败: ${e instanceof Error ? e.message : String(e)}`)
  } finally {
    // 无论成功或失败都关闭弹窗,避免弹窗卡住无法操作
    showTransferPopup.value = false
    transferTarget.value = ''
    transferGatewayId.value = null
    transferType.value = 'attended'
  }
}

// ==================== 生命周期 ====================

onBeforeUnmount(() => {
  stopKeepAlive()
  unregisterSipClient()
})

interface PublicIPInfo {
  ip: string
  port: string
}
/**
 * 通过 STUN 获取公网 IP 和端口（用于 WebRTC 媒体协商）
 *
 * 需求背景: JsSIP 签入时需获取公网 IP 构建 contact_uri,供 WebRTC ICE 候选交换。
 * 预期结果: 返回 srflx/relay 类型的公网 IP+端口;STUN 不可达时返回本地兜底地址。
 * 处理逻辑:
 *   1. STUN 服务器为空时直接返回本地兜底地址 {ip:'127.0.0.1', port:'0'}，
 *      不创建 RTCPeerConnection，避免阻塞签入流程
 *   2. STUN 不为空时创建 RTCPeerConnection,监听 onicecandidate 事件
 *   3. 收到 srflx/relay 类型候选时 resolve
 *   4. ICE 收集完成(null candidate)仍无公网候选时 resolve 本地兜底地址
 *   5. 10 秒超时兜底: STUN 服务器不可达时返回本地地址继续 SIP 注册流程
 * 异常场景:
 *   - STUN 服务器宕机/网络隔离: 10 秒超时后兜底返回 127.0.0.1,SIP 信令走 WebSocket 不受影响
 *   - createOffer/setLocalDescription 失败: reject(由调用方 try-catch 处理)
 * 设计约束:
 *   - 兜底返回本地地址而非 reject,因为 SIP REGISTER/INVITE 信令走 WebSocket 不依赖公网 IP;
 *     媒体协商失败由 TURN 服务器兜底或本地局域网直连,不应阻塞签入流程
 */
async function getPublicIPAndPort(): Promise<PublicIPInfo> {
  const stun = stunServer.value.trim()
  // STUN 为空时直接返回本地兜底地址，不创建 RTCPeerConnection，避免阻塞签入
  if (!stun) {
    console.log('[getPublicIPAndPort] STUN 未配置，使用本地地址 127.0.0.1 兜底')
    return { ip: '127.0.0.1', port: '0' }
  }

  const stunUrl = stun.startsWith('stun:') ? stun : `stun:${stun}`
  return new Promise<PublicIPInfo>((resolve, reject) => {
    // STUN 超时兜底: 10 秒后返回本地地址,避免 STUN 不可达时 Promise 永远挂起阻塞签入流程
    const STUN_TIMEOUT_MS = 10000
    const pc = new RTCPeerConnection({
      iceServers: [{ urls: stunUrl }]
    })
    const timeout = setTimeout(() => {
      pc.close()
      console.warn('[getPublicIPAndPort][STUN 超时,使用本地地址兜底]')
      resolve({ ip: '127.0.0.1', port: '0' })
    }, STUN_TIMEOUT_MS)

    pc.createDataChannel('dummy')

    pc.onicecandidate = (evt) => {
      if (!evt.candidate) {
        clearTimeout(timeout)
        pc.close()
        // ICE 收集完成仍未获取到 srflx/relay 候选,兜底返回本地地址
        console.warn('[getPublicIPAndPort][ICE 收集完成无公网候选,使用本地地址兜底]')
        resolve({ ip: '127.0.0.1', port: '0' })
        return
      }
      const parts = evt.candidate.candidate.split(' ') as Array<string>
      const [, , , , ip, port, , type] = parts
      if ((type === 'srflx' || type === 'relay') && ip && port) {
        clearTimeout(timeout)
        pc.close()
        resolve({ ip, port })
      }
    }

    pc.createOffer()
      .then((desc) => pc.setLocalDescription(desc))
      .catch((err) => {
        clearTimeout(timeout)
        pc.close()
        reject(err)
      })
  })
}
</script>

<style scoped>
.soft-phone-wrapper {
  padding: 16px;
  width: 100%;
  min-height: calc(100vh - 57px);
}

.main-row {
  width: 100%;
}

.config-card,
.phone-card,
.panel-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.card-header {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.config-card :deep(.el-card__body) {
  padding: 12px 16px;
}

.config-card :deep(.el-form-item) {
  margin-bottom: 12px;
}

.config-card :deep(.el-form-item__label) {
  font-size: 12px;
  color: #606266;
  padding-bottom: 4px;
  line-height: 1.4;
}

.phone-card :deep(.el-card__body) {
  padding: 16px;
  position: relative;
}

/* ==================== 软电话工具栏 ==================== */

.soft-phone-bar {
  display: inline-flex;
  align-items: center;
  border: 1px solid #e4e7ed;
  overflow: visible;
  padding: 2px 0;
  border-radius: 12px;
  position: relative;
}

.bar-section {
  display: flex;
  align-items: center;
  height: 100%;
  flex-shrink: 0;
}

.bar-auth {
  padding: 0 8px;
}

.bar-status {
  padding: 0 10px;
  gap: 8px;
}

.bar-dial {
  padding: 0 8px;
}

.bar-divider {
  width: 1px;
  height: 24px;
  background: #e4e7ed;
  flex-shrink: 0;
}

.switch-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.switch-label {
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.switch-label--ready {
  color: #67c23a;
}

.switch-label--busy {
  color: #e6a23c;
}

.offline-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 10px;
  height: 24px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  background: #f4f4f5;
  color: #909399;
  border: 1px solid #e9e9eb;
}

.offline-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #909399;
}

/* ==================== 拨号弹窗 ==================== */

.popover {
  position: absolute;
  top: calc(100% + 12px);
  left: 16px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1), 0 0 1px rgba(0, 0, 0, 0.06);
  border: 1px solid #e4e7ed;
  z-index: 2000;
  overflow: visible;
  width: 280px;
}

.popover--incall {
  position: relative;
  top: 16px;
  left: 0;
  width: 100%;
  margin-top: 8px;
}

.popover-header {
  padding: 10px 16px;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.popover-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.popover-close {
  width: 20px;
  height: 20px;
  border-radius: 4px;
  border: none;
  background: transparent;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 12px;
  transition: all 0.15s;
}

.popover-close:hover {
  background: #fef0f0;
  color: #f56c6c;
}

.popover-body {
  padding: 14px 16px;
}

.dial-input {
  width: 100%;
  height: 34px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 10px;
  font-size: 15px;
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  letter-spacing: 2px;
  text-align: center;
  color: #303133;
  background: #fff;
  outline: none;
  margin-bottom: 10px;
}

.dial-input:focus {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.12);
}

.dial-input::placeholder {
  color: #a8abb2;
  font-family: inherit;
  letter-spacing: 0;
  font-size: 12px;
}

.dialpad-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
}

.dialpad-grid--incall {
  margin-top: 10px;
}

.dialpad-key {
  height: 38px;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
  background: #fff;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
  cursor: pointer;
  transition: all 0.15s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.dialpad-key:hover {
  background: #ecf5ff;
  border-color: #a0cfff;
  color: #409eff;
}

.dialpad-key:active {
  background: #a0cfff;
  transform: scale(0.97);
}

.dialpad-key--incall {
  height: 32px;
  font-size: 14px;
}

.dialpad-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin-top: 8px;
}

.dialpad-act {
  height: 32px;
  border-radius: 4px;
  border: 1px solid transparent;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  transition: all 0.15s;
}

.dialpad-act--default {
  background: #fff;
  border-color: #dcdfe6;
  color: #606266;
}

.dialpad-act--default:hover {
  color: #409eff;
  border-color: #79bbff;
}

.dialpad-act--primary {
  background: #409eff;
  color: #fff;
}

.dialpad-act--primary:hover {
  background: #79bbff;
}

/* ==================== 多通话列表 ==================== */

.session-list {
  border-bottom: 1px solid #e4e7ed;
}

.session-list-header {
  padding: 8px 16px;
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  background: #f5f7fa;
}

.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  cursor: pointer;
  transition: background 0.15s;
}

.session-item:hover {
  background: #f5f7fa;
}

.session-item--active {
  background: #ecf5ff;
  border-left: 3px solid #409eff;
  padding-left: 13px;
}

.session-item-number {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 13px;
  color: #303133;
  letter-spacing: 0.5px;
}

.session-item-status {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 10px;
}

.session-item-status--ringing {
  background: #fdf6ec;
  color: #e6a23c;
}

.session-item-status--active {
  background: #f0f9eb;
  color: #67c23a;
}

.session-item-status--held {
  background: #f4f4f5;
  color: #909399;
}

/* ==================== 通话控制区域 ==================== */

.active-call {
  padding: 16px;
  text-align: center;
}

.active-call-timer {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 24px;
  font-weight: 600;
  color: #409eff;
  margin-bottom: 6px;
  letter-spacing: 2px;
}

.active-call-number {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 13px;
  color: #606266;
  margin-bottom: 12px;
  letter-spacing: 1px;
}

.call-controls {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.call-ctrl-btn {
  flex: 1;
  height: 32px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  background: #fff;
  font-size: 12px;
  font-weight: 500;
  color: #606266;
  cursor: pointer;
  transition: all 0.15s;
}

.call-ctrl-btn:hover:not(:disabled) {
  color: #409eff;
  border-color: #79bbff;
}

.call-ctrl-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.call-ctrl-btn--active {
  background: #ecf5ff;
  border-color: #a0cfff;
  color: #409eff;
}

.call-ctrl-btn--active:hover:not(:disabled) {
  background: #d9ecff;
}

.call-ctrl-btn--transfer {
  border-color: #f5dab1;
  color: #e6a23c;
}

.call-ctrl-btn--transfer:hover:not(:disabled) {
  background: #fdf6ec;
}

.transfer-panel {
  margin-top: 8px;
  padding: 0;
  border-radius: 6px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  overflow: hidden;
}

.transfer-panel .popover-header {
  padding: 8px 12px;
}

.transfer-panel .popover-body {
  padding: 10px 12px;
}

.hangup-full {
  width: 100%;
  height: 36px;
  border-radius: 4px;
  border: none;
  background: #f56c6c;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  transition: all 0.15s;
}

.hangup-full:hover {
  background: #f78989;
}

/* ==================== 来电弹窗 ==================== */

.incoming-dialog {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  background: #fff;
  border-radius: 12px;
  width: 280px;
  overflow: hidden;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.12);
  border: 1px solid #e4e7ed;
  z-index: 3000;
}

.incoming-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.3);
  z-index: 2999;
}

.incoming-header {
  background: #409eff;
  padding: 24px 20px 18px;
  text-align: center;
  color: white;
}

.incoming-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 10px;
}

.incoming-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 4px;
}

.incoming-number {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
  font-size: 13px;
  opacity: 0.9;
  letter-spacing: 1px;
}

.incoming-actions {
  display: flex;
  gap: 10px;
  padding: 14px 20px 18px;
}

.incoming-btn {
  flex: 1;
  height: 36px;
  border-radius: 4px;
  border: 1px solid transparent;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  transition: all 0.2s;
}

.incoming-btn--reject {
  background: #fef0f0;
  color: #f56c6c;
  border-color: #fbc4c4;
}

.incoming-btn--reject:hover {
  background: #f78989;
  color: #fff;
}

.incoming-btn--accept {
  background: #67c23a;
  color: #fff;
}

.incoming-btn--accept:hover {
  background: #85ce61;
}

/* ==================== 右侧面板（上下分块） ==================== */
/* 右栏整体高度对齐左侧，操作日志占上半（约 40%），WS 消息占下半（约 60%） */

.panel-card {
  display: flex;
  flex-direction: column;
}

.panel-card--log {
  height: calc((100vh - 89px) * 0.4);
  margin-bottom: 12px;
}

.panel-card--ws {
  height: calc((100vh - 89px) * 0.6);
}

.panel-card :deep(.el-card__body) {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 0 16px 12px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid #ebeef5;
}

.panel-toolbar-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  flex: 1;
}

.panel-toolbar-legend {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #606266;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.legend-dot--send {
  background: #67c23a;
}

.legend-dot--recv {
  background: #409eff;
}

.panel-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px 4px;
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
}

.panel-empty {
  color: #c0c4cc;
  text-align: center;
  padding: 40px 0;
  font-size: 13px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

/* 日志面板 */

.log-panel {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
}

.log-item {
  display: flex;
  gap: 8px;
  padding: 4px 6px;
  border-bottom: 1px dashed #f0f0f0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}

.log-item:hover {
  background: #fafafa;
}

.log-time {
  color: #909399;
  flex-shrink: 0;
  font-size: 11px;
}

.log-content {
  color: #303133;
  flex: 1;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

/* WS 消息面板 */

.ws-panel {
  font-family: 'Menlo', 'Monaco', 'Consolas', monospace;
}

.ws-item {
  padding: 8px;
  margin-bottom: 6px;
  border-radius: 4px;
  border-left: 3px solid;
}

.ws-item--send {
  background: #f0f9eb;
  border-left-color: #67c23a;
}

.ws-item--recv {
  background: #ecf5ff;
  border-left-color: #409eff;
}

.ws-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.ws-time {
  font-size: 11px;
  color: #909399;
}

.ws-summary {
  font-size: 12px;
  color: #303133;
  line-height: 1.5;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
