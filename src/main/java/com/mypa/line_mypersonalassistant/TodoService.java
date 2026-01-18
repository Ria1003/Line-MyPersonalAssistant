package com.mypa.line_mypersonalassistant;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Service
public class TodoService {

    private final TodoStorage storage;

    // 用 List 存待辦事項（先用記憶體）
    private Map<String, List<String>> todosByUser= new HashMap<>();

    public TodoService(TodoStorage storage) {
        this.storage = storage;
    }

    @PostConstruct
    public void init() {
        this.todosByUser = storage.load();
        System.out.println("✅ Todo 資料已載入，使用者數量: " + todosByUser.size());
    }

    // if the user has not had a list, create a new one
    private List<String> getOrCreateList(String userId) {
        return todosByUser.computeIfAbsent(userId, k -> new ArrayList<>());
    }

    // 新增待辦
    public void addTodo(String userId, String text) {
        getOrCreateList(userId).add(text);
        storage.save(todosByUser);
    }

    // 取得所有待辦
    public List<String> getTodos(String userId) {
        return getOrCreateList(userId);
    }

    // 完成某一筆（index 從 1 開始）
    public String completeTodo(String userId, int index) {
        List<String> list = getOrCreateList(userId);
        if (index < 1 || index > list.size()) return null;
        String removed = list.remove(index - 1);
        storage.save(todosByUser);
        return removed;
    }

    public boolean isEmpty(String userId) {
        return getOrCreateList(userId).isEmpty();
    }
}
