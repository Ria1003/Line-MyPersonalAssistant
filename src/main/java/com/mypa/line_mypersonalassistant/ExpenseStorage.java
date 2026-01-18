package com.mypa.line_mypersonalassistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExpenseStorage {

    // ObjectMapper：Jackson 的核心工具，用來做 JSON <-> Java 物件轉換
    private final ObjectMapper mapper = new ObjectMapper();

    // 檔案位置：專案根目錄下的 data/expenses.json
    private final Path filePath = Path.of("data", "expenses.json");

    /**
     * load()
     * - 程式啟動時呼叫
     * - 如果檔案不存在：回傳空 Map
     * - 如果檔案存在：把 JSON 讀成 Map<String, List<Record>>
     */
    public Map<String, List<ExpenseService.Record>> load() {
        try {
            if (!Files.exists(filePath)) return new HashMap<>();
            return mapper.readValue(
                    filePath.toFile(),
                    new TypeReference<>() {}
            );
        } catch (Exception e) {
            System.out.println("⚠️ 讀取 expenses.json 失敗，改用空資料: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * save(data)
     * - 每次新增記帳後呼叫
     * - 確保 data 資料夾存在
     * - 把 Map 寫回 JSON 檔（pretty print 方便你看）
     */
    public void save(Map<String, List<ExpenseService.Record>> data) {
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), data);
        } catch (Exception e) {
            System.out.println("⚠️ 寫入 expenses.json 失敗: " + e.getMessage());
        }
    }
}
