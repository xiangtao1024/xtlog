package top.xtcoder.xtlog.ingest;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.schedule.XtScheduled;

import top.xtcoder.xtlog.service.AppService;

/**
 * 文件日志采集器：周期扫描 source_type=FILE 应用的配置路径（文件或目录），
 * 记录已读字节偏移，增量读取，解码后交给 LogTextIngestor 做归一化与摄入。
 * 轮转检测：size 小于已读偏移时判定为重建/轮转，从头读取。
 *
 * 编码处理：日志文件的编码由「写日志的应用」决定，服务端无法强制。
 * UTF-8 严格解码成功即按 UTF-8；失败则回退 GBK（覆盖中文 Windows 上
 * Java/Python 默认编码产出的日志），最后再宽松回退 UTF-8，保证不丢数据。
 *
 * 多行合并、时间戳解析、级别/logger 推断等文本语义全部委托给 LogTextIngestor，
 * 与命令采集器共用同一套逻辑，避免两份实现分叉。
 */
@Component
public class FileCollector {

    /** 文件读取缓冲上限：单次最多消费 8MB */
    private static final long MAX_READ = 8L * 1024 * 1024;

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Charset GBK = Charset.forName("GBK");

    @Autowired
    private AppService appService;
    @Autowired
    private LogTextIngestor ingestor;

    /** path(绝对路径) -> 已读字节偏移 */
    private final ConcurrentHashMap<String, Long> offsets = new ConcurrentHashMap<String, Long>();

    @XtScheduled(fixedDelay = 2000)
    public void scan() {
        for (AppService.AppInfo app : appService.getAllApps()) {
            if (!"FILE".equalsIgnoreCase(app.sourceType)) {
                continue;
            }
            if (!app.enabled) {
                continue; // 已停用：不采集
            }
            if (app.filePaths == null || app.filePaths.length() == 0) {
                continue;
            }
            for (String p : parsePaths(app.filePaths)) {
                collect(app, p);
            }
        }
    }

    private List<String> parsePaths(String json) {
        List<String> result = new ArrayList<String>();
        String s = json.trim();
        if (s.startsWith("[")) {
            boolean inStr = false;
            StringBuilder cur = new StringBuilder();
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    inStr = !inStr;
                } else if (inStr) {
                    cur.append(c);
                } else if (c == ',' && !inStr) {
                    if (cur.length() > 0) {
                        result.add(cur.toString());
                        cur.setLength(0);
                    }
                }
            }
            if (cur.length() > 0) {
                result.add(cur.toString());
            }
        } else {
            for (String line : s.split("\n")) {
                line = line.trim();
                if (line.length() > 0) {
                    result.add(line);
                }
            }
        }
        return result;
    }

    private void collect(AppService.AppInfo app, String path) {
        File file = new File(path);
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File newest = null;
            File[] files = file.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified())) {
                    newest = f;
                }
            }
            if (newest != null) {
                collectFile(app, newest);
            }
            return;
        }
        collectFile(app, file);
    }

    private void collectFile(AppService.AppInfo app, File file) {
        String key = file.getAbsolutePath();
        long offset = offsets.getOrDefault(key, 0L);
        long len = file.length();
        if (len < offset) {
            offset = 0L; // 轮转/重建
        }
        if (len == offset) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            long remain = len - offset;
            int cap = (int) Math.min(remain, MAX_READ);
            byte[] buf = new byte[cap];
            int read = raf.read(buf);
            if (read <= 0) {
                return;
            }
            // 只消费到最后一个换行为止：保证字节偏移总是落在字符边界上，
            // 既不会截断多字节字符，也不会重复消费半行。
            int end = read;
            while (end > 0 && buf[end - 1] != '\n') {
                end--;
            }
            if (end == 0) {
                return;
            }
            long newOffset = offset + end;
            String text = decode(buf, end);
            offsets.put(key, newOffset);
            ingestor.ingestText(app, text);
        } catch (IOException e) {
            // 单文件读取错误忽略
        }
    }

    /**
     * 按「UTF-8 严格 → GBK 严格 → UTF-8 宽松」的顺序解码。
     * 由于传入的字节序列必定以换行（单字节 ASCII）结尾、且从字符边界开始，
     * 严格解码不会因跨读取边界而误判。
     */
    private String decode(byte[] buf, int len) {
        String s = strictDecode(buf, len, UTF8);
        if (s != null) {
            return s;
        }
        s = strictDecode(buf, len, GBK);
        if (s != null) {
            return s;
        }
        return new String(buf, 0, len, UTF8);
    }

    private static String strictDecode(byte[] buf, int len, Charset cs) {
        CharsetDecoder decoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(buf, 0, len)).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
