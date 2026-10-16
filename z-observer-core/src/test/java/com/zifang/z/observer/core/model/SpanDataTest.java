package com.zifang.z.observer.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanDataTest {

    private static SpanData.Builder base() {
        return SpanData.builder().traceId("t1").spanId("s1").startTimeMillis(100);
    }

    @Test
    void parentSpanId为空即根span() {
        assertTrue(base().parentSpanId(null).build().root());
        assertTrue(base().parentSpanId("").build().root());
        assertFalse(base().parentSpanId("s0").build().root());
    }

    @Test
    void 结束时间为零表示未结束() {
        assertEquals(0, base().build().durationMillis());
        assertEquals(50, base().endTimeMillis(150).build().durationMillis());
    }

    @Test
    void 采样但不记录是合法的_为了链路不断洞() {
        SpanData span = base().sampling(true, false).build();
        assertFalse(span.recorded());
    }

    @Test
    void 决定丢弃却仍在记录时拒掉() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> base().sampling(false, true).build());
        assertTrue(e.getMessage().contains("本该丢弃"));
    }

    @Test
    void 采样但不记录时不因后续recorded调用而误判() {
        // recorded(true) 在 sampling() 之后调用会覆盖决策结果；只要最终 recorded=true
        // 而采样决策是丢弃，就必须拒掉——否则「丢了一半」会以合法面目流出去。
        assertThrows(IllegalStateException.class,
                () -> base().sampling(false, false).recorded(true).build());
    }

    @Test
    void 不采样且不记录是正常的丢弃路径() {
        assertFalse(base().sampling(false, false).build().recorded());
    }

    @Test
    void traceId与spanId必填() {
        assertThrows(IllegalArgumentException.class, () -> SpanData.builder().spanId("s").build());
        assertThrows(IllegalArgumentException.class, () -> SpanData.builder().traceId("t").build());
    }

    @Test
    void 结束早于开始时拒掉() {
        assertThrows(IllegalArgumentException.class,
                () -> base().startTimeMillis(200).endTimeMillis(100).build());
    }

    @Test
    void attributes是不可变的() {
        SpanData span = base().attribute("k", "v").build();
        assertEquals("v", span.attributes().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> span.attributes().put("x", "y"));
    }
}
