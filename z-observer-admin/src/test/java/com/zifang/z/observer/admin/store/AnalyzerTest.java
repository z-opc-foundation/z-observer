package com.zifang.z.observer.admin.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerTest {

    @Test
    void 中文按单字加二元切_搜两个字能命中() {
        List<String> tokens = Analyzer.tokenize("订单创建失败");
        assertThat(tokens).contains("失", "失败", "订", "订单");
    }

    @Test
    void 英文数字按非字母数字切且转小写() {
        assertThat(Analyzer.tokenize("OrderId=42, user=Alice"))
                .containsExactly("orderid", "42", "user", "alice");
    }

    @Test
    void 中英混排两边都切出来() {
        assertThat(Analyzer.tokenize("支付失败 pay failed"))
                .contains("支付", "失败", "pay", "failed");
    }

    @Test
    void 标点不产生词元() {
        assertThat(Analyzer.tokenize("!!! ???")).isEmpty();
    }

    @Test
    void 空输入不炸() {
        assertThat(Analyzer.tokenize(null)).isEmpty();
        assertThat(Analyzer.tokenize("")).isEmpty();
    }

    @Test
    void 日文假名也按单字二元切() {
        assertThat(Analyzer.tokenize("エラー")).contains("エ", "エラ");
    }
}
