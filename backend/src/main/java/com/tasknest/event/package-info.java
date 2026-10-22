/**
 * 事件契约与发布管线。{@link com.tasknest.event.EventEnvelope} 是 SSE 信封，
 * {@link com.tasknest.event.EventPublisher} 负责 seq、投影、落库与推送。
 * 字段变更必须同步 {@code docs/events.md} 与前端 {@code api/events.ts}。
 */
package com.tasknest.event;
