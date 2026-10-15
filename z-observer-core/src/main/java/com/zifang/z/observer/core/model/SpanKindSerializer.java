package com.zifang.z.observer.core.model;

import com.zifang.util.json.serializer.ValueSerializer;

/**
 * 把 {@link SpanData.SpanKind} 序列化成枚举名字符串。
 *
 * <p>存在的唯一理由：JsonUtil 的序列化分派里<b>没有枚举分支</b>，枚举值会掉进
 * 反射 POJO 那条路，把 {@code java.lang.Enum} 的私有 name/ordinal 当字段吐成
 * {@code {"name":null,"ordinal":null}}（实测 z-util-parser-json 1.2.1）。反序列化侧
 * JsonUtil 原生认字符串，所以只有出网这一半需要点名。
 *
 * <p>报文形态与 Jackson 一致（就是枚举常量名），两侧不因此错位。
 */
public final class SpanKindSerializer implements ValueSerializer {

    @Override
    public String serialize(Object value, String format) {
        if (value == null) {
            return "null";
        }
        return "\"" + ((SpanData.SpanKind) value).name() + "\"";
    }
}
