package com.zifang.z.observer.admin.notify.resilient;

import com.zifang.z.observer.admin.notify.AlertNotification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * 待发通知的本地落盘暂存。
 *
 * <p><b>为什么必须落盘而不是放内存队列。</b> 内存队列在进程重启时就没了 ——
 * 而「z-msg 挂掉的那 10 分钟里产生的告警」恰恰是最该被送到人的那批。
 * 落盘之后：进程重启、机器重启，待发的通知都还在，恢复后补发。
 *
 * <p><b>落盘里不许有凭据。</b> 这里存的是通知正文与结构化字段（应用名 / 实例 / 规则 /
 * 触发值 / 时间），没有 token、没有口令 —— 收件人这类东西是发送时现取的，
 * 不进暂存文件。
 *
 * <p>文件格式用 {@link Properties}：一行一个 {@code key=value}，
 * 读回来不需要引 JSON 解析器，也不会因为正文里出现换行/引号而解析失败。
 */
public final class NotificationSpool {

    private static final Logger LOG = LogManager.getLogger(NotificationSpool.class);

    private static final String PREFIX = "pending-";
    private static final String SUFFIX = ".properties";

    private final File dir;
    private final File failedDir;

    public NotificationSpool(File dir) {
        this(dir, new File(dir, "failed"));
    }

    public NotificationSpool(File dir, File failedDir) {
        this.dir = dir;
        this.failedDir = failedDir;
    }

    /** 落盘一条待发通知。写不成功会抛 —— 不许「落盘失败就当丢了」。 */
    public File spool(AlertNotification n) {
        try {
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("建不了暂存目录: " + dir);
            }
            File f = new File(dir, PREFIX + System.currentTimeMillis() + "-"
                    + Long.toHexString(System.nanoTime()) + SUFFIX);
            Properties p = new Properties();
            p.setProperty("kind", n.kind().name());
            p.setProperty("app", nz(n.app()));
            p.setProperty("instance", nz(n.instance()));
            p.setProperty("ruleId", nz(n.ruleId()));
            p.setProperty("ruleName", nz(n.ruleName()));
            p.setProperty("severity", nz(n.severity()));
            p.setProperty("value", nz(n.value()));
            p.setProperty("timestampMillis", Long.toString(n.timestampMillis()));
            p.setProperty("durationMillis", Long.toString(n.durationMillis()));
            p.setProperty("title", nz(n.title()));
            p.setProperty("body", nz(n.body()));
            p.setProperty("attempt", "0");
            OutputStream out = new FileOutputStream(f);
            try {
                p.store(out, "z-observer 待发通知");
            } finally {
                out.close();
            }
            LOG.warn("通知发送失败，已落盘待发: {}（应用 {} 实例 {}）", f.getName(), n.app(), n.instance());
            return f;
        } catch (IOException e) {
            throw new SpoolException("落盘待发失败: " + e.getMessage(), e);
        }
    }

    /** 当前待发条数（不含已判死的）。 */
    public int pendingCount() {
        File[] files = listPending();
        return files == null ? 0 : files.length;
    }

    /** 待发文件列表（按文件名排序 = 按时间排序）。 */
    public File[] listPending() {
        File[] files = dir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
            }
        });
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return files;
    }

    /** 读回一条待发通知。 */
    public AlertNotification read(File f) throws IOException {
        Properties p = new Properties();
        InputStream in = new FileInputStream(f);
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return new AlertNotification(
                kind(p.getProperty("kind")),
                p.getProperty("app"),
                p.getProperty("instance"),
                p.getProperty("ruleId"),
                p.getProperty("ruleName"),
                p.getProperty("severity"),
                p.getProperty("value"),
                longOf(p.getProperty("timestampMillis")),
                longOf(p.getProperty("durationMillis")),
                p.getProperty("title"),
                p.getProperty("body"),
                null);
    }

    /** 读回一条待发通知的重试次数。 */
    public int readAttempts(File f) throws IOException {
        Properties p = new Properties();
        InputStream in = new FileInputStream(f);
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return (int) longOf(p.getProperty("attempt"));
    }

    /** 记一次重试次数。 */
    public void writeAttempts(File f, int attempts) {
        try {
            Properties p = new Properties();
            InputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            p.setProperty("attempt", Integer.toString(attempts));
            OutputStream out = new FileOutputStream(f);
            try {
                p.store(out, "z-observer 待发通知");
            } finally {
                out.close();
            }
        } catch (IOException e) {
            LOG.error("写重试次数失败: {}", e.getMessage());
        }
    }

    /** 送到了：删掉暂存文件。 */
    public void remove(File f) {
        if (f.exists() && !f.delete()) {
            LOG.warn("待发通知删不掉: {}", f.getName());
        }
    }

    /**
     * 补发次数用尽：挪到 failed 目录。
     *
     * <p>挪过去而不是删掉 —— 「这条告警始终没送到」本身是必须留痕的事实，
     * 值班的人事后要能查到，而不是「重试几次就悄悄没了」。
     */
    public File markFailed(File f) {
        try {
            if (!failedDir.exists() && !failedDir.mkdirs() && !failedDir.isDirectory()) {
                throw new IOException("建不了 failed 目录: " + failedDir);
            }
            File target = new File(failedDir, f.getName());
            if (!f.renameTo(target)) {
                // 跨设备 rename 会失败，退化成复制
                java.nio.file.Files.copy(f.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                remove(f);
            }
            LOG.error("待发通知补发次数用尽，标记为未送达: {}", target.getName());
            return target;
        } catch (IOException e) {
            LOG.error("标记未送达失败: {}", e.getMessage());
            return f;
        }
    }

    /** failed 目录里的条数。 */
    public int failedCount() {
        File[] files = failedDir.listFiles();
        return files == null ? 0 : files.length;
    }

    public File dir() {
        return dir;
    }

    private static AlertNotification.Kind kind(String s) {
        try {
            return AlertNotification.Kind.valueOf(s);
        } catch (Exception e) {
            return AlertNotification.Kind.FIRING;
        }
    }

    private static long longOf(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 落盘失败。这是「连待发都存不住」的绝境，必须往上抛。 */
    public static final class SpoolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SpoolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
