package com.tsvoice.client.ts3voice;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Ed25519 twisted Edwards 曲线（a=-1）点运算的最小实现（BigInteger 仿射坐标）。
 *
 * <p>移植自 TS3AudioBot/TSLib（Ref10 GroupOperations）中 TS3 协议所需的部分：
 * 点解码（ge_frombytes_negate_vartime 语义）、标量乘（ge_scalarmult_vartime /
 * ge_scalarmult_base）、点加（ge_add）、点编码（ge_p3_tobytes）。
 * 本协议路径不要求高性能，正确性优先。可用 RFC 8032 测试向量自检
 * （见 {@link #selfTest()}）。</p>
 */
public final class Ed25519Math {

    private Ed25519Math() {
    }

    /** p = 2^255 - 19 */
    public static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    /** d = -121665/121666 mod p */
    public static final BigInteger D = BigInteger.valueOf(-121665L)
            .multiply(BigInteger.valueOf(121666L).modInverse(P)).mod(P);
    /** sqrt(-1) mod p */
    private static final BigInteger SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);
    /** 基点（规范 x 坐标，偶数，与 RFC 8032 编码一致）。 */
    public static final Pt BASE = new Pt(
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"));

    /** 仿射点（x, y）。恒等元为 (0, 1)。 */
    public record Pt(BigInteger x, BigInteger y) {
    }

    /** ref10 sc_clamp：s[0]&=248; s[31]&=127; s[31]|=64（就地修改）。 */
    public static void scClamp(byte[] s) {
        s[0] &= (byte) 248;
        s[31] &= (byte) 127;
        s[31] |= (byte) 64;
    }

    /** 32 字节小端序标量 → BigInteger（无符号）。 */
    public static BigInteger scalarFromLe(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) {
            be[i] = le[le.length - 1 - i];
        }
        return new BigInteger(1, be);
    }

    /** 解码 32 字节 Ed25519 压缩点并取负（对应 ge_frombytes_negate_vartime）。 */
    public static Pt decodeNegate(byte[] enc) {
        Pt p = decode(enc);
        return new Pt(p.x().negate().mod(P), p.y());
    }

    /** 解码 32 字节 Ed25519 压缩点。失败抛 IllegalArgumentException。 */
    public static Pt decode(byte[] enc) {
        if (enc.length != 32) {
            throw new IllegalArgumentException("bad point length");
        }
        byte[] yBytes = enc.clone();
        boolean xSign = (yBytes[31] & 0x80) != 0;
        yBytes[31] &= (byte) 0x7F;
        BigInteger y = scalarFromLe(yBytes);
        if (y.compareTo(P) >= 0) {
            throw new IllegalArgumentException("y out of range");
        }
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        // x = sqrt(u/v)，(P+3)/8 指数开方 + sqrt(-1) 修正
        BigInteger vInverted = v.modPow(P.subtract(BigInteger.TWO), P);
        BigInteger c = u.multiply(vInverted).mod(P);
        BigInteger x = c.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P);
        if (!x.multiply(x).subtract(c).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(SQRT_M1).mod(P);
            if (!x.multiply(x).subtract(c).mod(P).equals(BigInteger.ZERO)) {
                throw new IllegalArgumentException("point not on curve");
            }
        }
        if (x.testBit(0) != xSign) {
            x = x.negate().mod(P);
        }
        if (x.equals(BigInteger.ZERO) && xSign) {
            throw new IllegalArgumentException("invalid sign");
        }
        return new Pt(x, y);
    }

    /** 编码点为 32 字节压缩形式（ge_p3_tobytes）。 */
    public static byte[] encode(Pt p) {
        byte[] out = new byte[32];
        BigInteger y = p.y();
        for (int i = 0; i < 32; i++) {
            out[i] = y.shiftRight(8 * i).byteValue();
        }
        if (p.x().testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /**
     * 倍点（a=-1 扭曲 Edwards）。
     * 由一般加法律 (x3=2xy/(1+d x²y²), y3=(x²+y²)/(1−d x²y²)) 代入曲线关系
     * d x²y² = y²−x²−1 化简而来。
     */
    public static Pt dbl(Pt p) {
        BigInteger xx = p.x().multiply(p.x()).mod(P);
        BigInteger yy = p.y().multiply(p.y()).mod(P);
        BigInteger denomX = yy.subtract(xx).mod(P);
        BigInteger denomY = BigInteger.TWO.add(xx).subtract(yy).mod(P);
        BigInteger x3 = p.x().multiply(p.y()).multiply(BigInteger.TWO).mod(P)
                .multiply(denomX.modPow(P.subtract(BigInteger.TWO), P)).mod(P);
        BigInteger y3 = xx.add(yy).mod(P)
                .multiply(denomY.modPow(P.subtract(BigInteger.TWO), P)).mod(P);
        return new Pt(x3, y3);
    }

    /** 点加（a=-1 扭曲 Edwards 加法律；a==b 走倍点优化，a==-b 由通用公式得到恒等元）。 */
    public static Pt add(Pt a, Pt b) {
        if (a.x().equals(b.x()) && a.y().equals(b.y())) {
            return dbl(a);
        }
        BigInteger numX = a.x().multiply(b.y()).add(a.y().multiply(b.x())).mod(P);
        BigInteger numY = a.y().multiply(b.y()).add(a.x().multiply(b.x())).mod(P);
        BigInteger denD = D.multiply(a.x()).multiply(b.x()).multiply(a.y()).multiply(b.y()).mod(P);
        BigInteger denX = BigInteger.ONE.add(denD).mod(P);
        BigInteger denY = BigInteger.ONE.subtract(denD).mod(P);
        return new Pt(
                numX.multiply(denX.modPow(P.subtract(BigInteger.TWO), P)).mod(P),
                numY.multiply(denY.modPow(P.subtract(BigInteger.TWO), P)).mod(P));
    }

    /** 变量基标量乘（double-and-add，MSB→LSB）。 */
    public static Pt scalarMult(BigInteger k, Pt p) {
        Pt r = new Pt(BigInteger.ZERO, BigInteger.ONE);
        int bits = k.bitLength();
        for (int i = bits - 1; i >= 0; i--) {
            r = dbl(r);
            if (k.testBit(i)) {
                r = add(r, p);
            }
        }
        return r;
    }

    /** 基点标量乘（ge_scalarmult_base）。 */
    public static Pt scalarMultBase(BigInteger k) {
        return scalarMult(k, BASE);
    }

    /** SHA-512。 */
    public static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * RFC 8032 测试向量 1 自检：
     * seed=9d61b19d…7f60 → a=clamp(sha512(seed)[0:32]) → pub=d75a9801…511a。
     * 验证 clamp / 标量乘 / 编码实现的正确性。
     */
    public static boolean selfTest() {
        byte[] seed = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] h = sha512(seed);
        byte[] a = java.util.Arrays.copyOf(h, 32);
        scClamp(a);
        byte[] pub = encode(scalarMultBase(scalarFromLe(a)));
        byte[] expect = hexToBytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        return java.util.Arrays.equals(pub, expect);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
