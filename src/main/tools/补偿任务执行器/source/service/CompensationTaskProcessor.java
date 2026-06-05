package com.jst.aftersale.task.compensation.service;

import com.jst.aftersale.task.compensation.model.po.CompensationTaskLogPo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @description: 补偿任务处理器
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-09-07 16:11
 */
public interface CompensationTaskProcessor {

    /**
     * 获取任务类型
     * @return 任务类型
     */
    String getTaskType();
    /**
     * 获取待执行任务的状态
     */
    List<String> getWaitingStatus();

    /**
     * 获取失败状态
     * @return 失败状态
     */
    String failStatus();

    /**
     * 获取成功状态
     * @param nextStatus 下一个状态
     * @return 成功状态
     */
    Boolean isSuccess(String nextStatus);

    /**
     * 处理任务
     * @param task 任务
     * @param currentStatus 当前状态
     * @return 下一个状态
     */
    String process(CompensationTaskLogPo task, String currentStatus);

    /**
     * 校验最大次数
     * @param task 任务
     * @return 超过最大次数返回 false 否则 true
     */
    Boolean checkMaxRetryCount(CompensationTaskLogPo task);

    /**
     * 下次执行时间
     * @param task 任务
     * @param currentStatus 当前状态
     * @return 下次执行时间
     */
    LocalDateTime nextExecutionTime(CompensationTaskLogPo task, String currentStatus);

    /**
     * 处理超过重试次数回调
     * @param task
     */
    default void maxRetryCountCallback(CompensationTaskLogPo task) {
    }
    /**
     * 异常回调
     * @param task 任务
     * @param e 异常信息
     */
    default void exceptionCallback(CompensationTaskLogPo task, Exception e) {
        throw new RuntimeException(e);
    };

    /**
     * 处理失败回调
     * @param task
     * @param status
     */
    default void failCallback(CompensationTaskLogPo task, String status) {
    }

    /**
     * 处理自旋处理次数超限回调
     */
    default void spinCountLimitCallback(CompensationTaskLogPo task) {}
}
