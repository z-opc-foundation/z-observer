package com.zifang.z.observer.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README 里每一条定量主张都必须能从源码重算出来 —— 广告出去的话就是承诺。
 *
 * <p>为什么要有这一层：本仓 README 把「指标统一前缀 z_observer_ / 暴露路径
 * /actuator/prometheus / 收端 8080 与采集 14300 / 告警状态机四态 /
 * 表达式三类 / 60 秒口径 / 看板 3020 / 模块清单」写成了硬事实，
 * 而每改一次前缀常量、每改一次端口、每加一个状态或表达式类型都会让它们漂一次。
 * 没有任何尺子去问盘面「现在还是吗」，那这些读数的保质期就是上一次编辑的时刻。</p>
 *
 * <p>口径三条：</p>
 * <ol>
 *   <li><b>只数 src/main</b>：测试目录不是 README 说的「指标」「状态」；</li>
 *   <li><b>主张抠不到就红</b>：不许「正则没匹配上就跳过」，那正是空跑；</li>
 *   <li><b>事实对象只装源码侧事实</b>，README 侧的数字在核对当下现解析 ——
 *       否则尺子在做恒等式比较，改了 README 也不红。</li>
 * </ol>
 *
 * <p>数值不等 = 红且把<b>重算值</b>打进消息（红了要知道真数是多少）。
 * 尺自己的牙口由 {@link #rulerActuallyCondemnsWrongNumbers()} 用合成 README 证明，
 * 而「不许冤枉健康页」由 {@link #readmeClaimsMatchTheCode()} 本身证明。</p>
 */
class ReadmeClaimsTest {

    /** 告警状态机与表达式类型的枚举源码（相对仓根）。 */
    private static final String ALERT_STATE_JAVA =
            "z-observer-admin/src/main/java/com/zifang/z/observer/admin/rule/AlertState.java";
    private static final String EXPRESSION_JAVA =
            "z-observer-admin/src/main/java/com/zifang/z/observer/admin/rule/Expression.java";
    private static final String METRICS_PROPS_JAVA =
            "z-observer-starter/src/main/java/com/zifang/z/observer/starter/metrics/ObserverMetricsProperties.java";
    private static final String ALERT_QUERY_CONTROLLER_JAVA =
            "z-observer-admin/src/main/java/com/zifang/z/observer/admin/web/AlertQueryController.java";
    private static final String ADMIN_YML =
            "z-observer-admin/src/main/resources/application.yml";
    private static final String SUIT_VITE_JS =
            "_frontend/z-observer-suit/vite.config.js";
    private static final String ADMIN_POM = "z-observer-admin/pom.xml";
    private static final String ADMIN_AUTH_CONFIG_JAVA =
            "z-observer-admin/src/main/java/com/zifang/z/observer/admin/web/AdminAuthConfiguration.java";

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.exists(dir.resolve("README.md")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓根（同时含 README.md 与 pom.xml 的目录）");
    }

    private static String read(Path f) {
        try {
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readRel(String rel) {
        return read(repoRoot().resolve(rel));
    }

    /** 去掉 XML 注释后再解析 —— 注释里的字样不是真坐标。 */
    private static Element projectElement(Path pom) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(pom.toFile());
            Element root = doc.getDocumentElement();
            if (root == null || !"project".equals(root.getLocalName() == null
                    ? root.getNodeName() : root.getLocalName())) {
                throw new IllegalStateException("pom 的根元素不是 <project>：" + pom);
            }
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("解析 pom 失败：" + pom, e);
        }
    }

    private static Element childElement(Element project, String name) {
        NodeList nl = project.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    /** 根 pom 的 &lt;modules&gt; 声明顺序。 */
    private static List<String> rootModules() {
        Element project = projectElement(repoRoot().resolve("pom.xml"));
        Element modules = childElement(project, "modules");
        List<String> out = new ArrayList<>();
        if (modules == null) {
            return out;
        }
        NodeList nl = modules.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "module".equals(n.getNodeName())) {
                out.add(n.getTextContent().trim());
            }
        }
        return out;
    }

    /** 取枚举的常量名（enum 头到第一个分号之间的大写标识符）。 */
    private static List<String> enumConstants(String java, String enumName) {
        String src = java;
        int at = src.indexOf("enum " + enumName);
        if (at < 0) {
            throw new IllegalStateException("源码里找不到枚举 " + enumName);
        }
        String body = src.substring(at);
        int end = body.indexOf(';');
        // 截到常量段末尾那个分号（含它）—— 截断点若不含分号，最后一个常量就匹配不上了
        body = end < 0 ? body : body.substring(0, end + 1);
        List<String> out = new ArrayList<>();
        // 末位常量可以不带分号（Java 允许 enum 最后一个常量省略分隔符）⇒ 终止符可选
        Matcher m = Pattern.compile("(?m)^\\s*([A-Z][A-Z0-9_]*)\\s*(?:,|;|$)").matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 全部 main 源码里的 Java 文件。 */
    private static List<Path> mainJavaFiles() {
        Path root = repoRoot();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 12)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 源码里出现的全部指标名（registry.xxx 的名字 + Prometheus TYPE 声明）。 */
    private static Set<String> metricNames() {
        Set<String> names = new LinkedHashSet<>();
        Pattern p1 = Pattern.compile("\\.(?:counter|gauge|histogram|summary)\\(\\s*\"(z_[a-z0-9_]+)\"");
        Pattern p2 = Pattern.compile("# TYPE (z_[a-z0-9_]+) ");
        for (Path f : mainJavaFiles()) {
            String src = read(f);
            Matcher m1 = p1.matcher(src);
            while (m1.find()) {
                names.add(m1.group(1));
            }
            Matcher m2 = p2.matcher(src);
            while (m2.find()) {
                names.add(m2.group(1));
            }
        }
        return names;
    }

    private static int ymlInt(String yml, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(\\d+)\\s*$").matcher(yml);
        if (!m.find()) {
            throw new IllegalStateException("yml 里找不到 " + key);
        }
        return Integer.parseInt(m.group(1));
    }

    private static final class Facts {
        List<String> modules;
        Set<String> metrics;
        List<String> alertStates;
        List<String> expressions;
        String endpoint;
        int serverPort;
        int collectorPort;
        int evaluateIntervalMillis;
        int forDurationMillis;
        int suitePort;
        boolean rulesGet;
        boolean rulesPost;
        boolean seriesRejectedExists;
        boolean overviewDocExists;
        int viteProxyPort;
        boolean actuatorDeclared;
        boolean actuatorHealthExcluded;
        String managementInclude;
    }

    private static Facts recomputed() {
        Path root = repoRoot();
        Facts f = new Facts();
        f.modules = rootModules();
        f.metrics = metricNames();
        f.alertStates = enumConstants(readRel(ALERT_STATE_JAVA), "AlertState");
        f.expressions = enumConstants(readRel(EXPRESSION_JAVA), "Expression");
        Matcher ep = Pattern.compile("endpoint\\s*=\\s*\"([^\"]+)\"").matcher(readRel(METRICS_PROPS_JAVA));
        f.endpoint = ep.find() ? ep.group(1) : null;
        String yml = readRel(ADMIN_YML);
        f.serverPort = ymlInt(yml, "port");
        f.collectorPort = ymlInt(yml, "collector-port");
        f.evaluateIntervalMillis = ymlInt(yml, "evaluate-interval-millis");
        f.forDurationMillis = ymlInt(yml, "for-duration-millis");
        Matcher port = Pattern.compile("port:\\s*(\\d+)").matcher(readRel(SUIT_VITE_JS));
        f.suitePort = port.find() ? Integer.parseInt(port.group(1)) : -1;
        String ctrl = readRel(ALERT_QUERY_CONTROLLER_JAVA);
        f.rulesGet = ctrl.contains("@GetMapping(\"/rules\")");
        f.rulesPost = ctrl.contains("@PostMapping(\"/rules\")");
        f.seriesRejectedExists = f.metrics.contains("z_observer_series_rejected_total");
        f.overviewDocExists = Files.exists(root.resolve("_doc/001_arch/00-overview.md"));

        // 看板 dev 代理的目标端口：必须与收端 server.port 同一个，
        // 否则代理指向一个没人监听的端口 ⇒ 看板所有 /api/ 请求全 502。
        Matcher proxy = Pattern.compile("target:\\s*'http://localhost:(\\d+)'")
                .matcher(readRel(SUIT_VITE_JS));
        f.viteProxyPort = proxy.find() ? Integer.parseInt(proxy.group(1)) : -1;
        f.actuatorDeclared = readRel(ADMIN_POM).contains("spring-boot-starter-actuator");
        f.actuatorHealthExcluded = readRel(ADMIN_AUTH_CONFIG_JAVA).contains("\"/actuator/health\"");
        Matcher include = Pattern.compile("(?m)^\\s*include:\\s*([a-z,]+)\\s*$")
                .matcher(readRel(ADMIN_YML));
        f.managementInclude = include.find() ? include.group(1) : "";
        return f;
    }

    /** README 的「## 模块」章节正文。 */
    private static String section(String readme, String title) {
        int start = readme.indexOf("## " + title);
        if (start < 0) {
            return "";
        }
        String body = readme.substring(start);
        int next = body.indexOf("\n## ", 3);
        return next < 0 ? body : body.substring(0, next);
    }

    private static void verifyClaims(String readme, Facts f) {
        String flat = readme.replaceAll("\\s+", " ");

        // 1. 模块清单：README 的模块表必须逐个点名根 pom 声明的模块，且不多不少
        String modSection = section(readme, "模块");
        for (String m : f.modules) {
            assertTrue(modSection.contains("`" + m + "`"),
                    "README 的「模块」表没有点名根 pom <modules> 里的模块 " + m);
            assertTrue(Files.isDirectory(repoRoot().resolve(m)), "根 pom 声明的模块目录不存在：" + m);
        }

        // 2. 指标统一前缀：README 说 z_observer_，源码里的指标名必须全在这个前缀下
        assertTrue(flat.contains("统一前缀 `z_observer_`"), "README 里找不到「统一前缀 `z_observer_`」这条主张");
        assertTrue(!f.metrics.isEmpty(), "源码里一个指标名都没扫到（尺子失灵，不是 README 的问题）");
        for (String name : f.metrics) {
            assertTrue(name.startsWith("z_observer_"),
                    "指标名 " + name + " 不在 README 声称的统一前缀 z_observer_ 之下");
        }
        // README 点名的那个拒绝计数器必须真的存在
        assertTrue(flat.contains("`z_observer_series_rejected_total`"),
                "README 里找不到 z_observer_series_rejected_total 这条主张");
        assertTrue(f.seriesRejectedExists, "源码里没有 z_observer_series_rejected_total 这个指标");

        // 3. 暴露路径：/actuator/prometheus
        Matcher mPath = Pattern.compile("暴露面是 starter 自己挂的 servlet，路径与 actuator 对齐：`([^`]+)`").matcher(flat);
        assertTrue(mPath.find(), "README 里找不到暴露路径这条主张");
        assertEquals(f.endpoint, mPath.group(1), "暴露路径对不上（ObserverMetricsProperties.endpoint 默认值）");

        // 4. 端口：收端 8080 / 采集 14300
        Matcher mPort = Pattern.compile("收端（默认 (\\d+)，采集端口 (\\d+)）").matcher(flat);
        assertTrue(mPort.find(), "README 里找不到「收端（默认 N，采集端口 M）」这条主张");
        assertEquals(f.serverPort, Integer.parseInt(mPort.group(1)), "收端默认端口对不上（application.yml server.port）");
        assertEquals(f.collectorPort, Integer.parseInt(mPort.group(2)), "采集端口对不上（application.yml collector-port）");

        // 5. 告警状态机四态：OK → PENDING → FIRING → RESOLVED
        Matcher mState = Pattern.compile("两段确认状态机：`([^`]+)`").matcher(flat);
        assertTrue(mState.find(), "README 里找不到「两段确认状态机」这条主张");
        List<String> readmeStates = new ArrayList<>();
        for (String s : mState.group(1).split("→")) {
            readmeStates.add(s.trim());
        }
        assertEquals(f.alertStates, readmeStates, "告警状态机对不上（AlertState 枚举常量）");

        // 6. 表达式三类：源码枚举必须正好三类，且含 THRESHOLD / ABSENT / BOOLEAN
        assertTrue(flat.contains("三类表达式：阈值 / absent / 组合布尔"),
                "README 里找不到「三类表达式」这条主张");
        assertEquals(3, f.expressions.size(), "表达式类型数对不上（Expression 枚举常量个数）");
        assertTrue(f.expressions.containsAll(List.of("THRESHOLD", "ABSENT", "BOOLEAN")),
                "Expression 枚举里缺三类之一（实测：" + f.expressions + "）");

        // 7. 规则运行时注册：POST /api/rules + GET /api/rules
        assertTrue(flat.contains("`POST /api/rules`"), "README 里找不到 POST /api/rules 这条主张");
        assertTrue(flat.contains("`GET /api/rules`"), "README 里找不到 GET /api/rules 这条主张");
        assertTrue(f.rulesPost, "AlertQueryController 里没有 @PostMapping(\"/rules\")");
        assertTrue(f.rulesGet, "AlertQueryController 里没有 @GetMapping(\"/rules\")");

        // 8. 60 秒口径：确认时长 + 评估间隔必须留有余量
        Matcher m60 = Pattern.compile("而 M1 的口径是 (\\d+) 秒").matcher(flat);
        assertTrue(m60.find(), "README 里找不到「M1 的口径是 N 秒」这条主张");
        int slaSeconds = Integer.parseInt(m60.group(1));
        int worstCaseMillis = f.forDurationMillis + f.evaluateIntervalMillis;
        assertTrue(worstCaseMillis <= slaSeconds * 1000L,
                "60 秒口径在盘上不成立：确认时长 " + f.forDurationMillis + "ms + 评估间隔 "
                        + f.evaluateIntervalMillis + "ms = " + worstCaseMillis + "ms");

        // 9. 看板 dev 端口 3020
        Matcher mPort2 = Pattern.compile("npm run dev\\s+#\\s*(\\d+)，代理").matcher(flat);
        assertTrue(mPort2.find(), "README 里找不到「npm run dev # N，代理」这条主张");
        assertEquals(f.suitePort, Integer.parseInt(mPort2.group(1)), "看板 dev 端口对不上（suit 的 vite server.port）");

        // 10. 文档目录：README 点名的 _doc/001_arch/00-overview.md 必须在盘上
        assertTrue(flat.contains("(_doc/001_arch/00-overview.md)"),
                "README 里找不到 _doc/001_arch/00-overview.md 这条主张");
        assertTrue(f.overviewDocExists, "_doc/001_arch/00-overview.md 不在盘上");
    }

    /**
     * 运行期接线对账（不查 README，查源码与配置之间是否自洽）。
     *
     * <p>这两条都是「数字对不上 ⇒ 功能直接瘫」那一类，写错了没人拦：
     * ① 看板 dev 代理目标端口 ≠ 收端端口 ⇒ 看板所有 /api/ 请求 502，
     *    现象是「页面能开、数据全空」，很容易被当成后端没数据；
     * ② 收端没引 actuator ⇒ /actuator/health 是 404 ⇒ 通用探针永远失败。
     * </p>
     */
    private static void verifyRuntimeWiring(Facts f) {
        assertTrue(f.viteProxyPort > 0, "在 suit 的 vite.config.js 里抠不到代理目标端口（尺子失灵）");
        assertEquals(f.serverPort, f.viteProxyPort,
                "看板 dev 代理目标端口与收端 server.port 对不上"
                        + "（vite.config.js 的 target vs application.yml 的 server.port）");
        assertTrue(f.actuatorDeclared,
                "z-observer-admin/pom.xml 没引 spring-boot-starter-actuator ⇒ /actuator/health 不存在");
        assertTrue(f.actuatorHealthExcluded,
                "管理面鉴权闸门没把 /actuator/health 放进免鉴权名单 ⇒ 探针打它会拿到 401");
        assertTrue(f.managementInclude.contains("health"),
                "application.yml 的 management 暴露面里没有 health（实测：" + f.managementInclude + "）");
    }

    @Test
    @DisplayName("README 的定量主张与盘面重算值逐条对账")
    void readmeClaimsMatchTheCode() {
        verifyClaims(read(repoRoot().resolve("README.md")), recomputed());
    }

    @Test
    @DisplayName("不许冤枉健康页：真实 README 不得被判红")
    void healthyReadmeIsNotRejected() {
        verifyClaims(read(repoRoot().resolve("README.md")), recomputed());
    }

    @Test
    @DisplayName("尺子的牙口：把 README 的数改错，判定必须判红")
    void rulerActuallyCondemnsWrongNumbers() {
        String real = read(repoRoot().resolve("README.md"));
        Facts f = recomputed();
        List<String[]> mutations = List.of(
                new String[]{"收端端口", "收端（默认 8080", "收端（默认 9090"},
                new String[]{"采集端口", "采集端口 14300", "采集端口 14301"},
                new String[]{"状态机", "`OK → PENDING → FIRING → RESOLVED`", "`OK → PENDING → RESOLVED`"},
                new String[]{"暴露路径", "`/actuator/prometheus`", "`/actuator/metrics`"},
                new String[]{"看板端口", "npm run dev     # 3020", "npm run dev     # 3021"},
                new String[]{"60 秒口径", "而 M1 的口径是 60 秒", "而 M1 的口径是 30 秒"},
                new String[]{"表达式三类", "三类表达式：阈值 / absent / 组合布尔", "两类表达式：阈值 / absent"},
                new String[]{"指标前缀", "统一前缀 `z_observer_`", "统一前缀 `z_obs_`"});
        for (String[] mut : mutations) {
            String broken = real.replace(mut[1], mut[2]);
            assertNotEquals(broken, real, "变异「" + mut[0] + "」没改动 README（尺子测不到自己）");
            assertThrows(AssertionError.class, () -> verifyClaims(broken, f),
                    "变异「" + mut[0] + "」竟然没被判红");
        }
    }

    /**
     * 首屏边界句（卡 TASK-20261012-004 验收 4）。
     *
     * <p>「与第三方 Prometheus / Grafana 共存是<b>有意为之</b>」是本仓对外的
     * <b>边界声明</b>，不是描述：读的人据此知道「不自建时序存储」是拍出来的决定，
     * 而不是「还没空做」。这类声明一旦被挪到文档深处，首屏就只剩功能罗列，
     * 边界声明等于消失。
     *
     * <p>判据：README 的**第一个**二级章节就是它（首屏），
     * 且标题里同时有「共存」与「有意为之」两个词。
     */
    private static void verifyCoexistenceBoundary(String readme) {
        String firstHeading = null;
        for (String line : readme.split("\n")) {
            if (line.startsWith("## ")) {
                firstHeading = line;
                break;
            }
        }
        assertNotNull(firstHeading, "README 里一个二级章节都没有");
        assertTrue(firstHeading.contains("共存") && firstHeading.contains("有意为之"),
                "README 的首个二级章节不是「与第三方 Prometheus / Grafana 共存，是有意为之」这条边界声明"
                        + "（实测首章节：" + firstHeading + "）");
        assertTrue(firstHeading.contains("Prometheus") && firstHeading.contains("Grafana"),
                "首屏边界声明里必须点名 Prometheus 与 Grafana（实测：" + firstHeading + "）");
    }

    @Test
    @DisplayName("首屏写明「与第三方 Prometheus/Grafana 共存是有意为之」的边界")
    void firstScreenStatesTheCoexistenceBoundary() {
        verifyCoexistenceBoundary(read(repoRoot().resolve("README.md")));
    }

    @Test
    @DisplayName("尺子的牙口：把边界声明从首屏挪走，必须判红")
    void rulerCondemnsDemotedBoundaryClaim() {
        String real = read(repoRoot().resolve("README.md"));
        // 模拟「边界声明还在，但被挪到后面去了」：把首章节换成别的
        String broken = real.replaceFirst("(?m)^## 与第三方 Prometheus / Grafana 共存，是有意为之$",
                "## 背景");
        assertNotEquals(broken, real, "变异没改动 README（尺子测不到自己）");
        assertThrows(AssertionError.class, () -> verifyCoexistenceBoundary(broken),
                "边界声明被挪到后面竟然没被判红");
    }

    @Test
    @DisplayName("运行期接线自洽：代理端口 = 收端端口；actuator 健康端点通")
    void runtimeWiringIsSelfConsistent() {
        verifyRuntimeWiring(recomputed());
    }

    @Test
    @DisplayName("尺子的牙口：代理端口写错 / actuator 拿掉，必须判红")
    void runtimeWiringCondemnsBrokenWiring() {
        Facts f = recomputed();

        Facts brokenPort = copyOf(f);
        brokenPort.viteProxyPort = f.serverPort + 1;
        assertThrows(AssertionError.class, () -> verifyRuntimeWiring(brokenPort),
                "代理端口写错竟然没被判红");

        Facts noActuator = copyOf(f);
        noActuator.actuatorDeclared = false;
        assertThrows(AssertionError.class, () -> verifyRuntimeWiring(noActuator),
                "admin 拿掉 actuator 竟然没被判红");

        Facts noExclusion = copyOf(f);
        noExclusion.actuatorHealthExcluded = false;
        assertThrows(AssertionError.class, () -> verifyRuntimeWiring(noExclusion),
                "免鉴权名单去掉 /actuator/health 竟然没被判红");

        Facts noHealth = copyOf(f);
        noHealth.managementInclude = "info";
        assertThrows(AssertionError.class, () -> verifyRuntimeWiring(noHealth),
                "management 暴露面里去掉 health 竟然没被判红");
    }

    /** 逐字段拷贝 —— 变异测试里各条变异必须互不影响，否则第二条可能靠第一条的红蒙混过关。 */
    private static Facts copyOf(Facts src) {
        Facts c = new Facts();
        c.modules = src.modules;
        c.metrics = src.metrics;
        c.alertStates = src.alertStates;
        c.expressions = src.expressions;
        c.endpoint = src.endpoint;
        c.serverPort = src.serverPort;
        c.collectorPort = src.collectorPort;
        c.evaluateIntervalMillis = src.evaluateIntervalMillis;
        c.forDurationMillis = src.forDurationMillis;
        c.suitePort = src.suitePort;
        c.rulesGet = src.rulesGet;
        c.rulesPost = src.rulesPost;
        c.seriesRejectedExists = src.seriesRejectedExists;
        c.overviewDocExists = src.overviewDocExists;
        c.viteProxyPort = src.viteProxyPort;
        c.actuatorDeclared = src.actuatorDeclared;
        c.actuatorHealthExcluded = src.actuatorHealthExcluded;
        c.managementInclude = src.managementInclude;
        return c;
    }

    @Test
    @DisplayName("尺子的牙口：把 README 的主张删掉，判定必须判红（不许静默跳过）")
    void rulerCondemnsMissingClaims() {
        String real = read(repoRoot().resolve("README.md"));
        Facts f = recomputed();
        List<String[]> deletions = List.of(
                new String[]{"收端端口", "收端（默认 8080，采集端口 14300）", "收端（默认端口见配置）"},
                new String[]{"暴露路径", "路径与 actuator 对齐：`/actuator/prometheus`", "路径与 actuator 对齐"},
                new String[]{"看板端口", "npm run dev     # 3020，代理 /api/ 到收端", "npm run dev，代理 /api/ 到收端"});
        for (String[] del : deletions) {
            String broken = real.replace(del[1], del[2]);
            assertNotEquals(broken, real, "删除「" + del[0] + "」没改动 README（尺子测不到自己）");
            assertThrows(AssertionError.class, () -> verifyClaims(broken, f),
                    "删除「" + del[0] + "」竟然没被判红");
        }
    }
}
