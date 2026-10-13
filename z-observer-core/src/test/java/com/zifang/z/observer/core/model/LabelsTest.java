package com.zifang.z.observer.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelsTest {

    @Test
    void 按维度名排序让序列化结果稳定() {
        Labels labels = Labels.builder().put("b", "2").put("a", "1").build();
        assertEquals("a=1,b=2", labels.seriesKey());
    }

    @Test
    void 维度名或值为null时跳过而不是塞进去() {
        Labels labels = Labels.builder().put("a", null).put(null, "x").put("", "y").put("b", "1").build();
        assertEquals(1, labels.asMap().size());
        assertEquals("b=1", labels.seriesKey());
    }

    @Test
    void 空标签集的seriesKey是空串() {
        assertTrue(Labels.empty().isEmpty());
        assertEquals("", Labels.builder().build().seriesKey());
    }

    @Test
    void 标签值含分隔符时转义_否则不同标签集会算出同一个key() {
        String a = Labels.builder().put("k", "1,2").build().seriesKey();
        String b = Labels.builder().put("k", "1").put("2", "x").build().seriesKey();
        // 若不转义, 上者会变成 "k=1,2" 与后者 "k=1,2=x" 的歧义形态
        assertNotEquals(a, b);
        assertTrue(a.contains("\\"));
    }

    @Test
    void 相同标签集算出相同key() {
        assertEquals(
                Labels.builder().put("a", "1").put("b", "2").build().seriesKey(),
                Labels.builder().put("b", "2").put("a", "1").build().seriesKey());
    }

    @Test
    void 构建结果不可变() {
        Labels labels = Labels.builder().put("a", "1").build();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> labels.asMap().put("x", "y"));
    }
}
