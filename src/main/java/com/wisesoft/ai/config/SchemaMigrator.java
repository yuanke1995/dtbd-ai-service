package com.wisesoft.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存量库 schema 演进：启动时解析 classpath 的 schema.sql（CREATE TABLE 定义），
 * 与 information_schema.COLUMNS 比对，缺失的列自动 ALTER 补上（幂等：先查再补）。
 * <p>
 * 解决"存量表加列需手动 ALTER"的运维痛点——schema.sql 新增列后，老库启动即自动升级；
 * 补列失败仅告警不阻塞启动（保证只读/降级场景可用）。
 */
@Slf4j
@Component
public class SchemaMigrator implements ApplicationRunner {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS\\s+`([^`]+)`\\s*\\(([\\s\\S]*?)\\)\\s*ENGINE", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_LINE = Pattern.compile(
            "^\\s*`([^`]+)`\\s+(.+?)\\s*,?$");
    private static final List<String> NON_COLUMN_PREFIX = List.of("PRIMARY", "KEY", "UNIQUE", "CONSTRAINT");

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("[SchemaMigrator] schema 自动补列失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    /** 解析 schema.sql → 表名 → (列名 → 列定义)，逐表比对补列 */
    void migrate() {
        Map<String, Map<String, String>> tables = parseSchema();
        if (tables.isEmpty()) {
            log.warn("[SchemaMigrator] 未解析到任何建表定义（classpath schema.sql 不可读？）");
            return;
        }
        for (Map.Entry<String, Map<String, String>> e : tables.entrySet()) {
            String table = e.getKey();
            List<String> existing = queryColumns(table);
            if (existing == null) continue; // 表不存在（新库由 schema.sql 创建）或查询失败
            for (Map.Entry<String, String> col : e.getValue().entrySet()) {
                if (!existing.contains(col.getKey())) {
                    addColumn(table, col.getKey(), col.getValue());
                }
            }
        }
        log.info("[SchemaMigrator] schema 演进检查完成，共 {} 张表", tables.size());
    }

    /** 查表现有列；表不存在返回 null */
    private List<String> queryColumns(String table) {
        try {
            List<String> cols = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    String.class, table);
            return cols;
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 查询表 {} 列失败: {}", table, ex.getMessage());
            return null;
        }
    }

    private void addColumn(String table, String column, String definition) {
        try {
            String sql = "ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition;
            jdbcTemplate.execute(sql);
            log.info("[SchemaMigrator] 自动补列完成: {}.{} = {}", table, column, definition);
        } catch (Exception ex) {
            log.warn("[SchemaMigrator] 补列失败 {}.{}: {}", table, column, ex.getMessage());
        }
    }

    /** 解析 schema.sql 的 CREATE TABLE 块（classpath 读取，兼容 jar 部署） */
    Map<String, Map<String, String>> parseSchema() {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        String content = readSchemaFile();
        if (content == null) return tables;
        Matcher mt = CREATE_TABLE.matcher(content);
        while (mt.find()) {
            String table = mt.group(1);
            String body = mt.group(2);
            Map<String, String> cols = new LinkedHashMap<>();
            for (String line : body.split("\n")) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                if (NON_COLUMN_PREFIX.stream().anyMatch(l::startsWith)) continue;
                Matcher cm = COLUMN_LINE.matcher(l);
                if (cm.matches()) {
                    cols.put(cm.group(1), cm.group(2).trim());
                }
            }
            tables.put(table, cols);
        }
        return tables;
    }

    private String readSchemaFile() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("schema.sql")) {
            if (in == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[SchemaMigrator] schema.sql 读取失败: {}", e.getMessage());
            return null;
        }
    }
}
