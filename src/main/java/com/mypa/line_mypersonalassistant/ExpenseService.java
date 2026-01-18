package com.mypa.line_mypersonalassistant;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.*;
import java.time.*;

/**
 * ExpenseService
 * ----------------
 * 負責記帳資料
 *
 * 目前功能：
 * - 依 userId 儲存多筆支出紀錄
 * - 新增一筆支出/進帳
 * - 列出某使用者的所有支出
 * - 計算某使用者的支出總和
 * - 刪除一筆紀錄
 */

@Service
public class ExpenseService {
    
    public static class Record {
        public int amount;
        public String item;
        public long ts;

        public Record(){}

        public Record(int amount, String item) {
            this.amount = amount;
            this.item = item;
            this.ts = Instant.now().toEpochMilli(); // exact time right now
        }
    }

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    /** 把紀錄的 ts（毫秒）轉成 LA LocalDate */
    private LocalDate toLocalDate(long tsMillis) {
        return Instant.ofEpochMilli(tsMillis).atZone(ZONE).toLocalDate();
    }

    private final ExpenseStorage storage;
    public ExpenseService(ExpenseStorage storage) {
        this.storage = storage;
    }

    /**
     * recordsByUser
     * --------------
     * key   : userId（LINE 的 userId）
     * value : 該使用者的所有支出紀錄
     */
    private Map<String, List<Record>> recordsByUser = new HashMap<>();

    /**
     * @PostConstruct
     * - Spring 建好 ExpenseService 後會自動呼叫一次
     * - 我們在這裡把檔案資料載入到 recordsByUser
     */
    @PostConstruct
    public void init() {
        this.recordsByUser = storage.load();
        System.out.println("✅ Expense 資料已載入，使用者數量: " + recordsByUser.size());
    }

    /**
     * 取得某個 userId 的紀錄清單
     * - 如果不存在，則自動建立一個新的空 List
     */
    private List<Record> getOrCreate(String userId) {
        return recordsByUser.computeIfAbsent(userId, k -> new ArrayList<>());
    }

    /**
     * 新增一筆支出
     *
     * @param userId LINE 使用者 ID
     * @param amount 金額
     * @param item   東西
     */
    public void addExpense(String userId, int amount, String item) {
        getOrCreate(userId).add(new Record(amount, item));
        storage.save(recordsByUser);
    }

    /**
     * 列出某使用者的所有支出紀錄
     */
    public List<Record> list(String userId) {
        return getOrCreate(userId);
    }

    /**
     * 計算某使用者的支出總和
     */
    public int sum(String userId) {
        int total = 0;
        for(Record r : getOrCreate(userId)) {
            total += r.amount;
        }
        return total;
    }

    /** 列出「今天」的所有紀錄 */
    public List<Record> listToday(String userId) {
        LocalDate today = LocalDate.now(ZONE);
        List<Record> result = new ArrayList<>();
        for (Record r : getOrCreate(userId)) {
            if (toLocalDate(r.ts).equals(today)) result.add(r);
        }
        return result;
    }

    /** 計算「今天」總計 */
    public int sumToday(String userId) {
        int total = 0;
        for (Record r : listToday(userId)) total += r.amount;
        return total;
    }

    /** 列出「本月」的所有紀錄 */
    public List<Record> listThisMonth(String userId) {
        YearMonth ym = YearMonth.now(ZONE);
        List<Record> result = new ArrayList<>();
        for (Record r : getOrCreate(userId)) {
            LocalDate d = toLocalDate(r.ts);
            if (YearMonth.from(d).equals(ym)) result.add(r);
        }
        return result;
    }

    /** 計算「本月」總計 */
    public int sumThisMonth(String userId) {
        int total = 0;
        for (Record r : listThisMonth(userId)) total += r.amount;
        return total;
    }

    /**
     * 刪除某使用者的第 index 筆紀錄（index 從 1 開始）
     * @return 被刪掉的紀錄（方便回覆給使用者），如果不存在就回傳 null
     */
    public Record deleteByIndex(String userId, int index1Based) {
        List<Record> list = getOrCreate(userId);

        int idx = index1Based - 1; // 使用者輸入 1,2,3... 轉成 0-based
        if (idx < 0 || idx >= list.size()) return null;

        Record removed = list.remove(idx);
        storage.save(recordsByUser);
        return removed;
    }

}