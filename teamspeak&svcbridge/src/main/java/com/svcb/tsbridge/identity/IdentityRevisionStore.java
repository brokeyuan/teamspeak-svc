package com.svcb.tsbridge.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 持久化签发 revision 计数器：同一签发公钥跨进程重启严格单调，已发出的
 * revision 永不复用。
 *
 * <p>采用预留计数块：块用尽时先原子持久化"恢复点"，再逐个发放块内编号；
 * 崩溃恢复时从恢复点继续，块内未用编号一并跳过、不复用。持久化失败抛出
 * {@link RevisionAllocationException} —— 调用方必须暂停身份提升（语音保持
 * Ghost），不得回退到内存计数。</p>
 */
public final class IdentityRevisionStore {
    /** 预留块大小：崩溃时最多跳过这么多编号。 */
    public static final int RESERVE_BLOCK = 100;

    private final Persistence persistence;
    private long next;
    private long reservedUpTo;

    /** 恢复点存储：load 返回 0 表示首次使用；损坏/不可读必须抛出而非返回 0。 */
    public interface Persistence {
        void persistRecoveryPoint(long recoveryPoint) throws IOException;

        long loadRecoveryPoint();
    }

    public IdentityRevisionStore(Persistence persistence) {
        this.persistence = persistence;
        long loaded = persistence.loadRecoveryPoint();
        this.next = Math.max(2L, loaded);
        this.reservedUpTo = this.next;
    }

    /** 分配下一个 revision；持久化失败抛出 {@link RevisionAllocationException}。 */
    public synchronized long allocate() {
        if (next >= reservedUpTo) {
            long recoveryPoint = next + RESERVE_BLOCK;
            try {
                persistence.persistRecoveryPoint(recoveryPoint);
            } catch (IOException e) {
                throw new RevisionAllocationException("Unable to persist revision recovery point "
                        + recoveryPoint, e);
            }
            reservedUpTo = recoveryPoint;
        }
        return next++;
    }

    /** 文件持久化：单行 long，临时文件 + 原子替换。 */
    public static final class FilePersistence implements Persistence {
        private final Path path;

        public FilePersistence(Path path) {
            this.path = path;
        }

        @Override
        public void persistRecoveryPoint(long recoveryPoint) throws IOException {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, Long.toString(recoveryPoint), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Override
        public long loadRecoveryPoint() {
            try {
                if (!Files.exists(path)) {
                    return 0L;
                }
                String value = Files.readString(path).trim();
                if (value.isEmpty()) {
                    return 0L;
                }
                // 损坏文件抛 NumberFormatException：宁可暂停提升，不可复用编号。
                return Long.parseLong(value);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read revision recovery point", e);
            }
        }
    }

    public static final class RevisionAllocationException extends RuntimeException {
        public RevisionAllocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
