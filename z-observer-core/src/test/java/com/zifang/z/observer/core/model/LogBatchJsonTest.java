package com.zifang.z.observer.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 上报 body 走的是普通 JSON 序列化的批次对象，这一组钉住「序列化和反序列化都能走通」。 */
class LogBatchJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 批次序列化成JSON后能原样读回来() throws Exception {
        LogEvent event = LogEvent.builder()
                .timestampMillis(1700000000000L)
                .level("ERROR")
                .logger("com.demo.Foo")
                .thread("main")
                .message("订单创建失败 orderId=42")
                .traceId("t-1")
                .build();
        LogBatch batch = LogBatch.of("inst-1", Collections.singletonList(event));

        String json = mapper.writeValueAsString(batch);
        LogBatch back = mapper.readValue(json, LogBatch.class);

        assertEquals("inst-1", back.instanceId());
        assertEquals(1, back.size());
        LogEvent e = back.events().get(0);
        assertEquals("ERROR", e.level());
        assertEquals("订单创建失败 orderId=42", e.message());
        assertEquals(1700000000000L, e.timestampMillis());
    }

    @Test
    void 缺字段的批次读回来是null而不是崩() throws Exception {
        LogBatch back = mapper.readValue("{\"events\":[{\"message\":\"x\"}]}", LogBatch.class);
        assertNotNull(back.events());
        assertEquals("x", back.events().get(0).message());
    }

    @Test
    void span批次能过网且父子关系不丢() throws Exception {
        SpanData span = SpanData.builder()
                .traceId("t-1")
                .spanId("s-2")
                .parentSpanId("s-1")
                .name("order/create")
                .startTimeMillis(1700000000000L)
                .endTimeMillis(1700000000120L)
                .kind(SpanData.SpanKind.SERVER)
                .statusCode("ERROR")
                .attributes(java.util.Collections.singletonMap("orderId", "42"))
                .build();

        String json = mapper.writeValueAsString(SpanBatch.of("inst-1",
                java.util.Collections.singletonList(span)));
        SpanBatch back = mapper.readValue(json, SpanBatch.class);

        assertEquals(1, back.size());
        SpanData s = back.spans().get(0);
        assertEquals("s-1", s.parentSpanId());
        assertEquals(SpanData.SpanKind.SERVER, s.kind());
        assertEquals(120L, s.durationMillis());
        assertEquals("42", s.attributes().get("orderId"));
        assertEquals("ERROR", s.statusCode());
    }

    @Test
    void 异常链单独过网不丢栈() throws Exception {
        ThrowableInfo cause = new ThrowableInfo("java.lang.IllegalStateException", "底层坏了",
                new String[]{"com.demo.Boom.run(Boom.java:9)"}, null);
        String json = mapper.writeValueAsString(cause);
        ThrowableInfo back = mapper.readValue(json, ThrowableInfo.class);
        assertEquals("java.lang.IllegalStateException", back.type());
        assertEquals(1, back.stackFrames().length);
        assertEquals("com.demo.Boom.run(Boom.java:9)", back.stackFrames()[0]);
    }
}
