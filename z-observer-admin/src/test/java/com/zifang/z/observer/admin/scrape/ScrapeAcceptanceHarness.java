package com.zifang.z.observer.admin.scrape;

import com.zifang.z.observer.core.metric.MetricRegistry;
import com.zifang.z.observer.core.metric.PrometheusTextWriter;
import com.zifang.z.observer.core.model.Labels;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 采集层验收演练（D1 验收 1 / 2）。
 *
 * <p><b>它做什么</b>：起 N 个<b>真实进程</b>（python3.11 的 HTTP 服务），
 * 每个进程用 {@code /actuator/prometheus} 暴露一份<b>由 z-observer 自己的 writer 产出的</b>
 * Prometheus 文本格式（text format 0.0.4，含中文说明、UTF-8 标签值、
 * 转义字符、直方图分桶）—— 然后用收端<b>真实的采集代码</b>
 * （{@link Scraper} + {@link ScrapeLoop} + {@code PrometheusTextParser}）
 * 连续抓若干轮，中途<b>杀掉一个 target 进程</b>，验证下一轮能产生「抓不到」事件、
 * 且该 target 进缺席名单。
 *
 * <p><b>为什么用独立进程而不是线程内的 HTTP 服务</b>：验收第 2 条要的是「杀掉一个 target」。
 * 关掉一个内嵌 HTTP 服务只是「端口关了」，而杀掉一个进程才是「这个 target 没了」——
 * 后者才是 absent 告警真正要覆盖的场景。
 *
 * <p><b>跑法</b>：
 * <pre>
 * # 完整验收（默认 10 分钟，5 个 target，15 秒一轮）
 * mvn -pl z-observer-admin -am test -Dtest=ScrapeAcceptanceHarness \
 *     -Dzobserver.scrape.acceptance.seconds=600 \
 *     -Dzobserver.scrape.acceptance.out=/path/to/evidence
 *
 * # 短跑（默认构建里跑的就是这个：8 秒 / 2 秒一轮）
 * mvn -pl z-observer-admin -am test
 * </pre>
 *
 * <p><b>类名故意不匹配 surefire 的默认包含规则</b>（{@code *Test}），
 * 所以完整 10 分钟那场只在显式指定时才跑；常规构建里跑的是
 * {@link ScrapeAcceptanceTest}（同一条通路，几秒钟）。
 */
class ScrapeAcceptanceHarness {

    static final int TARGET_COUNT = 5;
    static final String EXPOSITION_PATH = "/actuator/prometheus";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 完整验收默认 10 分钟。 */
    static long durationMillis() {
        return Long.getLong("zobserver.scrape.acceptance.seconds", 600L) * 1000L;
    }

    /** 采集周期。默认 15 秒 —— 与生产 {@code z.observer.scrape-interval-millis} 一致。 */
    static long intervalMillis() {
        return Long.getLong("zobserver.scrape.acceptance.interval.seconds", 15L) * 1000L;
    }

    static Path outputDir() {
        return Paths.get(System.getProperty("zobserver.scrape.acceptance.out",
                "target/scrape-acceptance"));
    }

    /** 演练结果（写进 summary.json，也用于断言）。 */
    static final class Result {

        int rounds;
        int okScrapes;
        int failedScrapes;
        int parsedSamples;
        int parseFailures;
        boolean killObserved;
        boolean recoveryObserved;
        boolean allTargetsUpBeforeKill;
        String killedTargetId = "";
        String mode = "";
        final List<String> absentRounds = new ArrayList<String>();
    }

    @Test
    void 连续抓取_解析零失败_杀掉一个target下一轮产生抓不到事件() throws Exception {
        Result r = run(durationMillis(), intervalMillis(), outputDir());
        System.out.println("[acceptance] " + summary(r));
        org.junit.jupiter.api.Assertions.assertTrue(r.parseFailures == 0,
                "解析失败次数必须为 0，实际 " + r.parseFailures);
        org.junit.jupiter.api.Assertions.assertTrue(r.okScrapes > 0, "必须有抓成功的轮次");
        org.junit.jupiter.api.Assertions.assertTrue(r.allTargetsUpBeforeKill,
                "杀掉 target 之前必须所有 target 都是 up");
        org.junit.jupiter.api.Assertions.assertTrue(r.killObserved,
                "杀掉 target 之后必须有一轮抓不到它（抓不到事件）");
        org.junit.jupiter.api.Assertions.assertTrue(r.recoveryObserved,
                "target 恢复之后必须重新变回 up");
    }

    /** 跑一遍演练。durationMillis=0 表示不杀掉 target（只验证连续抓取）。 */
    static Result run(long durationMillis, long intervalMillis, Path outDir) throws Exception {
        Result result = new Result();
        Files.createDirectories(outDir);
        Path expositionDir = outDir.resolve("exposition");
        Files.createDirectories(expositionDir);

        List<LocalTarget> targets = new ArrayList<LocalTarget>();
        for (int i = 0; i < TARGET_COUNT; i++) {
            String id = "target-" + i;
            Path file = expositionDir.resolve(id + ".txt");
            Files.write(file, renderExposition(id).getBytes(UTF8));
            LocalTarget t = LocalTarget.start(id, file, outDir);
            targets.add(t);
        }
        result.mode = targets.get(0).mode();

        List<ScrapeTarget> scrapeTargets = new ArrayList<ScrapeTarget>();
        for (LocalTarget t : targets) {
            // target id 用「target-N」而不是 URL：URL 里带端口，重启后端口要保持不变，
            // 而 id 稳定才看得出「还是同一个 target 掉线又回来了」。
            scrapeTargets.add(new ScrapeTarget(t.id(), t.url()));
        }
        ScrapeLoop loop = new ScrapeLoop(
                new StaticTargetSource(scrapeTargets),
                new TargetRegistry(),
                new Scraper(5000));

        Writer roundsOut = writer(outDir.resolve("rounds.jsonl"));
        Writer samplesOut = writer(outDir.resolve("samples.jsonl"));
        try {
            long start = System.currentTimeMillis();
            long killAt = start + (long) (durationMillis * 0.4);
            long restartAt = start + (long) (durationMillis * 0.7);
            LocalTarget victim = targets.get(TARGET_COUNT / 2);
            result.killedTargetId = victim.id();
            boolean killed = false;
            boolean restarted = false;
            boolean killDone = false;
            int round = 0;
            while (System.currentTimeMillis() - start < durationMillis) {
                long now = System.currentTimeMillis();
                if (!killDone && durationMillis > 0 && now >= killAt) {
                    victim.kill();
                    killed = true;
                    killDone = true;
                }
                if (killed && !restarted && now >= restartAt) {
                    victim.restart();
                    restarted = true;
                }

                ScrapeLoop.Round r = loop.run(now);
                result.rounds++;
                result.okScrapes += r.okCount();
                result.failedScrapes += r.failCount();
                for (Map.Entry<String, List<com.zifang.z.observer.core.metric.PrometheusTextParser.ParsedSample>> e
                        : r.parsedSamples().entrySet()) {
                    result.parsedSamples += e.getValue().size();
                    for (com.zifang.z.observer.core.metric.PrometheusTextParser.ParsedSample s
                            : e.getValue()) {
                        samplesOut.write("{\"round\":" + round + ",\"target\":\"" + e.getKey()
                                + "\",\"ts\":" + now + ",\"sample\":\"" + s.seriesKey()
                                + " " + s.value() + "\"}\n");
                    }
                }
                roundsOut.write("{\"round\":" + round + ",\"ts\":" + now
                        + ",\"ok\":" + r.okCount() + ",\"fail\":" + r.failCount()
                        + ",\"errors\":" + jsonArray(r.errors()) + "}\n");
                samplesOut.flush();
                roundsOut.flush();

                ScrapeStatus victimStatus = loop.status().status(victim.id());
                if (victimStatus != null && victimStatus.isAbsent() && killed) {
                    result.killObserved = true;
                    result.absentRounds.add("round " + round);
                }
                if (result.killObserved && victimStatus != null && victimStatus.isUp()) {
                    result.recoveryObserved = true;
                }
                if (!killed && r.failCount() == 0 && r.okCount() == TARGET_COUNT) {
                    result.allTargetsUpBeforeKill = true;
                }
                round++;
                if (System.currentTimeMillis() - start >= durationMillis) {
                    break;
                }
                Thread.sleep(Math.min(intervalMillis,
                        Math.max(50L, durationMillis - (System.currentTimeMillis() - start))));
            }
        } finally {
            roundsOut.close();
            samplesOut.close();
        }

        result.parseFailures = (int) loop.status().parseFailureCount();
        Files.write(outDir.resolve("status-exposition.txt"),
                loop.status().toExposition().getBytes(UTF8));
        StringBuilder events = new StringBuilder();
        for (ScrapeEvent e : loop.status().events()) {
            events.append(e).append('\n');
        }
        Files.write(outDir.resolve("events.txt"), events.toString().getBytes(UTF8));
        Files.write(outDir.resolve("summary.json"), summary(result).getBytes(UTF8));

        for (LocalTarget t : targets) {
            t.close();
        }
        return result;
    }

    private static String summary(Result r) {
        return "{\"rounds\":" + r.rounds
                + ",\"okScrapes\":" + r.okScrapes
                + ",\"failedScrapes\":" + r.failedScrapes
                + ",\"parsedSamples\":" + r.parsedSamples
                + ",\"parseFailures\":" + r.parseFailures
                + ",\"killObserved\":" + r.killObserved
                + ",\"recoveryObserved\":" + r.recoveryObserved
                + ",\"allTargetsUpBeforeKill\":" + r.allTargetsUpBeforeKill
                + ",\"killedTargetId\":\"" + r.killedTargetId + "\""
                + ",\"mode\":\"" + r.mode + "\""
                + ",\"absentRounds\":" + jsonArray(r.absentRounds) + "}";
    }

    private static String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static Writer writer(Path path) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(path.toFile()), UTF8));
    }

    /**
     * 造一份<b>真实</b>的 exposition：用 z-observer 自己的 writer 渲染，
     * 于是被抓的字节与 SDK 暴露的字节同源。
     *
     * <p>刻意带上中文说明、UTF-8 标签值、引号与反斜杠 —— 解析器要能原样读回来。
     */
    static String renderExposition(String instance) {
        MetricRegistry registry = new MetricRegistry();
        Labels labels = Labels.builder()
                .put("app", "z-observer-acceptance")
                .put("host", instance)
                .build();
        registry.counter("z_app_requests_total", "应用请求数（中文说明）", labels).inc(1234);
        registry.gauge("z_app_uptime_seconds", "进程运行时长（秒）", labels).set(3600.5);
        registry.gauge("z_app_build_info", "构建信息",
                Labels.builder()
                        .put("app", "z-observer-acceptance")
                        .put("version", "1.0.0-rc\"1")
                        .put("note", "含\\反斜杠")
                        .build()).set(1);
        com.zifang.z.observer.core.metric.Histogram h =
                registry.histogram("z_app_latency_seconds", "请求耗时", labels,
                        new double[] {0.005, 0.01, 0.1, 1.0, 5.0});
        h.observe(0.003);
        h.observe(0.02);
        h.observe(0.5);
        h.observe(7.0);
        return new PrometheusTextWriter(registry).render();
    }

    /**
     * 一个本地 target：优先用 python3.11 起独立进程（可真的 kill 掉），
     * 没有 python3.11 时退回 JDK 内置 HTTP 服务（此时只能「关端口」）。
     */
    abstract static class LocalTarget {

        final String id;
        final Path expositionFile;
        final Path outDir;
        Process process;
        com.sun.net.httpserver.HttpServer server;
        int port;
        String mode = "";

        LocalTarget(String id, Path expositionFile, Path outDir) {
            this.id = id;
            this.expositionFile = expositionFile;
            this.outDir = outDir;
        }

        static LocalTarget start(String id, Path expositionFile, Path outDir) throws Exception {
            if (pythonAvailable()) {
                PythonTarget t = new PythonTarget(id, expositionFile, outDir);
                t.spawn();
                return t;
            }
            JdkTarget t = new JdkTarget(id, expositionFile, outDir);
            t.spawn();
            return t;
        }

        abstract void spawn() throws Exception;

        abstract void kill() throws Exception;

        void restart() throws Exception {
            spawn();
        }

        abstract void close();

        String url() {
            return "http://127.0.0.1:" + port + EXPOSITION_PATH;
        }

        String id() {
            return id;
        }

        String mode() {
            return mode;
        }

        static boolean pythonAvailable() {
            try {
                Process p = new ProcessBuilder("python3.11", "--version")
                        .redirectErrorStream(true).start();
                drain(p.getInputStream());
                return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        }

        /** 读完子进程输出（避免管道写满把它卡住）。Java 8 没有 readAllBytes。 */
        private static void drain(java.io.InputStream in) throws IOException {
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) {
                // 读掉即可，内容不重要
            }
            in.close();
        }

        static int freePort() throws IOException {
            ServerSocket s = new ServerSocket(0);
            try {
                return s.getLocalPort();
            } finally {
                s.close();
            }
        }
    }

    /** python3.11 独立进程：可以真的 kill 掉。 */
    static final class PythonTarget extends LocalTarget {

        PythonTarget(String id, Path expositionFile, Path outDir) {
            super(id, expositionFile, outDir);
            this.mode = "python3.11 独立进程";
        }

        @Override
        void spawn() throws Exception {
            Path script = outDir.resolve("target_server.py");
            Files.write(script, PY_SCRIPT.getBytes(UTF8));
            if (port <= 0) {
                // 端口只在第一次分配：重启必须回到<b>同一个</b>地址，
                // 否则「这个 target 恢复了」就变成了「换了个新 target 出现」，
                // 缺席名单的销账也就无从验证。
                port = freePort();
            }
            File log = outDir.resolve("target-" + id + ".log").toFile();
            ProcessBuilder pb = new ProcessBuilder("python3.11", script.toString(),
                    String.valueOf(port), expositionFile.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log);
            process = pb.start();
            waitReady();
        }

        private void waitReady() throws Exception {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline) {
                if (process != null && !process.isAlive()) {
                    throw new IllegalStateException("target " + id + " 进程启动即退出");
                }
                try (java.net.Socket s = new java.net.Socket()) {
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                    return;
                } catch (IOException ignored) {
                    Thread.sleep(100L);
                }
            }
            throw new IllegalStateException("target " + id + " 起来超时");
        }

        @Override
        void kill() throws Exception {
            if (process != null) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                process = null;
            }
        }

        @Override
        void close() {
            try {
                kill();
            } catch (Exception ignored) {
                // 收尾阶段的异常不该盖掉真正的结论
            }
        }
    }

    /** 退化形态：JDK 内置 HTTP 服务（没有 python3.11 时用；此时只能关端口）。 */
    static final class JdkTarget extends LocalTarget {

        JdkTarget(String id, Path expositionFile, Path outDir) {
            super(id, expositionFile, outDir);
            this.mode = "JDK 内置 HTTP 服务（退化形态：只能关端口，不能杀进程）";
        }

        @Override
        void spawn() throws Exception {
            if (port <= 0) {
                port = freePort();
            }
            byte[] data = Files.readAllBytes(expositionFile);
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
                @Override
                public void handle(com.sun.net.httpserver.HttpExchange x) throws IOException {
                    x.sendResponseHeaders(200, data.length);
                    x.getResponseBody().write(data);
                    x.close();
                }
            });
            server.start();
        }

        @Override
        void kill() {
            if (server != null) {
                server.stop(0);
                server = null;
            }
        }

        @Override
        void restart() throws Exception {
            spawn();
        }

        @Override
        void close() {
            kill();
        }
    }

    private static final String PY_SCRIPT = String.join("\n",
            "import sys, http.server",
            "port = int(sys.argv[1])",
            "fpath = sys.argv[2]",
            "class H(http.server.BaseHTTPRequestHandler):",
            "    protocol_version = 'HTTP/1.1'",
            "    def do_GET(self):",
            "        if self.path.split('?')[0] != '" + EXPOSITION_PATH + "':",
            "            self.send_response(404)",
            "            self.send_header('Content-Length', '0')",
            "            self.end_headers()",
            "            return",
            "        with open(fpath, 'rb') as f:",
            "            data = f.read()",
            "        self.send_response(200)",
            "        self.send_header('Content-Type', 'text/plain; version=0.0.4; charset=utf-8')",
            "        self.send_header('Content-Length', str(len(data)))",
            "        self.end_headers()",
            "        self.wfile.write(data)",
            "    def log_message(self, *a):",
            "        pass",
            "http.server.ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()",
            "");
}
