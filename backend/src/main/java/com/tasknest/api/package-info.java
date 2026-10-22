/**
 * REST 边界：控制器只做参数校验与转发，统一返回 {@code ApiResponse}。
 *
 * <p>主要入口：{@link com.tasknest.api.MainTaskController}、
 * {@link com.tasknest.api.PlanSessionController}、{@link com.tasknest.api.RunController}。</p>
 */
package com.tasknest.api;
