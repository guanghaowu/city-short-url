package com.cityshorturl.util;

/**
 * Base62 编解码。
 *
 * <h3>为什么用 62 进制而不是 64 进制</h3>
 * URL 路径里 {@code + / =} 需要转义，而 {@code 0-9A-Za-z} 这 62 个字符全部安全。
 * 进制越大短码越短，但字符集必须对 URL 友好。
 *
 * <h3>⚠ 短码实际是 10 位，不是 6-8 位（实测数据，别被旧文档误导）</h3>
 * 雪花 ID 在当前时间点约 {@code 3.6e17}，而各长度 base62 的上限是：
 * <pre>
 *   6 位 = 5.68e10     ← 差 6 个数量级
 *   8 位 = 2.18e14     ← 仍差 3 个数量级
 *  10 位 = 8.39e17     ← 实际落在这里
 * </pre>
 * 根因：雪花 41 位时间戳，2024-01-01 到 2026-09 已过去 8.6e10 ms，
 * 左移 22 位后就是 3.6e17 —— 这个量级决定了 base62 至少要 10 位。
 * <b>雪花算法本质上做不到 6-8 位短码</b>，要更短只能改用自增序列（号段模式）。
 *
 * <p>按当前 EPOCH，10 位可稳定用到约 2030 年（62^10 对应 6.34 年），之后变 11 位。
 *
 * <h3>已知不足</h3>
 * 字符集包含 {@code 0/O}、{@code 1/l/I} 等易混淆字符。生产环境通常改用 58 进制
 * 去掉这些字符，代价是同样长度可表示的 ID 空间变小。
 */
public final class Base62 {

    private static final String CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = CHARS.length();

    private Base62() {
    }

    /**
     * 数值 → base62 字符串。
     *
     * <p>注意 num=0 必须单独处理：循环条件是 {@code num > 0}，
     * 若不加这个分支会返回<b>空字符串</b>（旧文档里的骨架就有这个 bug）。
     */
    public static String encode(long num) {
        if (num < 0) {
            throw new IllegalArgumentException("base62 只支持非负数: " + num);
        }
        if (num == 0) {
            return String.valueOf(CHARS.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.append(CHARS.charAt((int) (num % BASE)));
            num /= BASE;
        }
        return sb.reverse().toString();
    }

    /** base62 字符串 → 数值。跳转链路用不到（直接拿 shortCode 查映射），这里提供是为了单测能验证互逆。 */
    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("短码不能为空");
        }
        long num = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = CHARS.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("非法 base62 字符: " + code.charAt(i));
            }
            num = num * BASE + digit;
        }
        return num;
    }
}
