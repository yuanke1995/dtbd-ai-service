package com.wisesoft.ai.parser;

import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.model.Chunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 解析器（POI）
 * 每个 sheet 转"行列文本"，超长按行切分；sheet 名作标题
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelParser implements DocumentParser {

    private final AiAppProperties properties;

    @Override
    public boolean supports(String ext) {
        return "xlsx".equalsIgnoreCase(ext) || "xls".equalsIgnoreCase(ext);
    }

    @Override
    public List<Chunk> parse(byte[] bytes, String fileName, String docId) throws Exception {
        int maxSize = properties.getChunk().getMaxSize();
        List<Chunk> chunks = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) continue;
                String sheetName = sheet.getSheetName();
                StringBuilder buf = new StringBuilder();
                int rowCount = 0;

                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    boolean hasCell = false;
                    for (Cell cell : row) {
                        String v = cellValue(cell);
                        if (!v.isEmpty()) {
                            if (hasCell) line.append(" | ");
                            line.append(v);
                            hasCell = true;
                        }
                    }
                    if (!hasCell) continue;
                    rowCount++;

                    if (buf.length() + line.length() > maxSize && buf.length() > 0) {
                        chunks.add(new Chunk(sheetName + "（前" + rowCount + "行）", buf.toString().trim(), List.of()));
                        buf.setLength(0);
                    }
                    if (buf.length() > 0) buf.append("\n");
                    buf.append(line);
                }
                if (buf.length() > 0) {
                    chunks.add(new Chunk(sheetName, buf.toString().trim(), List.of()));
                }
            }
        }
        log.info("[Excel] {} 解析出 {} 个分块", fileName, chunks.size());
        return chunks;
    }

    private String cellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }
}
