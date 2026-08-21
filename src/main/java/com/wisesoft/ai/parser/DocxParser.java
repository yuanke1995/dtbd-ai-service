package com.wisesoft.ai.parser;

import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.model.Chunk;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.VisionService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word(docx) 解析器
 * 段落/表格/标题层级分块 + 图片提取（压缩保存 + 视觉并发描述）
 * 从原 DocumentService 迁移，保持行为一致
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DocxParser implements DocumentParser {

    /** 允许提取的图片类型（浏览器与视觉模型可识别），EMF/WMF/PICT 等矢量图跳过 */
    private static final Set<String> ALLOWED_EXTS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final AiAppProperties properties;
    private final VisionService visionService;
    private final ConfigService configService;
    private final ExecutorService visionExecutor;
    /** 图片描述并发限流（容量随配置动态调整，保存即生效） */
    private volatile Semaphore visionSemaphore;

    public DocxParser(AiAppProperties properties, VisionService visionService, ConfigService configService) {
        this.properties = properties;
        this.visionService = visionService;
        this.configService = configService;
        this.visionSemaphore = new Semaphore(Math.max(1, properties.getVision().getConcurrency()));
        // 有界线程池（替代 cached：图片多时 cached 会为每张图建阻塞线程，线程数随图片数膨胀）
        // 队列 = 并发×2，满则拒绝并降级为无描述（图片仍展示），避免资源失控
        int c = Math.max(1, properties.getVision().getConcurrency());
        this.visionExecutor = new ThreadPoolExecutor(c, c, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(c * 2), r -> {
            Thread t = new Thread(r, "vision-desc");
            t.setDaemon(true);
            return t;
        });
    }

    /** 动态并发信号量：配置变更时重建（cached 线程池 + 信号量限流，保存即生效） */
    private Semaphore visionSemaphore() {
        int want = Math.max(1, configService.getInt("vision.concurrency"));
        Semaphore s = visionSemaphore;
        if (s.availablePermits() != want) {
            synchronized (this) {
                if (visionSemaphore.availablePermits() != want) {
                    visionSemaphore = new Semaphore(want);
                    s = visionSemaphore;
                    log.info("[DocxParser] 图片描述并发调整: {} -> {}", s.availablePermits(), want);
                }
            }
        }
        return s;
    }

    @PreDestroy
    public void shutdown() {
        visionExecutor.shutdown();
    }

    @Override
    public boolean supports(String ext) {
        return "docx".equalsIgnoreCase(ext);
    }

    @Override
    public java.util.Set<String> supportedExts() {
        return java.util.Set.of("docx");
    }

    @Override
    public List<Chunk> parse(byte[] bytes, String fileName, String docId) throws IOException {
        return parse(bytes, fileName, docId, null);
    }

    @Override
    public List<Chunk> parse(byte[] bytes, String fileName, String docId, ParseProgress progress) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = properties.getChunk().getMaxSize();
        // 结构感知切分（chunk.structural 可配，默认开）：标题栈 → 章节路径；边界优先阈值 = maxSize × ratio
        boolean structural = configService.getBoolean("chunk.structural");
        double ratio = Math.min(1.0, Math.max(0.5, configService.getDouble("chunk.structuralRatio", 0.8)));
        int boundaryThreshold = (int) (maxSize * ratio);
        String title = "概述";
        StringBuilder content = new StringBuilder();
        // 标题栈（结构感知：章节路径注入用；Deque 末尾为当前章节）
        ArrayDeque<String> titleStack = new ArrayDeque<>();
        String titlePath = "";
        // 当前分块待处理图片（描述异步并发生成，flush 时统一 join）
        List<SavedImage> currentImages = new ArrayList<>();
        // checksum -> 已保存图片（文档内去重，复用 URL 与描述）
        Map<Long, SavedImage> imageCache = new HashMap<>();
        int[] imageCount = {0};
        // 图片识别进度：预扫描总数，每张描述完成上报（10→30 区间由调用方映射）
        ImageProgress imageProgress = null;
        if (progress != null) {
            int total = 0;
            try (XWPFDocument scan = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
                total = countImages(scan);
            } catch (Exception e) {
                log.warn("图片预扫描失败，跳过图片进度上报: {}", e.getMessage());
            }
            imageProgress = new ImageProgress(total, progress);
        }

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) element;
                    String text = p.getText().trim();

                    int level = headingLevel(p);
                    if (level > 0 && level <= 3) {
                        // 遇标题 flush 当前块（结构模式：按层级维护标题栈生成章节路径）
                        flushChunk(title, content, currentImages, chunks, titlePath);
                        if (structural) {
                            while (!titleStack.isEmpty() && titleStack.size() >= level) titleStack.pollLast();
                            titleStack.addLast(text);
                            title = text;
                            titlePath = titleStack.size() >= 2 ? String.join(" > ", titleStack) : "";
                        } else {
                            title = text;
                        }
                        continue;
                    }

                    // 正文文本
                    if (!text.isEmpty()) {
                        if (content.length() > 0) content.append("\n");
                        content.append(text);
                    }

                    // 段落内嵌图片（图片独立成段时 text 为空，也要进块）
                    // 占位符 [图片] 立即写入原文位置，flush 时按序替换为 [图片：描述]
                    for (XWPFRun run : p.getRuns()) {
                        for (XWPFPicture pic : run.getEmbeddedPictures()) {
                            handlePicture(pic.getPictureData(), docId, currentImages, imageCache, imageCount, imageProgress);
                            if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
                                content.append("\n");
                            }
                            content.append("[图片]");
                        }
                    }
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    // 表格：解析为 Markdown 表格（保留行/列结构，LLM 可理解、前端可渲染）
                    XWPFTable table = (XWPFTable) element;
                    StringBuilder tableText = buildMarkdownTable(table);
                    if (tableText.length() > 0) {
                        if (structural) {
                            // 结构模式：表格独立成块（不与其他段落混杂，保持结构语义）；超长表格按行拆（重复表头/重开围栏，保持语法有效）
                            flushChunk(title, content, currentImages, chunks, titlePath);
                            addTableChunks(title, tableText.toString(), titlePath, chunks, maxSize);
                        } else {
                            if (content.length() > 0) content.append("\n");
                            content.append(tableText);
                        }
                    }
                }

                // 超长 flush（图片描述按平均 40 字估算计入长度）
                int estimated = content.length() + currentImages.size() * 40;
                if (structural) {
                    // 结构模式：≥maxSize 硬切（按段落/句边界拆多块，单块 ≤maxSize）；[阈值,maxSize) 在段落边界断块
                    if (estimated >= maxSize) {
                        flushStructural(title, content, currentImages, chunks, titlePath, maxSize);
                    } else if (estimated >= boundaryThreshold) {
                        flushChunk(title, content, currentImages, chunks, titlePath);
                    }
                } else if (estimated > maxSize) {
                    flushChunk(title, content, currentImages, chunks, titlePath);
                }
            }
            flushChunk(title, content, currentImages, chunks, titlePath);
        }
        return chunks;
    }

    /**
     * 将 Word 表格解析为 Markdown：
     * - 单列表格（作者常用表格排版代码/命令/模板）→ 输出为代码块，保留原始换行
     * - 多列表格 → Markdown 表格（保留行/列结构），单元格内多段改用 &lt;br&gt; 保留换行
     * - 单元格内竖线转义（防止破坏表格语法）
     */
    private StringBuilder buildMarkdownTable(XWPFTable table) {
        List<List<String>> grid = new ArrayList<>();
        int colCount = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                // 逐段落提取，保留单元格内换行（getTextRecursively 会丢失段落分隔，导致多行命令粘连）
                StringBuilder ct = new StringBuilder();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    String t = p.getText().trim();
                    if (t.isEmpty()) continue;
                    if (ct.length() > 0) ct.append('\n');
                    ct.append(t);
                }
                cells.add(ct.toString());
            }
            colCount = Math.max(colCount, cells.size());
            grid.add(cells);
        }
        if (grid.isEmpty()) return new StringBuilder();

        // 单列表格：极可能是"代码/命令/模板容器"，输出为代码块（LLM 识别为代码、前端渲染代码样式）
        if (colCount == 1) {
            StringBuilder code = new StringBuilder("```\n");
            for (List<String> row : grid) {
                for (String c : row) {
                    if (c.isEmpty()) continue;
                    code.append(c).append('\n');
                }
            }
            code.append("```");
            return code;
        }

        StringBuilder sb = new StringBuilder();
        // 补齐每行到统一列数（合并单元格/不规则表格），并输出
        for (int r = 0; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            while (row.size() < colCount) row.add("");
            // 单元格内换行用 <br>（Markdown 表格标准写法），竖线转义防破坏语法
            List<String> escaped = new ArrayList<>();
            for (String c : row) {
                escaped.add(c.replace("|", "\\|").replace("\n", "<br>"));
            }
            // 先输出当前行，再在首行（表头）之后紧跟分隔行（Markdown 语法：| 表头 | → | --- | → | 数据 |）
            sb.append("| ").append(String.join(" | ", escaped)).append(" |\n");
            if (r == 0 && grid.size() > 1) {
                sb.append("| ").append(String.join(" | ", java.util.Collections.nCopies(colCount, "---"))).append(" |\n");
            }
        }
        return sb;
    }

    /**
     * 处理单张图片：类型过滤 → 数量上限 → 去重 → 压缩保存 → 异步并发生成描述
     */
    private void handlePicture(XWPFPictureData data, String docId,
                               List<SavedImage> currentImages,
                               Map<Long, SavedImage> imageCache, int[] imageCount,
                               ImageProgress imageProgress) {
        String ext = data.suggestFileExtension().toLowerCase();
        if (!ALLOWED_EXTS.contains(ext)) {
            log.debug("跳过不支持的图片类型: {}", ext);
            return;
        }

        // 数量上限截断（防图片爆炸导致视觉描述数小时；配置保存即生效）
        int maxImages = configService.getInt("chunk.maxImages");
        if (maxImages > 0 && imageCount[0] >= maxImages) {
            if (imageCount[0] == maxImages) {
                log.warn("[{}] 图片数量达到上限 {}，后续图片不再提取描述", docId, maxImages);
                imageCount[0]++;  // 只记一次 warn
            }
            return;
        }

        long checksum = data.getChecksum();
        SavedImage saved = imageCache.get(checksum);
        if (saved == null) {
            try {
                // 双图策略：压缩图只进内存供视觉模型识别（不落盘）；原图落盘用于回答/知识库展示（保持清晰）
                CompressedImage ci = compress(data.getData(), ext);
                // 内容寻址文件名（sha256 原图字节）：同图跨重解析 URL 稳定 → 块 images 列表稳定 → 块级 diff 可复用；变更图生成新文件
                String url = persistImage(data.getData(), ext, docId, sha256Hex(data.getData()));
                // 并发限流（动态信号量，保存即生效）
                Semaphore sem = visionSemaphore();
                sem.acquire();
                CompletableFuture<String> descFuture;
                try {
                    descFuture = CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return visionService.describe(ci.bytes(), ci.ext());
                                } finally {
                                    sem.release();
                                }
                            }, visionExecutor);
                } catch (RejectedExecutionException e) {
                    // 图片描述队列已满：释放许可并降级为无描述（图片仍落盘展示，不阻断解析）
                    sem.release();
                    log.warn("[{}] 图片描述任务队列已满，该图降级为无描述", docId);
                    descFuture = CompletableFuture.completedFuture(null);
                }
                // 图片识别进度：每张描述完成（成功/失败均计数）上报一次
                if (imageProgress != null) {
                    final ImageProgress p = imageProgress;
                    descFuture.whenComplete((d, ex) -> {
                        p.done();
                        p.report();
                    });
                }
                saved = new SavedImage(url, descFuture);
                imageCache.put(checksum, saved);
                imageCount[0]++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("图片描述并发等待被中断: {}", e.getMessage());
                return;
            } catch (IOException e) {
                log.warn("图片保存失败: {}", e.getMessage());
                return;
            }
        }
        currentImages.add(saved);
    }

    /**
     * 图片识别进度计数器（每张描述完成上报一次；跨线程安全）
     * 进度映射 10→30 区间，desc="识别图片 k/total"
     */
    private static final class ImageProgress {
        private final int total;
        private final ParseProgress callback;
        private final AtomicInteger done = new AtomicInteger(0);

        ImageProgress(int total, ParseProgress callback) {
            this.total = Math.max(1, total);
            this.callback = callback;
        }

        void done() { done.incrementAndGet(); }

        void report() {
            if (callback == null) return;
            int k = Math.min(done.get(), total);
            callback.onProgress(10 + 20 * k / total, "识别图片 " + k + "/" + total);
        }
    }

    /**
     * 预扫描文档段落内嵌图片总数（与 handlePicture 同构过滤：类型→去重→maxImages 截断），供进度上报
     */
    private int countImages(XWPFDocument document) {
        int total = 0;
        int maxImages = configService.getInt("chunk.maxImages");
        Set<Long> seen = new HashSet<>();
        outer:
        for (IBodyElement element : document.getBodyElements()) {
            if (element.getElementType() != BodyElementType.PARAGRAPH) continue;
            for (XWPFRun run : ((XWPFParagraph) element).getRuns()) {
                for (XWPFPicture pic : run.getEmbeddedPictures()) {
                    XWPFPictureData data = pic.getPictureData();
                    String ext = data.suggestFileExtension().toLowerCase();
                    if (!ALLOWED_EXTS.contains(ext)) continue;
                    if (!seen.add(data.getChecksum())) continue;
                    total++;
                    if (maxImages > 0 && total >= maxImages) break outer;
                }
            }
        }
        return total;
    }

    /**
     * 图片压缩：等比缩放（最长边超过 max-width 时）+ 重编码
     */
    private CompressedImage compress(byte[] original, String ext) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(original));
            if (img == null) return new CompressedImage(original, ext);

            int maxWidth = properties.getImages().getMaxWidth();
            // 按最长边缩放（宽或高超限都等比缩小，竖长图不再绕过）
            int longest = Math.max(img.getWidth(), img.getHeight());
            if (maxWidth > 0 && longest > maxWidth) {
                int w = (int) Math.round(img.getWidth() * (double) maxWidth / longest);
                int h = (int) Math.round(img.getHeight() * (double) maxWidth / longest);
                int type = img.getType() == 0 ? BufferedImage.TYPE_INT_RGB : img.getType();
                BufferedImage scaled = new BufferedImage(w, h, type);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img, 0, 0, w, h, null);
                g.dispose();
                img = scaled;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (img.getColorModel().hasAlpha()) {
                ImageIO.write(img, "png", out);
                return new CompressedImage(out.toByteArray(), "png");
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(properties.getImages().getQuality());
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(img);
            } finally {
                writer.dispose();
            }
            return new CompressedImage(out.toByteArray(), "jpg");
        } catch (Exception e) {
            log.debug("图片压缩失败，使用原图: {}", e.getMessage());
            return new CompressedImage(original, ext);
        }
    }

    /**
     * flush 当前分块：join 并发描述结果，并将 content 中 [图片] 占位按序替换为 [图片：描述]
     * （占位在解析时写入原文位置，因此图文天然交错）
     */
    private void flushChunk(String title, StringBuilder content, List<SavedImage> currentImages, List<Chunk> chunks, String titlePath) {
        if (content.length() == 0 && currentImages.isEmpty()) return;
        chunks.add(buildChunk(title, resolveImagePlaceholders(content.toString(), currentImages),
                imageUrls(currentImages), titlePath));
        content.setLength(0);
        currentImages.clear();
    }

    /**
     * 结构模式超长块硬切：内容 ≤maxSize 单块；超长按段落/句边界拆成多块（单块 ≤maxSize，预留路径前缀空间），
     * 图片 URL 按各段 [图片] 占位出现顺序切片对应
     */
    private void flushStructural(String title, StringBuilder content, List<SavedImage> currentImages,
                                 List<Chunk> chunks, String titlePath, int maxSize) {
        if (content.length() == 0 && currentImages.isEmpty()) return;
        String resolved = resolveImagePlaceholders(content.toString(), currentImages);
        String prefix = buildPathPrefix(titlePath);
        if (resolved.length() <= maxSize) {
            chunks.add(new Chunk(title, prefix + resolved, imageUrls(currentImages)));
        } else {
            int splitMax = Math.max(200, maxSize - prefix.length());
            List<String> urls = imageUrls(currentImages);
            int imgIdx = 0;
            for (String seg : splitByBoundaries(resolved, splitMax)) {
                List<String> segUrls = new ArrayList<>();
                for (int i = 0; i < countOccurrences(seg, "[图片") && imgIdx < urls.size(); i++) {
                    segUrls.add(urls.get(imgIdx++));
                }
                chunks.add(new Chunk(title, prefix + seg, segUrls));
            }
        }
        content.setLength(0);
        currentImages.clear();
    }

    /** 结构模式表格入块：≤maxSize 单块；超长按行拆（代码块表格重开/闭合围栏，Markdown 表格重复表头） */
    private void addTableChunks(String title, String tableText, String titlePath, List<Chunk> chunks, int maxSize) {
        String prefix = buildPathPrefix(titlePath);
        if (tableText.length() <= maxSize) {
            chunks.add(new Chunk(title, prefix + tableText, List.of()));
            return;
        }
        for (String seg : splitTableRows(tableText, maxSize)) {
            chunks.add(new Chunk(title, prefix + seg, List.of()));
        }
    }

    /**
     * 超长文本按边界硬切：段落（\n）为单元贪心打包；超长段落按句（。！？；）拆；
     * 单句仍超长按字符硬切（兜底）
     */
    private List<String> splitByBoundaries(String text, int max) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n", -1)) {
            if (para.isEmpty()) continue;
            List<String> pieces = para.length() <= max ? List.of(para) : splitLongPara(para, max);
            for (String piece : pieces) {
                if (cur.length() > 0 && cur.length() + piece.length() > max) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append("\n");
                cur.append(piece);
            }
        }
        if (cur.length() > 0) result.add(cur.toString());
        return result;
    }

    /** 超长段落按句拆（保留标点），单句仍超长按字符硬切 */
    private List<String> splitLongPara(String para, int max) {
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : splitSentences(para)) {
            if (s.length() > max) {
                if (cur.length() > 0) {
                    pieces.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < s.length(); i += max) {
                    pieces.add(s.substring(i, Math.min(s.length(), i + max)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + s.length() > max) {
                pieces.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(s);
        }
        if (cur.length() > 0) pieces.add(cur.toString());
        return pieces;
    }

    /** 按句末标点拆句（保留标点）；[图片...] 标记内的标点不拆；无标点返回整段 */
    private List<String> splitSentences(String para) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inMarker = false;
        for (int i = 0; i < para.length(); i++) {
            char c = para.charAt(i);
            cur.append(c);
            if (c == '[' && !inMarker) inMarker = true;
            else if (c == ']' && inMarker) inMarker = false;
            if (!inMarker && (c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';')) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.isEmpty() ? List.of(para) : out;
    }

    /** 超长表格按行拆：代码块表格（``` 包裹）重开/闭合围栏；Markdown 表格每段重复表头+分隔行 */
    private List<String> splitTableRows(String tableText, int max) {
        String[] lines = tableText.split("\n", -1);
        if (tableText.startsWith("```")) {
            List<String> rows = new ArrayList<>();
            for (int i = 1; i < lines.length - 1; i++) rows.add(lines[i]);
            List<String> result = new ArrayList<>();
            StringBuilder cur = new StringBuilder("```\n");
            for (String r : rows) {
                if (cur.length() > 4 && cur.length() + r.length() + 4 > max) {
                    cur.append("```");
                    result.add(cur.toString());
                    cur = new StringBuilder("```\n");
                }
                if (cur.length() > 4) cur.append("\n");
                cur.append(r);
            }
            if (cur.length() > 4) {
                cur.append("```");
                result.add(cur.toString());
            }
            return result;
        }
        // Markdown 表格：表头行 + 分隔行作为每段的重复前缀（保持每段都是合法表格）
        if (lines.length < 2) return List.of(tableText);
        String header = lines[0] + "\n" + lines[1] + "\n";
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder(header);
        for (int i = 2; i < lines.length; i++) {
            String r = lines[i];
            if (r.isBlank()) continue;
            if (cur.length() > header.length() && cur.length() + r.length() + 1 > max) {
                result.add(cur.toString());
                cur = new StringBuilder(header);
            }
            cur.append(r).append("\n");
        }
        if (cur.length() > header.length()) result.add(cur.toString());
        return result;
    }

    /** [图片] 占位按序替换为 [图片：描述]（join 并发描述结果） */
    private String resolveImagePlaceholders(String rawContent, List<SavedImage> currentImages) {
        if (currentImages.isEmpty()) return rawContent;
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\\[图片]").matcher(rawContent);
        int imgIdx = 0;
        while (m.find() && imgIdx < currentImages.size()) {
            SavedImage img = currentImages.get(imgIdx++);
            String desc = img.descFuture().join();
            m.appendReplacement(out, desc.isBlank()
                    ? "[图片]"
                    : "[图片：" + Matcher.quoteReplacement(desc) + "]");
        }
        m.appendTail(out);
        if (imgIdx != currentImages.size()) {
            log.warn("图片占位与图片数不一致: 占位{} 图片{}", imgIdx, currentImages.size());
        }
        return out.toString();
    }

    /** 章节路径前缀（≥2 级才注入——参与向量语义匹配；title 保持短标题避免 titleBonus 误发） */
    private String buildPathPrefix(String titlePath) {
        return titlePath == null || titlePath.isBlank() ? "" : "【上下文】" + titlePath + "\n\n";
    }

    /** 组装分块：章节路径前缀 + 内容 + 图片 URL 列表 */
    private Chunk buildChunk(String title, String text, List<String> images, String titlePath) {
        return new Chunk(title, buildPathPrefix(titlePath) + text, images);
    }

    private List<String> imageUrls(List<SavedImage> currentImages) {
        return currentImages.stream().map(SavedImage::url).toList();
    }

    private int countOccurrences(String text, String sub) {
        if (text == null || sub.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 保存图片到 data/images/{docId}/{sha256}.{ext}（内容寻址），返回访问 URL
     * 同内容图片天然同路径：跨重解析 URL 稳定，复用块引用有效；变更图片自动落新文件
     */
    private String persistImage(byte[] bytes, String ext, String docId, String hash) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        Files.createDirectories(dir);
        String filename = hash + "." + ext;
        Files.write(dir.resolve(filename), bytes);
        return properties.getImages().getUrlPrefix() + "/" + docId + "/" + filename;
    }

    /** 图片字节 SHA-256（hex） */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // MessageDigest 正常不会失败；极端兜底保证文件名唯一
            return "img" + Long.toHexString(System.nanoTime());
        }
    }

    /**
     * 检测段落标题层级：样式名 → 大纲级别 → 0（正文）
     */
    private int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style != null) {
            String s = style.toLowerCase();
            if (s.contains("heading1") || s.contains("标题1") || s.equals("1")) return 1;
            if (s.contains("heading2") || s.contains("标题2") || s.equals("2")) return 2;
            if (s.contains("heading3") || s.contains("标题3") || s.equals("3")) return 3;
        }
        try {
            var pPr = p.getCTP().getPPr();
            if (pPr != null && pPr.isSetOutlineLvl()) {
                int outlineLvl = pPr.getOutlineLvl().getVal().intValue();
                if (outlineLvl >= 0 && outlineLvl <= 2) {
                    return outlineLvl + 1;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 已保存图片：URL + 异步生成的描述（flush 时 join） */
    private record SavedImage(String url, CompletableFuture<String> descFuture) {}

    /** 压缩后图片 */
    private record CompressedImage(byte[] bytes, String ext) {}
}
