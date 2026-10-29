package io.github.aiarchguard.platform.identity;

import java.util.Set;
import java.util.UUID;

/** 已认证操作者的不可变身份与全局权限快照；Project 成员权限由 Project 模块另行校验。 */
public record CurrentActor(UUID id, Set<String> permissions) {

    public CurrentActor {
        permissions = Set.copyOf(permissions);
    }

    /** 精确匹配权限字符串，不在这里推导角色继承或 Project 授权。 */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
