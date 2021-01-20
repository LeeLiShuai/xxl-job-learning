package com.xxl.job.core.enums;

/**
 * 阻塞策略，执行任务时，同一个id的上一个任务还没有执行完
 * Created by xuxueli on 17/5/9.
 */
public enum ExecutorBlockStrategyEnum {
    //串行
    SERIAL_EXECUTION("Serial execution"),
    //丢弃后续调度
    DISCARD_LATER("Discard Later"),
    //覆盖前一个调度
    COVER_EARLY("Cover Early");

    private String title;
    private ExecutorBlockStrategyEnum (String title) {
        this.title = title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
    public String getTitle() {
        return title;
    }

    public static ExecutorBlockStrategyEnum match(String name, ExecutorBlockStrategyEnum defaultItem) {
        if (name != null) {
            for (ExecutorBlockStrategyEnum item:ExecutorBlockStrategyEnum.values()) {
                if (item.name().equals(name)) {
                    return item;
                }
            }
        }
        return defaultItem;
    }
}
