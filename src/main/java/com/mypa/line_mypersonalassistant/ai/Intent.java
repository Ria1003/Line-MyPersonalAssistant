package com.mypa.line_mypersonalassistant.ai;

/**
 * High-level actions the bot can execute.
 *
 * Keep these aligned with your controller/services.
 */
public enum Intent {
    // Todo
    ADD_TODO,
    LIST_TODO,
    DONE_TODO,

    // Expense
    ADD_EXPENSE,
    EXPENSE_LIST,
    EXPENSE_SUM,
    EXPENSE_DELETE,

    // Convenience views
    TODAY,
    MONTH,

    // Reminders
    REMIND_CREATE,
    REMIND_LIST,
    REMIND_DELETE,

    // Help
    HELP,

    // Fallback
    UNKNOWN
}
