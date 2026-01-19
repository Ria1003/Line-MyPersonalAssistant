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
    private final ObjectMapper mapper = new ObjectMapper();

    private final Path filePath;

    public ExpenseStorage() {
        String dir = System.getenv().getOrDefault("DATA_DIR", "data");
        this.filePath = Path.of(dir, "expenses.json");
    }

    public Map<String, List<ExpenseService.Record>> load() {
        try {
            if (!Files.exists(filePath)) return new HashMap<>();
            return mapper.readValue(filePath.toFile(), new TypeReference<>() {});
        } catch (Exception e) {
            System.out.println("⚠️ 讀取 expenses.json 失敗，改用空資料: " + e.getMessage());
            return new HashMap<>();
        }
    }

    public void save(Map<String, List<ExpenseService.Record>> data) {
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), data);
        } catch (Exception e) {
            System.out.println("⚠️ 寫入 expenses.json 失敗: " + e.getMessage());
        }
    }
}
