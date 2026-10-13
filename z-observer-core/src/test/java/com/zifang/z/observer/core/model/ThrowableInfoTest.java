package com.zifang.z.observer.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ThrowableInfoTest {

    @Test
    void 提取类型消息与栈() {
        Exception e = new IllegalStateException("boom");
        ThrowableInfo info = ThrowableInfo.from(e);
        assertEquals("java.lang.IllegalStateException", info.type());
        assertEquals("boom", info.message());
        assertNotNull(info.stackFrames());
        assertEquals(true, info.stackFrames().length > 0);
    }

    @Test
    void cause链会被提取() {
        ThrowableInfo info = ThrowableInfo.from(new RuntimeException("outer", new IllegalArgumentException("inner")));
        assertNotNull(info.cause());
        assertEquals("inner", info.cause().message());
    }

    @Test
    void 互为cause的环按深度上限截断() {
        // JDK 的 initCause 禁止自引用（Self-causation not permitted），所以现实里能造出的
        // 最短环是两元素：A 的 cause 是 B，B 的 cause 是 A。这必须靠深度上限兜住，
        // 否则上报异常时会把栈展开成死循环，把上报线程和整个收端一起拖死。
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        assertEquals(ThrowableInfo.MAX_CAUSE_DEPTH, depth(ThrowableInfo.from(a)));
    }

    @Test
    void 深度上限不会误伤正常因果链() {
        Throwable root = new IllegalStateException("root");
        Throwable t = root;
        for (int i = 0; i < ThrowableInfo.MAX_CAUSE_DEPTH - 1; i++) {
            t = new RuntimeException("level" + i, t);
        }
        // 共 MAX_CAUSE_DEPTH 层（含根），一条不少
        assertEquals(ThrowableInfo.MAX_CAUSE_DEPTH, depth(ThrowableInfo.from(t)));
    }

    @Test
    void null异常返回null() {
        assertNull(ThrowableInfo.from(null));
    }

    private static int depth(ThrowableInfo info) {
        int d = 0;
        while (info != null) {
            d++;
            info = info.cause();
        }
        return d;
    }
}
