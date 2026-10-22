package com.zifang.z.observer.admin.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理面入站鉴权闸门。
 *
 * <p>要抓的是三件容易犯的错：
 * <ol>
 *   <li><b>只保护读端点</b> ⇒ 写端点（加静默/加抑制/覆盖规则）必须一样被挡；</li>
 *   <li><b>免鉴权口子开太大</b> ⇒ 只有健康端点免鉴权，业务读端点一律挡；</li>
 *   <li><b>挑战写错</b> ⇒ 401 必须带 {@code WWW-Authenticate}，否则客户端不知道该带什么。</li>
 * </ol>
 */
class AdminAuthFilterTest {

    private static final String TOKEN = "test-admin-token";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new StubRulesController(),
                        new InternalHealthController())
                .addFilters(new AdminAuthFilter(TOKEN,
                        Arrays.asList(AdminAuthConfiguration.INTERNAL_HEALTH_PATH)))
                .build();
    }

    @Test
    void 没有令牌一律401并带挑战() throws Exception {
        mvc.perform(get("/api/rules"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("Bearer realm=\"z-observer-admin\"")));
    }

    @Test
    void 令牌错也401() throws Exception {
        mvc.perform(get("/api/rules").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 头格式不对也401() throws Exception {
        mvc.perform(get("/api/rules").header("Authorization", TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 带对令牌的读端点放行() throws Exception {
        mvc.perform(get("/api/rules").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void 写端点同样被挡() throws Exception {
        // ⚠ 这条是本卡的核心：**只保护读端点等于没保护**
        mvc.perform(post("/api/rules").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/silences").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inhibits").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 带对令牌的写端点放行() throws Exception {
        mvc.perform(post("/api/silences").contentType("application/json").content("{}")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void 健康端点免鉴权() throws Exception {
        mvc.perform(get(AdminAuthConfiguration.INTERNAL_HEALTH_PATH))
                .andExpect(status().isOk());
    }

    @Test
    void options预检也要过鉴权() throws Exception {
        mvc.perform(options("/api/rules"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearer解析的边界() {
        assertThat(AdminAuthFilter.bearerOf("Bearer abc")).isEqualTo("abc");
        assertThat(AdminAuthFilter.bearerOf("bearer abc")).isEqualTo("abc");
        assertThat(AdminAuthFilter.bearerOf("Bearer  abc ")).isEqualTo("abc");
        assertThat(AdminAuthFilter.bearerOf("abc")).isNull();
        assertThat(AdminAuthFilter.bearerOf("Bearer ")).isNull();
        assertThat(AdminAuthFilter.bearerOf("Bearer")).isNull();
        assertThat(AdminAuthFilter.bearerOf(null)).isNull();
    }

    /** 只为占位：本测试只关心「有没有被挡」，不关心业务返回什么。 */
    @RestController
    static class StubRulesController {

        @GetMapping("/api/rules")
        public Map<String, Object> rules() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            return out;
        }

        @PostMapping("/api/rules")
        public Map<String, Object> addRule(@RequestBody Map<String, Object> body) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            return out;
        }

        @PostMapping("/api/silences")
        public Map<String, Object> silence(@RequestBody Map<String, Object> body) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            return out;
        }

        @PostMapping("/api/inhibits")
        public Map<String, Object> inhibit(@RequestBody Map<String, Object> body) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            return out;
        }
    }
}
