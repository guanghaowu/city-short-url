package com.cityshorturl.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Base62Test {

    @Test
    @DisplayName("0 必须编码为 \"0\" 而不是空字符串（旧骨架的 bug）")
    void encodeZero() {
        assertEquals("0", Base62.encode(0));
    }

    @Test
    @DisplayName("encode / decode 互为逆运算")
    void roundTrip() {
        long[] samples = {0L, 1L, 61L, 62L, 63L, 3843L, 3844L,
                123456789L, 362719785500278784L, Long.MAX_VALUE};
        for (long n : samples) {
            String code = Base62.encode(n);
            assertEquals(n, Base62.decode(code), "往返失败: " + n + " -> " + code);
        }
    }

    @Test
    @DisplayName("实测：当前量级的雪花 ID 编码为 10 位（不是旧文档说的 6-8 位）")
    void shortCodeLengthIsTen() {
        // 2026-09 前后雪花 ID 的量级
        long snowflakeId = 362719785500278784L;
        String code = Base62.encode(snowflakeId);

        assertEquals(10, code.length(),
                "实际长度 " + code.length() + "，与文档宣称的 6-8 位不符");

        // 把边界也钉住，防止将来有人误改字符集或进制
        assertTrue(snowflakeId > Math.pow(62, 8), "该 ID 已超出 8 位 base62 上限");
        assertTrue(snowflakeId < Math.pow(62, 10), "该 ID 未超出 10 位 base62 上限");
    }

    @Test
    @DisplayName("字符集覆盖 62 个且不含需要 URL 转义的字符")
    void charsetIsUrlSafe() {
        String all = "";
        for (long i = 0; i < 62; i++) {
            all += Base62.encode(i);
        }
        assertEquals(62, all.chars().distinct().count());
        for (char c : "+/=".toCharArray()) {
            assertTrue(all.indexOf(c) < 0, "字符集不应包含 " + c);
        }
    }

    @Test
    @DisplayName("非法输入被拒绝")
    void rejectInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
        assertThrows(IllegalArgumentException.class, () -> Base62.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("abc-123"));
    }
}
