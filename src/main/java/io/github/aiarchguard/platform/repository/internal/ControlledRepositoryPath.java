package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class ControlledRepositoryPath {
    private ControlledRepositoryPath() { }

    static String normalize(Path sourceRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new InvalidRepositoryException("Mount path is required");
        }
        Path relative;
        try {
            relative = Path.of(rawPath.replace('\\', '/')).normalize();
        } catch (RuntimeException exception) {
            throw new InvalidRepositoryException("Mount path is invalid");
        }
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().equals(".")) {
            throw new InvalidRepositoryException("Mount path must be relative to the configured source root");
        }
        Path candidate = sourceRoot.resolve(relative).normalize();
        if (!candidate.startsWith(sourceRoot)) {
            throw new InvalidRepositoryException("Mount path escapes the configured source root");
        }
        try {
            Path realRoot = sourceRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot) || containsSymbolicLink(sourceRoot, relative)
                    || !Files.isDirectory(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new InvalidRepositoryException("Mount path must identify a real directory inside the source root");
            }
        } catch (IOException exception) {
            throw new InvalidRepositoryException("Mount path does not exist inside the configured source root");
        }
        return sourceRoot.relativize(candidate).toString().replace('\\', '/');
    }

    private static boolean containsSymbolicLink(Path root, Path relative) {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }
}
