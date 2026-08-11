package com.wisesoft.ai.parser;

import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.model.Chunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器（PDFBox 文本抽取）
 * 纯文本抽取（无内嵌图片描述需求），按页合并 + 超长切分，保持与现有分块管线一致
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfParser implements DocumentParser {

    private final AiAppProperties properties;

    @Override
    public boolean supports(String ext) {
        return "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public List<Chunk> parse(byte[] bytes, String fileName, String docId) throws Exception {
        int maxSize = properties.getChunk().getMaxSize();
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder pageBuffer = new StringBuilder();
        String pageTitle = "第 1 页";

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int total = doc.getNumberOfPages();
            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).trim();
                if (text.isEmpty()) continue;

                // 按页累积，超长切分（保证 chunk 粒度与 docx 一致）
                if (pageBuffer.length() + text.length() > maxSize && pageBuffer.length() > 0) {
                    chunks.add(new Chunk(pageTitle, pageBuffer.toString().trim(), List.of()));
                    pageBuffer.setLength(0);
                }
                if (pageBuffer.length() > 0) pageBuffer.append("\n");
                pageBuffer.append(text);
                pageTitle = "第 " + page + " 页";
            }
            if (pageBuffer.length() > 0) {
                chunks.add(new Chunk(pageTitle, pageBuffer.toString().trim(), List.of()));
            }
        }
        log.info("[PDF] {} 解析出 {} 个分块", fileName, chunks.size());
        return chunks;
    }
}
