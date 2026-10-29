package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 将仓库登记路径限制在配置的源码根内，并返回可持久化的相对路径文本。
 * 这里只校验已存在的本地目录，不执行 Git clone，也不要求目录包含 Git 元数据。
 * 检查不持有目录句柄或文件系统锁，检查与后续使用之间仍可能发生路径替换，即 TOCTOU 竞态。
 */
final class ControlledRepositoryPath {
    private ControlledRepositoryPath() { }

    /**
     * sourceRoot 由应用服务预先转为规范化绝对路径；输入先做文本规范化，再检查真实文件系统位置。
     * 返回值由根目录与候选路径的相对关系生成；实现未额外要求目标必须是根目录的严格子目录。
     */
    static String normalize(Path sourceRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new InvalidRepositoryException("Mount path is required");
        }
        Path relative;
        try {
            // 统一分隔符并消除冗余点段；是否为绝对路径仍遵循当前平台的 Path 语义。
            relative = Path.of(rawPath.replace('\\', '/')).normalize();
        } catch (RuntimeException exception) {
            // 将路径解析失败收敛为业务输入错误，不向 API 传播底层路径解析异常。
            throw new InvalidRepositoryException("Mount path is invalid");
        }
        // 对规范化后的路径做词法筛选；随后仍须检查解析后的真实位置，不能只依赖字符串。
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().equals(".")) {
            throw new InvalidRepositoryException("Mount path must be relative to the configured source root");
        }
        Path candidate = sourceRoot.resolve(relative).normalize();
        if (!candidate.startsWith(sourceRoot)) {
            throw new InvalidRepositoryException("Mount path escapes the configured source root");
        }
        try {
            // toRealPath 要求路径存在并解析链接；真实位置必须仍位于真实根目录内。
            Path realRoot = sourceRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            // 即使链接最终仍指向根内，也拒绝相对路径各段被识别为符号链接的情况，并要求目标为目录。
            if (!realCandidate.startsWith(realRoot) || containsSymbolicLink(sourceRoot, relative)
                    || !Files.isDirectory(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new InvalidRepositoryException("Mount path must identify a real directory inside the source root");
            }
        } catch (IOException exception) {
            // 缺失、不可访问等文件系统读取失败统一视为不能登记，不保存未经确认的路径。
            throw new InvalidRepositoryException("Mount path does not exist inside the configured source root");
        }
        // 保存相对位置，让后续 Runner 按自己的受控源码根解析，而不是使用 Platform 的绝对目录。
        return sourceRoot.relativize(candidate).toString().replace('\\', '/');
    }

    /**
     * 从根以下逐段检测相对路径，不单独检测根自身；根的真实位置由调用处的 toRealPath 解析。
     * Files.isSymbolicLink 的判断依赖文件系统提供者，不能据此承诺覆盖所有 Windows junction 或重解析点。
     */
    private static boolean containsSymbolicLink(Path root, Path relative) {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }
}
