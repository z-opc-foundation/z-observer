package com.zifang.z.observer.starter.schedule;

import com.zifang.util.json.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * z-schedule 任务执行日志 → 指标事件的解析。
 *
 * <p><b>z-schedule 侧零源码改动</b>是这张卡的一条硬口径，所以边车只能读「已经存在的东西」。
 * 任务执行日志就是那样东西：z-schedule 每执行一次任务就落一行 {@code JobLog}
 * （调度时间、执行结果码、执行器、处理器），而它的管理面把这批行原样吐成 JSON
 * （{@code GET /api/schedule/joblog/list}）。
 * 边车读那一行，就等于复用了「任务执行日志这个现成钩子」。
 *
 * <p><b>为什么不是「改 z-schedule 加个上报钩子」</b>：那会让每个域的调度仓都依赖观测 SDK，
 * 而短生命周期批任务本来就不该为了被观测而改代码 —— 边车的价值就在于零侵入。
 *
 * <p>口径（与 z-schedule 侧一致，不自己发明）：
 * <ul>
 *   <li>成功：{@code handleCode} 为 0 或 200；其余（500/502/404/503/400…）算失败；</li>
 *   <li>调度本身失败（{@code triggerCode} 非 0）也算失败 —— 任务压根没跑起来，
 *       「没跑起来」和「跑失败了」对值班的人是一回事：任务没成功。</li>
 * </ul>
 */
public final class ScheduleJobEventParser {

    /** 一次任务执行（来自 z-schedule 的一行调度日志）。 */
    public static final class JobEvent {

        private final long id;
        private final String job;
        private final boolean success;
        private final long timestampMillis;

        public JobEvent(long id, String job, boolean success, long timestampMillis) {
            this.id = id;
            this.job = job == null || job.isEmpty() ? ("job-" + id) : job;
            this.success = success;
            this.timestampMillis = timestampMillis;
        }

        public long id() {
            return id;
        }

        /** 任务名（执行器处理器名）。 */
        public String job() {
            return job;
        }

        public boolean isSuccess() {
            return success;
        }

        /** 执行时间（毫秒）；取不到就是 0。 */
        public long timestampMillis() {
            return timestampMillis;
        }

        @Override
        public String toString() {
            return id + " " + job + " " + (success ? "success" : "failure");
        }
    }

    private ScheduleJobEventParser() {
    }

    /**
     * 解析一段 {@code /api/schedule/joblog/list} 的响应体。
     *
     * @param body 响应体 JSON（{@code ReturnT} 信封：{@code {code,msg,content:[...]}}）
     * @return 解析出来的事件；解析不了就返回空列表（**不抛** ——
     *         边车不能因为 z-schedule 换了报文格式就整个挂掉，那会让监控跟着瞎）
     */
    public static List<JobEvent> parse(String body) {
        List<JobEvent> out = new ArrayList<JobEvent>();
        if (body == null || body.trim().isEmpty()) {
            return out;
        }
        Object content = null;
        try {
            Map<String, Object> envelope = JsonUtil.parseToMap(body);
            if (envelope != null) {
                content = envelope.get("content");
                if (content == null) {
                    content = envelope.get("data");
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        if (content == null) {
            return out;
        }
        for (Object row : rows(content)) {
            if (row == null) {
                continue;
            }
            long id = asLong(field(row, "id"));
            String handler = asString(field(row, "executorHandler"));
            if (handler == null || handler.isEmpty()) {
                handler = asString(field(row, "jobDesc"));
            }
            int handleCode = (int) asLong(field(row, "handleCode"));
            int triggerCode = (int) asLong(field(row, "triggerCode"));
            boolean success = (handleCode == 0 || handleCode == 200) && triggerCode == 0;
            long ts = asMillis(field(row, "handleTime"));
            if (ts <= 0) {
                ts = asMillis(field(row, "triggerTime"));
            }
            out.add(new JobEvent(id, handler, success, ts));
        }
        return out;
    }

    /**
     * 把「一列行」摊平成可迭代的列表。
     *
     * <p>⚠ JSON 解析库把数组装成它自己的 {@code JsonArray}（<b>不是</b> {@code java.util.List}），
     * 而它的元素又是 {@code JsonObject}（不是 {@code Map}）——
     * 只按 {@code instanceof List} / {@code instanceof Map} 判类型，
     * 就会「解析成功但一条都读不出来」（实测踩过：指标全 0，看起来像「没有任务执行」）。
     * ⇒ 这里两种形态都认。
     */
    private static List<Object> rows(Object content) {
        List<Object> out = new ArrayList<Object>();
        if (content instanceof Iterable) {
            for (Object o : (Iterable<?>) content) {
                out.add(o);
            }
        } else if (content instanceof Object[]) {
            for (Object o : (Object[]) content) {
                out.add(o);
            }
        }
        return out;
    }

    /** 取一行里的某个字段（JsonObject 与 Map 两种形态都认）。 */
    private static Object field(Object row, String key) {
        if (row instanceof Map) {
            return ((Map<?, ?>) row).get(key);
        }
        if (row instanceof com.zifang.util.json.model.JsonObject) {
            return ((com.zifang.util.json.model.JsonObject) row).get(key);
        }
        return null;
    }

    private static long asLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String asString(Object v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v);
    }

    /** 时间字段可能是毫秒数，也可能是 {@code yyyy-MM-dd HH:mm:ss} 文本。 */
    private static long asMillis(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return parseTextTime(s);
            }
        }
        return 0L;
    }

    private static long parseTextTime(String s) {
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss.SSS"
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p);
                f.setLenient(false);
                return f.parse(s).getTime();
            } catch (java.text.ParseException ignored) {
                // 换下一个格式
            }
        }
        return 0L;
    }
}
