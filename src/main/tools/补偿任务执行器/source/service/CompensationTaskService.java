package com.jst.aftersale.task.compensation.service;

import com.jst.aftersale.task.compensation.model.bo.CompensationTaskBo;
import com.jst.aftersale.task.compensation.model.po.CompensationTaskLogPo;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @description: 补偿任务服务
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-09-07 10:30
 */
@Service
public interface CompensationTaskService {

    /**
     * 执行补偿任务
     * @param taskType 任务类型
     */
    void executeCompensationTask(String taskType);


    /**
     * 执行补偿任务
     * @param taskType 任务类型
     */
    void executeCompensationTask(String taskType, ThreadPoolExecutor compensationTaskWorkThreadPool);
}
