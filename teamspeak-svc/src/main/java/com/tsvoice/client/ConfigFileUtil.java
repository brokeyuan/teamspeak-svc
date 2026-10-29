package com.tsvoice.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 配置/身份文件写入工具：原子替换 + POSIX 600（不支持则静默忽略）。
 */
public final class ConfigFileUtil {

    private ConfigFileUtil() {
    }

    /** 写字符串：先写临时文件，再原子替换目标；POSIX 下权限收紧为 600。 */
    public static void writePrivate(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            restrict(tmp);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(path);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** POSIX 600；Windows 等不支持 POSIX 权限的平台上忽略。 */
    public static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    /** 解析失败时保留原始文件（改名 .broken），避免下次保存覆盖掉可人工修复的内容。 */
    public static void backupBroken(Path path) {
        try {
            if (Files.exists(path)) {
                Path broken = path.resolveSibling(path.getFileName() + ".broken");
                Files.move(path, broken, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }
}
