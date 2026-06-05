package com.jst.aftersale.task.compensation.enums;

import lombok.Getter;

/**
 * @description: 通用的补偿任务状态
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-09-09 15:15
 */
@Getter
public enum CompensationTaskStatus {

    /**
     * 待处理
     */
    WAITING("WAITING"),

    /**
     * 已处理
     */
    SUCCESS("SUCCESS"),

    /**
     * 已失败
     */
    FAIL("FAIL");

    private final String status;

    CompensationTaskStatus(String status) {
        this.status = status;
    }
}
