import { apiBase } from './client'
import {
  EVENT_TYPES,
  parseEvent,
  type ConnectionState,
  type TaskNestEvent,
} from './events'

export interface SseOptions {
  runId: string
  initialAfterSeq: number
  getAfterSeq: () => number
  beforeReconnect?: () => Promise<number | null>
  onEvent: (event: TaskNestEvent) => void
  onStateChange: (state: ConnectionState, attempt: number) => void
  onParseError: (raw: string, reason: string, seq: number | null) => void
}

/**
 * 解析失败时尽量从原始帧里取出 seq，让调用方可以跳过这条坏事件并推进游标，
 * 而不是让整个流停在缺口上。
 */
function peekSeq(raw: string): number | null {
  try {
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) {
      return null
    }
    const seq = (value as { seq?: unknown }).seq
    return typeof seq === 'number' && Number.isInteger(seq) && seq >= 1 ? seq : null
  } catch {
    return null
  }
}

/**
 * EventSource 的手动重连封装：
 * - 关闭浏览器自带的自动重连，避免与快照恢复竞争；
 * - 指数退避 1s 起、翻倍、上限 30s，附带 0–20% 抖动；
 * - 重连前可先拉快照，返回 null 表示不再重连（运行已终止）。
 */
export class SseClient {
  private source: EventSource | null = null
  private closed = false
  private retryTimer: number | null = null
  private attempt = 0

  constructor(private readonly options: SseOptions) {}

  connect(afterSeq: number = this.options.initialAfterSeq): void {
    this.closed = false
    this.open(afterSeq)
  }

  restart(afterSeq: number): void {
    if (this.closed) {
      return
    }
    this.clearRetry()
    this.attempt = 0
    this.open(afterSeq)
  }

  close(): void {
    this.closed = true
    this.clearRetry()
    this.disposeSource()
    this.options.onStateChange('DISCONNECTED', this.attempt)
  }

  /**
   * 开发工作台用：主动让当前连接失败，走完整的退避、快照恢复与游标续传路径。
   * 只影响前端连接，不会停止后端编排。
   */
  simulateTransportError(): void {
    if (this.closed || !this.source) {
      return
    }
    this.clearRetry()
    this.handleTransportError()
  }

  private open(afterSeq: number): void {
    this.disposeSource()
    this.options.onStateChange(this.attempt > 0 ? 'RECONNECTING' : 'CONNECTING', this.attempt)

    const url = `${apiBase()}/api/runs/${encodeURIComponent(this.options.runId)}/events?afterSeq=${afterSeq}`
    const source = new EventSource(url)
    this.source = source

    source.onopen = () => {
      if (this.source !== source) {
        return
      }
      this.attempt = 0
      this.options.onStateChange('CONNECTED', 0)
    }

    for (const type of EVENT_TYPES) {
      source.addEventListener(type, (rawEvent) => {
        if (this.source !== source) {
          return
        }
        // 原生连接错误也是名为 error 的 Event，只有带 data 的 MessageEvent 才是业务事件。
        if (
          !(rawEvent instanceof MessageEvent)
          || typeof rawEvent.data !== 'string'
          || rawEvent.data.length === 0
        ) {
          return
        }
        const event = parseEvent(rawEvent.data)
        if (!event) {
          this.options.onParseError(
            rawEvent.data,
            '事件 JSON 或字段不完整',
            peekSeq(rawEvent.data),
          )
          return
        }
        this.options.onEvent(event)
      })
    }

    source.onerror = () => {
      if (this.source !== source || this.closed) {
        return
      }
      this.handleTransportError()
    }
  }

  private handleTransportError(): void {
    this.disposeSource()
    this.attempt += 1
    this.options.onStateChange('RECONNECTING', this.attempt)
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null
      void this.reconnect()
    }, this.backoff(this.attempt))
  }

  private async reconnect(): Promise<void> {
    if (this.closed) {
      return
    }
    let cursor: number | null = this.options.getAfterSeq()
    if (this.options.beforeReconnect) {
      try {
        cursor = await this.options.beforeReconnect()
      } catch {
        cursor = this.options.getAfterSeq()
      }
    }
    if (this.closed) {
      return
    }
    if (cursor === null) {
      this.options.onStateChange('DISCONNECTED', this.attempt)
      return
    }
    this.open(cursor)
  }

  private backoff(attempt: number): number {
    const exponential = Math.min(30_000, 1_000 * 2 ** Math.max(0, attempt - 1))
    const jitter = exponential * Math.random() * 0.2
    return Math.round(exponential + jitter)
  }

  private clearRetry(): void {
    if (this.retryTimer !== null) {
      window.clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
  }

  private disposeSource(): void {
    if (!this.source) {
      return
    }
    this.source.onopen = null
    this.source.onerror = null
    this.source.close()
    this.source = null
  }
}
