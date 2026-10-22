/**
 * 内存运行状态：{@link com.tasknest.store.RunStore} 保存活动运行，
 * {@link com.tasknest.store.TaskProjection} 把事件投影为两层任务；
 * 进程重启后由 MySQL 历史档案兜底。
 */
package com.tasknest.store;
