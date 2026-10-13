package com.zifang.z.observer.admin.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志全文检索的分词器。
 *
 * <p>纯自研、无词典依赖，因为词典是这里唯一能省的部分，省不掉的是切分规则本身：
 * <b>中文没有空格</b>，按空白切分的话「订单创建失败」是一个整词，搜「失败」一个字都搜不到，
 * 而「失败」恰恰是值班时最想搜的那个词。
 *
 * <p>所以中文走 unigram + bigram：单字保证召回（二字词任意组合都能被单字命中），
 * 二元组压掉单字带来的巨量误召回（「单」会命中「订单」也会命中「单独」）。
 * 英文/数字按非字母数字切，转小写。
 */
public final class Analyzer {

    private Analyzer() {
    }

    /** 索引一个字段时产出的词元。 */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String lower = text.toLowerCase();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjkRun = new StringBuilder();

        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            int width = Character.charCount(cp);
            if (isCjk(cp)) {
                flushLatin(latin, out);
                cjkRun.appendCodePoint(cp);
                i += width;
                continue;
            }
            flushCjk(cjkRun, out);
            if (Character.isLetterOrDigit(cp) || cp == '_') {
                latin.appendCodePoint(cp);
            } else {
                flushLatin(latin, out);
            }
            i += width;
        }
        flushLatin(latin, out);
        flushCjk(cjkRun, out);
        return out;
    }

    private static void flushLatin(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder buf, List<String> out) {
        if (buf.length() == 0) {
            return;
        }
        String run = buf.toString();
        buf.setLength(0);
        for (int i = 0; i < run.length(); i++) {
            out.add(run.substring(i, i + 1));
            if (i + 2 <= run.length()) {
                out.add(run.substring(i, i + 2));
            }
        }
    }

    /**
     * CJK 统一表意文字 + 扩展 A + 日文假名 + 韩文。
     *
     * <p>日文假名和韩文也归进来是有意的：它们同样不用空格分词，走同一套 unigram+bigram
     * 至少有召回；按 ASCII 规则切会把整段假名当成一个词，等于没索引。
     */
    static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
    }
}
