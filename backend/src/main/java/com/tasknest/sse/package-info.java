/**
 * SSE 连接管理：按 {@code afterSeq} 补发历史事件，按连接队列有序推送，
 * 维持心跳并在终态 / 断线后关闭。
 */
package com.tasknest.sse;
