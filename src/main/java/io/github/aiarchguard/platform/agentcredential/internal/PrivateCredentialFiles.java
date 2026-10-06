package io.github.aiarchguard.platform.agentcredential.internal;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

final class PrivateCredentialFiles {
    private PrivateCredentialFiles() { }

    static void check(Path path, boolean directory) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.toRealPath().equals(path)
                || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                              : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw unavailable();
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var allowed = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
            if (!allowed.containsAll(posix.readAttributes().permissions())) throw unavailable();
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw unavailable();
        var owner = acl.getOwner();
        for (var entry : acl.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                    && !entry.principal().getName().equalsIgnoreCase("NT AUTHORITY\\SYSTEM")) throw unavailable();
        }
    }

    static FileAttribute<?> privateAttribute(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        }
        var owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        var acl = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        return new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return acl; }
        };
    }

    // Setup helper used only for synthetic test files; production does not change an operator's ACL.
    static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }

    static CredentialFailure unavailable() { return new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
}
