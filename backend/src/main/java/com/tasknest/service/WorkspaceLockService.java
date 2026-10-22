package com.tasknest.service;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Git 仓库根目录加进程内锁。同一仓库同时只允许一个规划会话或执行运行。
 * 锁不持久化，进程重启后自动释放。
 */
@Service
public class WorkspaceLockService {

    private final Map<String, String> owners = new ConcurrentHashMap<>();

    /**
     * 获取仓库锁。同一持有者重复获取视为成功（可重入）；其他持有者已占用时
     * 抛出 {@link ErrorCode#WORKSPACE_BUSY}。
     *
     * @return 规范化后的仓库锁 key，可用于按 key 释放
     */
    public String acquire(Path root, String owner) {
        String key = key(root);
        String existing = owners.putIfAbsent(key, owner);
        if (existing != null && !existing.equals(owner)) {
            throw new BusinessException(ErrorCode.WORKSPACE_BUSY,
                    "同一仓库已有活动运行: " + key);
        }
        return key;
    }

    /**
     * 按仓库路径释放锁。只有持有者匹配时才真正释放，避免误释放他人的锁。
     */
    public void release(Path root, String owner) {
        String key = key(root);
        owners.computeIfPresent(key, (ignored, current) ->
                current.equals(owner) ? null : current);
    }

    /**
     * 按已规范化的锁 key 释放；持有者不匹配时保持原锁不变。
     */
    public void release(String key, String owner) {
        owners.computeIfPresent(key, (ignored, current) ->
                current.equals(owner) ? null : current);
    }

    /**
     * 判断仓库当前是否被任意持有者占用。
     */
    public boolean isLocked(Path root) {
        return owners.containsKey(key(root));
    }

    /**
     * 优先使用真实路径作为 key，保证符号链接与相对路径指向同一仓库；
     * 无法解析时退回绝对规范化路径。
     */
    private String key(Path root) {
        try {
            return root.toRealPath().toString();
        } catch (IOException exception) {
            return root.toAbsolutePath().normalize().toString();
        }
    }
}
