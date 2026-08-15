package com.wisesoft.ai.parser;

import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.model.Chunk;
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
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
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
    private final ExecutorService visionExecutor;

    public DocxParser(AiAppProperties properties, VisionService visionService) {
        this.properties = properties;
        this.visionService = visionService;
        this.visionExecutor = Executors.newFixedThreadPool(
                Math.max(1, properties.getVision().getConcurrency()));
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
    public List<Chunk> parse(byte[] bytes, String fileName, String docId) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = properties.getChunk().getMaxSize();
        String title = "概述";
        StringBuilder content = new StringBuilder();
        // 当前分块待处理图片（描述异步并发生成，flush 时统一 join）
        List<SavedImage> currentImages = new ArrayList<>();
        // checksum -> 已保存图片（文档内去重，复用 URL 与描述）
        Map<Long, SavedImage> imageCache = new HashMap<>();
        int[] imageCount = {0};

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) element;
                    String text = p.getText().trim();

                    int level = headingLevel(p);
                    if (level > 0 && level <= 3) {
                        // 遇标题 flush 当前块
                        flushChunk(title, content, currentImages, chunks);
                        title = text;
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
                            handlePicture(pic.getPictureData(), docId, currentImages, imageCache, imageCount);
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
                        if (content.length() > 0) content.append("\n");
                        content.append(tableText);
                    }
                }

                // 通用超长 flush（图片描述按平均 40 字估算计入长度，保持原有分块粒度）
                if (content.length() + currentImages.size() * 40 > maxSize) {
                    flushChunk(title, content, currentImages, chunks);
                }
            }
            flushChunk(title, content, currentImages, chunks);
        }
        return chunks;
    }

    /**
     * 将 Word 表格解析为 Markdown 表格文本（保留行/列结构）
     * - 空单元格保留占位（保持列对齐）
     * - 单元格内竖线转义（防止破坏表格语法）
     * - 表头分隔行使用 | --- |
     */
    private StringBuilder buildMarkdownTable(XWPFTable table) {
        List<List<String>> grid = new ArrayList<>();
        int colCount = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String ct = cell.getTextRecursively().trim();
                // 单元格内换行折叠为空格，竖线转义防破坏表格
                ct = ct.replaceAll("\\s+", " ").replace("|", "\\|");
                cells.add(ct);
            }
            colCount = Math.max(colCount, cells.size());
            grid.add(cells);
        }
        if (grid.isEmpty()) return new StringBuilder();

        StringBuilder sb = new StringBuilder();
        // 补齐每行到统一列数（合并单元格/不规则表格），并输出
        for (int r = 0; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            while (row.size() < colCount) row.add("");
            // 先输出当前行，再在首行（表头）之后紧跟分隔行（Markdown 语法：| 表头 | → | --- | → | 数据 |）
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
            if (r == 0 && grid.size() > 1) {
                sb.append("| ").append(String.join(" | ", java.util.Collections.nCopies(colCount, "---"))).append(" |\n");
            }
        }
        return sb;
    }

    /**
     * 处理单张图片：类型过滤 → 去重 → 压缩保存 → 异步并发生成描述
     */
    private void handlePicture(XWPFPictureData data, String docId,
                               List<SavedImage> currentImages,
                               Map<Long, SavedImage> imageCache, int[] imageCount) {
        String ext = data.suggestFileExtension().toLowerCase();
        if (!ALLOWED_EXTS.contains(ext)) {
            log.debug("跳过不支持的图片类型: {}", ext);
            return;
        }

        long checksum = data.getChecksum();
        SavedImage saved = imageCache.get(checksum);
        if (saved == null) {
            try {
                CompressedImage ci = compress(data.getData(), ext);
                String url = persistImage(ci.bytes(), ci.ext(), docId, imageCount[0]);
                CompletableFuture<String> descFuture = CompletableFuture.supplyAsync(
                        () -> visionService.describe(ci.bytes(), ci.ext()), visionExecutor);
                saved = new SavedImage(url, descFuture);
                imageCache.put(checksum, saved);
                imageCount[0]++;
            } catch (IOException e) {
                log.warn("图片保存失败: {}", e.getMessage());
                return;
            }
        }
        currentImages.add(saved);
    }

    /**
     * 图片压缩：等比缩放（最长边超过 max-width 时）+ 重编码
     */
    private CompressedImage compress(byte[] original, String ext) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(original));
            if (img == null) return new CompressedImage(original, ext);

            int maxWidth = properties.getImages().getMaxWidth();
            if (maxWidth > 0 && img.getWidth() > maxWidth) {
                int height = (int) Math.round(img.getHeight() * (double) maxWidth / img.getWidth());
                int type = img.getType() == 0 ? BufferedImage.TYPE_INT_RGB : img.getType();
                BufferedImage scaled = new BufferedImage(maxWidth, height, type);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, maxWidth, height, null);
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
    private void flushChunk(String title, StringBuilder content, List<SavedImage> currentImages, List<Chunk> chunks) {
        if (content.length() == 0 && currentImages.isEmpty()) return;
        String finalContent = content.toString();
        if (!currentImages.isEmpty()) {
            StringBuilder out = new StringBuilder();
            Matcher m = Pattern.compile("\\[图片]").matcher(finalContent);
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
            finalContent = out.toString();
        }
        chunks.add(new Chunk(title, finalContent,
                currentImages.stream().map(SavedImage::url).toList()));
        content.setLength(0);
        currentImages.clear();
    }

    /**
     * 保存图片到 data/images/{docId}/{seq}.{ext}，返回访问 URL
     */
    private String persistImage(byte[] bytes, String ext, String docId, int seq) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        Files.createDirectories(dir);
        String filename = seq + "." + ext;
        Files.write(dir.resolve(filename), bytes);
        return properties.getImages().getUrlPrefix() + "/" + docId + "/" + filename;
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
