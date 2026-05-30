package com.cna.test;
import java.sql.*;
public class CheckDB {
    public static void main(String[] args) throws Exception {
        String db = args.length > 0 ? args[0] : "guild1.db";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            System.out.println("=== " + db + " ===");
            ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
            while (rs.next()) System.out.println("  TABLE: " + rs.getString(1));
            // Check Feeling_Dimensions columns
            try { s.execute("SELECT status, llm_notes FROM Feeling_Dimensions LIMIT 1");
                System.out.println("  Feeling_Dimensions: 已有 status, llm_notes 列");
            } catch (SQLException e) {
                System.out.println("  Feeling_Dimensions: 缺 status/llm_notes 列 (将被 ensureColumn 自动补齐)");
            }
        }
    }
}
