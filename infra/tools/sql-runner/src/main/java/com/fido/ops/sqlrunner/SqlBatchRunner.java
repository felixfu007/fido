package com.fido.ops.sqlrunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 一次性 / 可重複使用的 DevOps 工具：在沒有 sqlcmd.exe 或 Invoke-Sqlcmd 的環境
 * （例如本機 SQL Server LocalDB 開發環境）執行帶有 GO 批次分隔符號的 .sql 檔案。
 *
 * GO 是 SQLCMD / SSMS 專用的批次分隔符號，不是合法的 T-SQL 陳述式，一般 JDBC
 * Statement.execute() 無法直接處理。此工具會先將檔案依「獨立一行、去除頭尾空白後
 * 內容等於 GO（忽略大小寫）」切割成多個批次，再依序透過 JDBC 執行每個批次，並將
 * SQL Server 的 PRINT 訊息（以 SQLWarning 型式回傳）印到 stdout，方便確認執行結果。
 *
 * 用法：
 *   java -jar sql-runner.jar "<JDBC_URL>" file1.sql [file2.sql ...]
 *
 * 任何一個批次執行失敗即中止（fail-fast），並印出是哪個檔案、第幾個批次、以及
 * 該批次的 SQL 內容，方便除錯。
 */
public final class SqlBatchRunner {

    // 獨立一行、去除頭尾空白後等於 GO（可能後面接數字重複次數，例如 "GO 3"，此處不支援重複次數，
    // 因為 infra/sql/*.sql 目前沒有用到這個語法）。
    private static final Pattern GO_LINE = Pattern.compile("(?i)^\\s*GO\\s*$");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java -jar sql-runner.jar \"<JDBC_URL>\" file1.sql [file2.sql ...]");
            System.exit(2);
        }

        String jdbcUrl = args[0];
        List<Path> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            files.add(Path.of(args[i]));
        }

        System.out.println("[sql-runner] 連線至: " + maskUrl(jdbcUrl));
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            System.out.println("[sql-runner] 連線成功。SQL Server 版本: "
                    + conn.getMetaData().getDatabaseProductVersion());

            for (Path file : files) {
                runFile(conn, file);
            }
        }

        System.out.println("[sql-runner] 全部檔案執行完成。");
    }

    private static void runFile(Connection conn, Path file) throws IOException, SQLException {
        System.out.println();
        System.out.println("========== 執行檔案: " + file + " ==========");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 去除可能存在的 UTF-8 BOM
        if (!content.isEmpty() && content.charAt(0) == '﻿') {
            content = content.substring(1);
        }

        List<String> batches = splitByGo(content);
        System.out.println("[sql-runner] 共切割出 " + batches.size() + " 個批次。");

        try (Statement stmt = conn.createStatement()) {
            for (int i = 0; i < batches.size(); i++) {
                String batch = batches.get(i);
                if (batch.isBlank()) {
                    continue;
                }
                int batchNo = i + 1;
                try {
                    boolean hasResultSet = stmt.execute(batch);
                    printMessages(stmt, batchNo);
                    if (hasResultSet) {
                        // 目前 infra/sql/*.sql 皆為 DDL/PRINT，理論上不會有 result set，
                        // 若真的出現，僅記錄一下，不特別處理內容。
                        System.out.println("[batch " + batchNo + "] 回傳了一個 result set（已略過內容）。");
                    }
                } catch (SQLException e) {
                    System.err.println("[sql-runner] 檔案 " + file + " 第 " + batchNo + " 個批次執行失敗:");
                    System.err.println("---- 批次內容 ----");
                    System.err.println(batch);
                    System.err.println("------------------");
                    throw new RuntimeException("批次執行失敗於 " + file + " 批次 #" + batchNo, e);
                }
            }
        }
        System.out.println("========== 檔案完成: " + file + " ==========");
    }

    private static void printMessages(Statement stmt, int batchNo) throws SQLException {
        SQLWarning w = stmt.getWarnings();
        while (w != null) {
            String msg = w.getMessage();
            if (msg != null && !msg.isBlank()) {
                System.out.println("[batch " + batchNo + "] " + msg.strip());
            }
            w = w.getNextWarning();
        }
        stmt.clearWarnings();
    }

    private static List<String> splitByGo(String content) {
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = content.split("\r\n|\n|\r", -1);
        for (String line : lines) {
            if (GO_LINE.matcher(line).matches()) {
                batches.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(line).append('\n');
            }
        }
        if (!current.toString().isBlank()) {
            batches.add(current.toString());
        }
        return batches;
    }

    private static String maskUrl(String url) {
        // 目前連線字串採用 integratedSecurity，不含密碼，但保留遮罩邏輯以防未來改用帳密。
        return url.replaceAll("(?i)password=[^;]*", "password=****");
    }
}
