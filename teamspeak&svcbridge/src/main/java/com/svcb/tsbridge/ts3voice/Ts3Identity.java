package com.svcb.tsbridge.ts3voice;

import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.ECCurve;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * TS3 客户端身份（NIST P-256 / prime256v1 ECC 密钥 + 工作量证明安全等级）。
 * 移植自 TSLib 的 IdentityData + TsCrypt.GenerateNewIdentity / ImproveSecurity。
 */
public final class Ts3Identity {

    private static final int MAX_ULONG_STRING_LEN = 20;

    private final BigInteger privateKey;
    private final ECPoint publicKey;
    private final String publicKeyString;
    private final String clientUid;
    private long validKeyOffset;
    private long lastCheckedKeyOffset;

    private Ts3Identity(BigInteger privateKey, ECPoint publicKey, long keyOffset) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.publicKeyString = exportPublicKey(publicKey);
        this.clientUid = Base64.getEncoder().encodeToString(
                sha1(publicKeyString.getBytes(StandardCharsets.US_ASCII)));
        this.validKeyOffset = keyOffset;
        this.lastCheckedKeyOffset = keyOffset;
    }

    public BigInteger getPrivateKey() {
        return privateKey;
    }

    public ECPoint getPublicKey() {
        return publicKey;
    }

    /** 公钥 DER 序列的 base64（clientinitiv 的 omega 参数）。 */
    public String getPublicKeyString() {
        return publicKeyString;
    }

    /** client uid = base64(sha1(publicKeyString))。 */
    public String getClientUid() {
        return clientUid;
    }

    /** 当前安全等级对应的 keyOffset（clientinit 的 client_key_offset）。 */
    public long getValidKeyOffset() {
        return validKeyOffset;
    }

    /**
     * 生成新身份并提升安全等级。
     *
     * @param securityLevel 目标安全等级（官方客户端默认 8；等级 N 平均需要 ~2^N 次 sha1）
     * @param maxMillis     提升等级的时间上限（超时返回当前最高等级，不抛异常）
     */
    public static Ts3Identity generate(int securityLevel, long maxMillis) {
        ECCurve curve = ECNamedCurveTable.getByName("prime256v1").getCurve();
        ECDomainParameters domain = new ECDomainParameters(
                curve, ECNamedCurveTable.getByName("prime256v1").getG(),
                ECNamedCurveTable.getByName("prime256v1").getN(),
                ECNamedCurveTable.getByName("prime256v1").getH());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new java.security.SecureRandom()));
        var pair = gen.generateKeyPair();
        ECPoint pub = ((ECPublicKeyParameters) pair.getPublic()).getQ().normalize();
        BigInteger priv = ((ECPrivateKeyParameters) pair.getPrivate()).getD();

        Ts3Identity identity = new Ts3Identity(priv, pub, 0);
        identity.improveSecurity(securityLevel, maxMillis);
        return identity;
    }

    /**
     * 提升安全等级：寻找最小的 offset 使
     * sha1(publicKeyString + decimal(offset)) 的前导零位数 ≥ toLevel。
     * 算法耗时约 2^level 次哈希；可随时超时中断（保留已达到的最好结果）。
     */
    public void improveSecurity(int toLevel, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        byte[] pubBytes = publicKeyString.getBytes(StandardCharsets.US_ASCII);
        byte[] buffer = new byte[pubBytes.length + MAX_ULONG_STRING_LEN];
        System.arraycopy(pubBytes, 0, buffer, 0, pubBytes.length);

        int best = securityLevel(buffer, pubBytes.length, validKeyOffset);
        if (best >= toLevel) {
            return;
        }
        long offset = lastCheckedKeyOffset;
        while (System.currentTimeMillis() < deadline) {
            int curr = securityLevel(buffer, pubBytes.length, offset);
            if (curr > best) {
                validKeyOffset = offset;
                best = curr;
                if (best >= toLevel) {
                    lastCheckedKeyOffset = offset;
                    return;
                }
            }
            offset++;
        }
        lastCheckedKeyOffset = offset;
    }

    /** 当前身份的安全等级。 */
    public int getSecurityLevel() {
        byte[] pubBytes = publicKeyString.getBytes(StandardCharsets.US_ASCII);
        byte[] buffer = new byte[pubBytes.length + MAX_ULONG_STRING_LEN];
        System.arraycopy(pubBytes, 0, buffer, 0, pubBytes.length);
        return securityLevel(buffer, pubBytes.length, validKeyOffset);
    }

    private static int securityLevel(byte[] hashBuffer, int pubKeyLen, long offset) {
        byte[] numBuffer = new byte[MAX_ULONG_STRING_LEN];
        int numLen = 0;
        long off = offset;
        do {
            numBuffer[numLen++] = (byte) ('0' + (off % 10));
            off /= 10;
        } while (off > 0);
        for (int i = 0; i < numLen; i++) {
            hashBuffer[pubKeyLen + i] = numBuffer[numLen - (i + 1)];
        }
        byte[] outHash = sha1(hashBuffer, 0, pubKeyLen + numLen);
        return leadingZeroBits(outHash);
    }

    private static int leadingZeroBits(byte[] data) {
        int curr = 0;
        int i;
        for (i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                curr += 8;
            } else {
                break;
            }
        }
        if (i < data.length) {
            for (int bit = 0; bit < 8; bit++) {
                if ((data[i] & (1 << bit)) == 0) {
                    curr++;
                } else {
                    break;
                }
            }
        }
        return curr;
    }

    /** 公钥 DER 导出（与 TSLib ExportPublicKey 一致）：SEQ(BITSTRING(0,7), INT 32, INT x, INT y)。 */
    public static String exportPublicKey(ECPoint publicKey) {
        var seq = new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[]{
                new DERBitString(new byte[]{0b0000_0000}, 7),
                new ASN1Integer(32),
                new ASN1Integer(publicKey.getAffineXCoord().toBigInteger()),
                new ASN1Integer(publicKey.getAffineYCoord().toBigInteger())});
        try {
            return Base64.getEncoder().encodeToString(seq.getEncoded("DER"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("DER encoding failed", e);
        }
    }

    private static byte[] sha1(byte[] data, int offset, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(data, offset, len);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha1(byte[] data) {
        return sha1(data, 0, data.length);
    }

    // ---- 持久化（跨重启保持同一 TS 身份，配对关系才稳定） ----

    /** 保存身份到文件：私钥 DER base64 + 安全等级 offset，三行文本。 */
    public void save(java.nio.file.Path path) throws java.io.IOException {
        String priv = exportPrivateKey(privateKey);
        String content = priv + "\n" + validKeyOffset + "\n" + lastCheckedKeyOffset + "\n";
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, content);
    }

    /** 从文件加载身份；文件不存在或损坏返回 null。 */
    public static Ts3Identity load(java.nio.file.Path path) {
        if (!java.nio.file.Files.exists(path)) {
            return null;
        }
        try {
            String[] lines = java.nio.file.Files.readString(path).trim().split("\n");
            BigInteger priv = importPrivateKey(lines[0].trim());
            if (priv == null) {
                return null;
            }
            long offset = Long.parseLong(lines[1].trim());
            long lastChecked = lines.length > 2 ? Long.parseLong(lines[2].trim()) : offset;
            ECPoint pub = ECNamedCurveTable.getByName("prime256v1").getG()
                    .multiply(priv).normalize();
            Ts3Identity id = new Ts3Identity(priv, pub, offset);
            id.lastCheckedKeyOffset = Math.max(lastChecked, offset);
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /** 私钥 DER 导出（与 TSLib ExportPrivateKey 一致）：SEQ(BITSTRING(0xC0,6), INT 32, INT d)。 */
    private static String exportPrivateKey(BigInteger privateKey) {
        try {
            var seq = new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[]{
                    new DERBitString(new byte[]{(byte) 0b1100_0000}, 6),
                    new ASN1Integer(32),
                    new ASN1Integer(privateKey)});
            return Base64.getEncoder().encodeToString(seq.getEncoded("DER"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("DER encoding failed", e);
        }
    }

    private static BigInteger importPrivateKey(String b64) {
        try {
            var seq = (org.bouncycastle.asn1.ASN1Sequence)
                    org.bouncycastle.asn1.ASN1Primitive.fromByteArray(Base64.getDecoder().decode(b64));
            return ((ASN1Integer) seq.getObjectAt(2)).getValue();
        } catch (Exception e) {
            return null;
        }
    }
}
