package com.svcb.tsbridge.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;

/** Persists one EC P-256 claim-issuer key pair per MC server. */
public final class IdentitySigningKeys {
    private IdentitySigningKeys() {
    }

    public static synchronized KeyPair loadOrGenerate(Path path) {
        try {
            if (Files.exists(path)) {
                String[] lines = Files.readString(path, StandardCharsets.UTF_8).trim().split("\n", -1);
                if (lines.length == 2) {
                    PublicKey publicKey = com.tsvoice.protocol.SignedPlayerClaim.decodePublicKey(
                            Base64.getDecoder().decode(lines[0].trim()));
                    var privateKey = com.tsvoice.protocol.SignedPlayerClaim.decodePrivateKey(
                            Base64.getDecoder().decode(lines[1].trim()));
                    return new KeyPair(publicKey, privateKey);
                }
            }
            KeyPair generated = com.tsvoice.protocol.ServerTrustDirectory.generateIssuerKeyPair();
            save(path, generated);
            return generated;
        } catch (java.security.GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Unable to load identity signing keys", e);
        }
    }

    private static void save(Path path, KeyPair keyPair) throws IOException {
        Files.createDirectories(path.getParent());
        String content = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n" + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()) + "\n";
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

}
