package com.tsvoice.client.ts3voice;

import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.EAXBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TS3 语音协议全部加密原语的移植（源：TSLib TsCrypt.cs）。
 *
 * <ul>
 *     <li>密钥协商：P-256 ECDH（omega 公钥 × 我方私钥 → sha1 → 与 alpha/beta 异或成 ivStruct）；</li>
 *     <li>包加密：AES-EAX（每 包类型/方向/代 派生 key+nonce，包 id 混入 key 前 2 字节）；</li>
 *     <li>init 握手：init0→1→2→3(RSA 谜题 x^(2^level) mod n + 内嵌 clientinitiv)→4；</li>
 *     <li>身份：P-256（{@link Ts3Identity}）；</li>
 *     <li>新版握手（license 链）：Ed25519 点运算派生密钥（{@link Ed25519Math}）。</li>
 * </ul>
 */
public final class Ts3Crypt {

    public static final int MAC_LEN = 8;
    public static final int PACKET_TYPE_KINDS = 9;
    /** init 包使用的固定版本（3.5.0）。 */
    public static final long INIT_VERSION = 1566914096L;

    private static final String DUMMY_KEY_AND_NONCE = "c:\\windows\\system\\firewall32.cpl";
    private static final byte[] DUMMY_KEY = DUMMY_KEY_AND_NONCE.substring(0, 16).getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DUMMY_IV = DUMMY_KEY_AND_NONCE.substring(16).getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TS3_INIT_MAC = "TS3INIT1".getBytes(StandardCharsets.US_ASCII);

    private final Ts3Identity identity;
    private final EAXBlockCipher eaxCipher = new EAXBlockCipher(new AESEngine());
    private final SecureRandom random = new SecureRandom();

    private volatile boolean cryptoInitComplete = false;
    private byte[] alphaTmp;
    private byte[] ivStruct;
    private final byte[] fakeSignature = new byte[MAC_LEN];
    /** [方向][包类型][代] → key/nonce。解密（接收线程）与加密（发送锁内）并发访问。 */
    @SuppressWarnings("unchecked")
    private final java.util.concurrent.ConcurrentHashMap<Integer, KeyNonce>[] keyNonceCache =
            new java.util.concurrent.ConcurrentHashMap[PACKET_TYPE_KINDS * 2];

    public Ts3Crypt(Ts3Identity identity) {
        this.identity = identity;
        for (int i = 0; i < keyNonceCache.length; i++) {
            keyNonceCache[i] = new java.util.concurrent.ConcurrentHashMap<>();
        }
    }

    public boolean isCryptoInitComplete() {
        return cryptoInitComplete;
    }

    public Ts3Identity getIdentity() {
        return identity;
    }

    public void reset() {
        cryptoInitComplete = false;
        ivStruct = null;
        java.util.Arrays.fill(fakeSignature, (byte) 0);
        for (java.util.Map<Integer, KeyNonce> m : keyNonceCache) {
            m.clear();
        }
    }

    // ---- 旧版 clientinitiv 握手（initiv alpha/beta/omega） ----

    /**
     * 处理服务器的 initiv 命令响应（旧协议）。
     *
     * @param alpha 我方 alpha（服务器回显，base64）
     * @param beta  服务器 beta（base64，10 或 54 字节）
     * @param omega 服务器 ECDH 公钥（base64 DER）
     */
    public String cryptoInit(String alpha, String beta, String omega) {
        byte[] alphaBytes = b64(alpha);
        if (alphaBytes == null || alphaBytes.length != 10) {
            return "alpha parameter is invalid";
        }
        byte[] betaBytes = b64(beta);
        if (betaBytes == null) {
            return "beta parameter is invalid";
        }
        byte[] omegaBytes = b64(omega);
        if (omegaBytes == null) {
            return "omega parameter is invalid";
        }
        ECPoint serverPublicKey = importPublicKey(omegaBytes);
        if (serverPublicKey == null) {
            return "server public key is invalid";
        }
        byte[] sharedKey = getSharedSecret(serverPublicKey);
        return setSharedSecret(alphaBytes, betaBytes, sharedKey);
    }

    /** ECDH(服务器公钥, 我方私钥) → x 坐标（左填充 32B）→ sha1。 */
    private byte[] getSharedSecret(ECPoint serverPublicKey) {
        ECPoint p = serverPublicKey.multiply(identity.getPrivateKey()).normalize();
        BigInteger x = p.getAffineXCoord().toBigInteger();
        byte[] keyArr = x.toByteArray(); // 带符号（可能多一个 0 前导字节）
        if (keyArr.length == 32) {
            return sha1(keyArr);
        }
        if (keyArr.length > 32) {
            return sha1(keyArr, keyArr.length - 32, 32);
        }
        byte[] ext = new byte[32];
        System.arraycopy(keyArr, 0, ext, 32 - keyArr.length, keyArr.length);
        return sha1(ext);
    }

    private String setSharedSecret(byte[] alpha, byte[] beta, byte[] sharedKey) {
        if (beta.length != 10 && beta.length != 54) {
            return "Invalid beta size (" + beta.length + ")";
        }
        // ivStruct[0..10)  = sharedKey[0..10)  ^ alpha
        // ivStruct[10..)   = sharedKey[10..)   ^ beta
        // 旧协议：beta=10 → ivStruct=20（shared=sha1 20B）；新协议：beta=54 → ivStruct=64（shared=sha512 64B）
        if (sharedKey.length < 10 + beta.length) {
            return "Shared secret too short (" + sharedKey.length + "B) for beta size " + beta.length;
        }
        ivStruct = new byte[10 + beta.length];
        xorBinary(sharedKey, alpha, alpha.length, ivStruct, 0);
        for (int i = 0; i < beta.length; i++) {
            ivStruct[10 + i] = (byte) (sharedKey[10 + i] ^ beta[i]);
        }
        byte[] buffer2 = sha1(ivStruct);
        System.arraycopy(buffer2, 0, fakeSignature, 0, MAC_LEN);
        alphaTmp = null;
        cryptoInitComplete = true;
        return null;
    }

    // ---- 新版 clientinitiv 握手（license 链） ----

    /**
     * 处理新版 initiv（带 license/proof/beta）。
     *
     * @param privateKey 临时 Ed25519 私钥标量（32 字节，由 {@link #generateTemporaryKey()} 生成）
     */
    public String cryptoInit2(String license, String omega, String proof, String beta, byte[] privateKey) {
        byte[] licenseBytes = b64(license);
        if (licenseBytes == null) {
            return "license parameter is invalid";
        }
        byte[] omegaBytes = b64(omega);
        if (omegaBytes == null) {
            return "omega parameter is invalid";
        }
        byte[] proofBytes = b64(proof);
        if (proofBytes == null) {
            return "proof parameter is invalid";
        }
        byte[] betaBytes = b64(beta);
        if (betaBytes == null) {
            return "beta parameter is invalid";
        }
        ECPoint serverPublicKey = importPublicKey(omegaBytes);
        if (serverPublicKey == null) {
            return "server public key is invalid";
        }
        // 验证 license 链未被篡改（ECDSA-P256 over license by omega key）
        if (!verifySign(serverPublicKey, licenseBytes, proofBytes)) {
            return "The init proof is not valid";
        }
        Licenses licenseChain;
        try {
            licenseChain = Licenses.parse(licenseBytes);
        } catch (Exception e) {
            return "Failed to parse license: " + e.getMessage();
        }
        byte[] key;
        byte[] sharedKey;
        try {
            key = licenseChain.deriveKey();
            sharedKey = getSharedSecret2(key, privateKey);
        } catch (RuntimeException e) {
            return "Failed to derive shared secret: " + e.getMessage();
        }
        if (alphaTmp == null) {
            return "alpha is missing (internal error)";
        }
        return setSharedSecret(alphaTmp, betaBytes, sharedKey);
    }

    /** Ed25519 点运算的共享密钥：sha512(encode([clamped_priv]decode_negate(licenseKey)) ^ 0x80)。 */
    private static byte[] getSharedSecret2(byte[] publicKey, byte[] privateKey) {
        byte[] priv = privateKey.clone();
        Ed25519Math.scClamp(priv);
        Ed25519Math.Pt pub1 = Ed25519Math.decodeNegate(publicKey);
        Ed25519Math.Pt mul = Ed25519Math.scalarMult(Ed25519Math.scalarFromLe(priv), pub1);
        byte[] sharedTmp = Ed25519Math.encode(mul);
        sharedTmp[31] ^= (byte) 0x80;
        return Ed25519Math.sha512(sharedTmp);
    }

    /** 生成临时 Ed25519 密钥对（新版握手 clientek 用）。 */
    public static byte[][] generateTemporaryKey() {
        byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);
        Ed25519Math.scClamp(privateKey);
        byte[] publicKey = Ed25519Math.encode(
                Ed25519Math.scalarMultBase(Ed25519Math.scalarFromLe(privateKey)));
        return new byte[][]{publicKey, privateKey};
    }

    // ---- init1 握手状态机 ----

    /**
     * init1 包状态机（仅客户端方向）。
     *
     * @param data 服务器发来的 init1 载荷；null 表示发起首次 init0
     * @return 应发送的 init1 载荷；返回长度 0 表示握手完成；抛 IOException 表示失败
     */
    public byte[] processInit1(byte[] data) throws Ts3VoiceException {
        final int versionLen = 4;
        final int initTypeLen = 1;

        if (data == null) {
            return buildInit0();
        }
        if (data.length < 1) {
            throw new Ts3VoiceException("Invalid Init1 packet (too short)");
        }
        int type = data[0] & 0xFF;
        if (type != 1 && type != 3 && type != 0x7F) {
            throw new Ts3VoiceException("Invalid Init1 step " + type);
        }

        if (type == 0x7F) {
            // 某些奇怪的服务器行为：重新开始
            return buildInit0();
        }

        switch (type) {
            case 1: {
                if (data.length == 5) {
                    // init 错误包：[1][errorcode 4LE]
                    long err = readUInt32LE(data, 1);
                    throw new Ts3VoiceException("Got Init1 error code " + err);
                }
                if (data.length == 21) {
                    byte[] sendData = new byte[versionLen + initTypeLen + 16 + 4];
                    writeUInt32BE(sendData, 0, INIT_VERSION);
                    sendData[versionLen] = 0x02;
                    System.arraycopy(data, initTypeLen, sendData, versionLen + initTypeLen, 20);
                    return sendData;
                }
                throw new Ts3VoiceException("Invalid Init1(1) length " + data.length);
            }
            case 3: {
                if (data.length != initTypeLen + 64 + 64 + 4 + 100) {
                    throw new Ts3VoiceException("Invalid Init1(3) length " + data.length);
                }
                alphaTmp = new byte[10];
                random.nextBytes(alphaTmp);
                String alpha = Base64.getEncoder().encodeToString(alphaTmp);
                String initAdd = "clientinitiv alpha=" + alpha
                        + " omega=" + identity.getPublicKeyString()
                        + " ot=1 ip=";

                // RSA 谜题：y = x^(2^level) mod n
                int level = readInt32BE(data, initTypeLen + 128);
                byte[] y = solveRsaChallenge(data, initTypeLen, level);

                byte[] textBytes = initAdd.getBytes(StandardCharsets.UTF_8);
                byte[] sendData = new byte[versionLen + initTypeLen + 64 + 64 + 4 + 100 + 64 + textBytes.length];
                writeUInt32BE(sendData, 0, INIT_VERSION);
                sendData[versionLen] = 0x04;
                System.arraycopy(data, initTypeLen, sendData, versionLen + initTypeLen, 232);
                // Y occupies the 64 bytes immediately after the 232-byte server echo.
                if (y.length != 64) {
                    throw new Ts3VoiceException("RSA challenge result must be exactly 64 bytes");
                }
                System.arraycopy(y, 0, sendData, versionLen + initTypeLen + 232, 64);
                System.arraycopy(textBytes, 0, sendData, versionLen + initTypeLen + 232 + 64, textBytes.length);
                return sendData;
            }
            case 4: {
                // 最终 ack（TODO 校验结果），握手完成
                return new byte[0];
            }
            default:
                throw new Ts3VoiceException("Got invalid Init1(" + type + ") packet id");
        }
    }

    private byte[] buildInit0() {
        byte[] sendData = new byte[4 + 1 + 4 + 4 + 8];
        writeUInt32BE(sendData, 0, INIT_VERSION);
        sendData[4] = 0x00;
        writeUInt32BE(sendData, 5, System.currentTimeMillis() / 1000L);
        writeInt32BE(sendData, 9, random.nextInt());
        return sendData;
    }

    /**
     * x^(2^level) mod n（服务器 RSA 谜题）。
     */
    private static byte[] solveRsaChallenge(byte[] data, int offset, int level) throws Ts3VoiceException {
        if (level < 0 || level > 1_000_000) {
            throw new Ts3VoiceException("RSA challenge level is not within an acceptable range");
        }
        BigInteger x = new BigInteger(1, data, offset, 64);
        BigInteger n = new BigInteger(1, data, 64 + offset, 64);
        try {
            return com.tsvoice.protocol.UnsignedIntegers.toFixedWidth(
                    x.modPow(BigInteger.TWO.pow(level), n), 64);
        } catch (IllegalArgumentException e) {
            throw new Ts3VoiceException("RSA challenge result is invalid", e);
        }
    }

    // ---- 加密/解密 ----

    /**
     * 加密出站包。packet.raw 布局：[MAC(8)][header][data]。
     */
    public void encrypt(Ts3Packet packet) {
        if (packet.packetType() == PacketType.INIT1) {
            fakeEncrypt(packet, TS3_INIT_MAC);
            return;
        }
        if (packet.unencryptedFlag()) {
            fakeEncrypt(packet, fakeSignature);
            return;
        }
        KeyNonce kn = getKeyNonce(packet.fromServer, packet.packetId, packet.generationId, packet.packetType(),
                !cryptoInitComplete);
        packet.buildHeader();
        CipherParameters params = new AEADParameters(new KeyParameter(kn.key), 8 * MAC_LEN, kn.nonce, packet.header);
        byte[] result;
        int len;
        try {
            synchronized (eaxCipher) {
                eaxCipher.init(true, params);
                result = new byte[eaxCipher.getOutputSize(packet.data.length)];
                len = eaxCipher.processBytes(packet.data, 0, packet.data.length, result, 0);
                len += eaxCipher.doFinal(result, len);
            }
        } catch (org.bouncycastle.crypto.InvalidCipherTextException e) {
            throw new IllegalStateException("Internal encryption error", e);
        }
        // result = [data..., mac...] → 重排为 [mac..., header..., data...]
        System.arraycopy(result, len - MAC_LEN, packet.raw, 0, MAC_LEN);
        System.arraycopy(packet.header, 0, packet.raw, MAC_LEN, packet.headerLength);
        System.arraycopy(result, 0, packet.raw, MAC_LEN + packet.headerLength, len - MAC_LEN);
    }

    /** 解密入站包。成功时 packet.data 被填充。 */
    public boolean decrypt(Ts3Packet packet) {
        if (packet.packetType() == PacketType.INIT1) {
            return fakeDecrypt(packet, TS3_INIT_MAC);
        }
        if (packet.unencryptedFlag()) {
            return fakeDecrypt(packet, fakeSignature);
        }
        if (decryptData(packet, !cryptoInitComplete)) {
            return true;
        }
        // 特殊 ack 的键不确定（clientek=1 dummy / clientinit=2 session），换 dummy 键重试
        if (packet.packetType() == PacketType.ACK && packet.packetId <= 2) {
            return decryptData(packet, true);
        }
        return false;
    }

    private boolean decryptData(Ts3Packet packet, boolean dummyEncryption) {
        System.arraycopy(packet.raw, MAC_LEN, packet.header, 0, packet.headerLength);
        KeyNonce kn = getKeyNonce(packet.fromServer, packet.packetId, packet.generationId, packet.packetType(),
                dummyEncryption);
        int dataLen = packet.raw.length - (MAC_LEN + packet.headerLength);
        CipherParameters params = new AEADParameters(new KeyParameter(kn.key), 8 * MAC_LEN, kn.nonce, packet.header);
        try {
            byte[] result;
            int len;
            synchronized (eaxCipher) {
                eaxCipher.init(false, params);
                result = new byte[eaxCipher.getOutputSize(dataLen + MAC_LEN)];
                len = eaxCipher.processBytes(packet.raw, MAC_LEN + packet.headerLength, dataLen, result, 0);
                len += eaxCipher.processBytes(packet.raw, 0, MAC_LEN, result, len);
                len += eaxCipher.doFinal(result, len);
                if (len != dataLen) {
                    return false;
                }
            }
            packet.data = result;
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private static void fakeEncrypt(Ts3Packet packet, byte[] mac) {
        System.arraycopy(mac, 0, packet.raw, 0, MAC_LEN);
        packet.buildHeader(packet.raw, MAC_LEN);
        System.arraycopy(packet.data, 0, packet.raw, MAC_LEN + packet.headerLength, packet.data.length);
    }

    private static boolean fakeDecrypt(Ts3Packet packet, byte[] mac) {
        if (packet.raw.length < MAC_LEN) {
            return false;
        }
        for (int i = 0; i < MAC_LEN; i++) {
            if (packet.raw[i] != mac[i]) {
                return false;
            }
        }
        int dataLen = packet.raw.length - (MAC_LEN + packet.headerLength);
        packet.data = new byte[dataLen];
        System.arraycopy(packet.raw, MAC_LEN + packet.headerLength, packet.data, 0, dataLen);
        return true;
    }

    /** 每 包类型/方向/代 派生 key+nonce（sha256），包 id 混入 key 前 2 字节。 */
    private KeyNonce getKeyNonce(boolean fromServer, int packetId, long generationId, PacketType packetType,
                                 boolean dummyEncryption) {
        if (dummyEncryption) {
            return new KeyNonce(DUMMY_KEY, DUMMY_IV);
        }
        byte packetTypeRaw = (byte) packetType.value;
        // 与 TSLib 一致：方向 × 包类型 各占独立槽位（防止不同方向/类型的代际缓存互相污染）
        int cacheIndex = packetTypeRaw * 2 + (fromServer ? 1 : 0);
        java.util.concurrent.ConcurrentHashMap<Integer, KeyNonce> byGen = keyNonceCache[cacheIndex];
        KeyNonce cacheValue = byGen.get((int) generationId);
        if (cacheValue == null) {
            byte[] tmpToHash = new byte[ivStruct.length == 20 ? 26 : 70];
            tmpToHash[0] = (byte) (fromServer ? 0x30 : 0x31);
            tmpToHash[1] = packetTypeRaw;
            writeUInt32BE(tmpToHash, 2, generationId);
            System.arraycopy(ivStruct, 0, tmpToHash, 6, ivStruct.length);
            byte[] result = sha256(tmpToHash);
            cacheValue = new KeyNonce(
                    java.util.Arrays.copyOfRange(result, 0, 16),
                    java.util.Arrays.copyOfRange(result, 16, 32));
            KeyNonce prev = byGen.putIfAbsent((int) generationId, cacheValue);
            if (prev != null) {
                cacheValue = prev;
            }
        }
        byte[] key = cacheValue.key.clone();
        byte[] nonce = cacheValue.nonce.clone();
        key[0] ^= (byte) (packetId >> 8);
        key[1] ^= (byte) packetId;
        return new KeyNonce(key, nonce);
    }

    // ---- license 链（新版握手） ----

    /** TS3 license 链解析 + 密钥派生（源：TSLib License.cs）。 */
    public static final class Licenses {
        private static final byte[] LICENSE_ROOT_KEY = hex(
                "cd0de2aed46345509a7e3cfd8f68b3dc7555b29dccec73cd18750f993812408a");

        final java.util.List<LicenseBlock> blocks = new java.util.ArrayList<>();

        static Licenses parse(byte[] data) throws Ts3VoiceException {
            if (data.length < 1) {
                throw new Ts3VoiceException("License too short");
            }
            if (data[0] != 1) {
                throw new Ts3VoiceException("Unsupported license version");
            }
            Licenses res = new Licenses();
            int pos = 1;
            while (pos < data.length) {
                pos += LicenseBlock.parse(data, pos, res.blocks);
            }
            return res;
        }

        byte[] deriveKey() {
            byte[] round = LICENSE_ROOT_KEY;
            for (LicenseBlock block : blocks) {
                round = block.deriveKey(round);
            }
            return round;
        }

        private static byte[] hex(String hex) {
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
    }

    private static final class LicenseBlock {
        final byte[] key;
        final byte[] hash;

        private LicenseBlock(byte[] key, byte[] hash) {
            this.key = key;
            this.hash = hash;
        }

        /** 解析单个 license 块，返回消耗的字节数。 */
        static int parse(byte[] data, int off, java.util.List<LicenseBlock> out) throws Ts3VoiceException {
            final int minBlockLen = 42;
            if (data.length - off < minBlockLen) {
                throw new Ts3VoiceException("License too short");
            }
            if (data[off] != 0) {
                throw new Ts3VoiceException("Wrong key kind in license");
            }
            int type = data[off + 33] & 0xFF;
            int read;
            switch (type) {
                case 0 -> { // intermediate: null 结尾 issuer @46
                    int strEnd = findNull(data, off + 46);
                    read = 5 + (strEnd - (off + 46));
                }
                case 2 -> { // server: license type @42, issuer @47
                    int strEnd = findNull(data, off + 47);
                    read = 6 + (strEnd - (off + 47));
                }
                case 32 -> read = 0; // ephemeral
                default -> throw new Ts3VoiceException("Invalid license block type " + type);
            }
            byte[] key = java.util.Arrays.copyOfRange(data, off + 1, off + 33);
            byte[] all = java.util.Arrays.copyOfRange(data, off + 1, off + minBlockLen + read);
            byte[] hash = java.util.Arrays.copyOf(sha512(all), 32);
            out.add(new LicenseBlock(key, hash));
            return minBlockLen + read;
        }

        private static int findNull(byte[] data, int from) throws Ts3VoiceException {
            for (int i = from; i < data.length; i++) {
                if (data[i] == 0) {
                    return i;
                }
            }
            throw new Ts3VoiceException("Non-null-terminated issuer string");
        }

        /**
         * new_pub = hash·Key_pub + parent（Ed25519 点运算），结果翻转最高位。
         */
        byte[] deriveKey(byte[] parent) {
            byte[] h = hash.clone();
            Ed25519Math.scClamp(h);
            Ed25519Math.Pt pubkey = Ed25519Math.decodeNegate(key);
            Ed25519Math.Pt parkey = Ed25519Math.decodeNegate(parent);
            Ed25519Math.Pt res = Ed25519Math.scalarMult(Ed25519Math.scalarFromLe(h), pubkey);
            Ed25519Math.Pt sum = Ed25519Math.add(res, parkey);
            byte[] finalKey = Ed25519Math.encode(sum);
            finalKey[31] ^= (byte) 0x80;
            return finalKey;
        }
    }

    // ---- 工具 ----

    private static ECPoint importPublicKey(byte[] asnByteArray) {
        try {
            var asnKeyData = (ASN1Sequence) ASN1Primitive.fromByteArray(asnByteArray);
            BigInteger x = ((ASN1Integer) asnKeyData.getObjectAt(2)).getValue();
            BigInteger y = ((ASN1Integer) asnKeyData.getObjectAt(3)).getValue();
            var curve = ECNamedCurveTable.getByName("prime256v1").getCurve();
            var point = curve.validatePoint(x, y);
            if (point.isInfinity()) {
                return null;
            }
            return point;
        } catch (Exception e) {
            Ts3VoiceClient.LOG.warn("[teamspeak-svc] Failed to import server public key: {}", e.toString());
            return null;
        }
    }

    /** ECDSA-P256 验签（license proof）。 */
    private static boolean verifySign(ECPoint publicKey, byte[] data, byte[] proof) {
        try {
            var signer = new org.bouncycastle.crypto.signers.ECDSASigner();
            var curve = ECNamedCurveTable.getByName("prime256v1");
            var domain = new org.bouncycastle.crypto.params.ECDomainParameters(
                    curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
            signer.init(false, new org.bouncycastle.crypto.params.ECPublicKeyParameters(publicKey, domain));
            BigInteger[] sig = decodeDerOrRaw(proof);
            return signer.verifySignature(sha256(data), sig[0], sig[1]);
        } catch (Exception e) {
            return false;
        }
    }

    /** ECDSA 签名兼容：支持 DER 序列或原始 r||s 64 字节。 */
    private static BigInteger[] decodeDerOrRaw(byte[] proof) throws Exception {
        if (proof.length == 64) {
            return new BigInteger[]{
                    new BigInteger(1, java.util.Arrays.copyOfRange(proof, 0, 32)),
                    new BigInteger(1, java.util.Arrays.copyOfRange(proof, 32, 64))};
        }
        var seq = (ASN1Sequence) ASN1Primitive.fromByteArray(proof);
        return new BigInteger[]{
                ((ASN1Integer) seq.getObjectAt(0)).getValue(),
                ((ASN1Integer) seq.getObjectAt(1)).getValue()};
    }

    private static void xorBinary(byte[] a, byte[] b, int len, byte[] out, int outOff) {
        for (int i = 0; i < len; i++) {
            out[outOff + i] = (byte) (a[i] ^ b[i]);
        }
    }

    static byte[] sha1(byte[] data) {
        return hash("SHA-1", data, 0, data.length);
    }

    static byte[] sha1(byte[] data, int offset, int len) {
        return hash("SHA-1", data, offset, len);
    }

    static byte[] sha256(byte[] data) {
        return hash("SHA-256", data, 0, data.length);
    }

    static byte[] sha512(byte[] data) {
        return hash("SHA-512", data, 0, data.length);
    }

    private static byte[] hash(String algo, byte[] data, int offset, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            md.update(data, offset, len);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] b64(String str) {
        if (str == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void writeUInt32BE(byte[] buf, int off, long value) {
        buf[off] = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }

    static void writeInt32BE(byte[] buf, int off, int value) {
        buf[off] = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }

    static int readInt32BE(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }

    static long readUInt32LE(byte[] buf, int off) {
        return ((buf[off] & 0xFFL)) | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16) | ((buf[off + 3] & 0xFFL) << 24);
    }

    /** key/nonce 对。 */
    private record KeyNonce(byte[] key, byte[] nonce) {
    }
}
