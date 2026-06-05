package com.jst.aftersale.task.compensation.service;

import com.jst.aftersale.common.utils.ListUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * @description: 补偿任务上下文
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-09-07 16:29
 */
@Service
@Slf4j
public class CompensationTaskContext {

    private Map<String, CompensationTaskProcessor> processorContext;

    @Autowired(required = false)
    public void setProcessor(List<CompensationTaskProcessor> processors) {
        this.processorContext = ListUtils.toMap(processors, CompensationTaskProcessor::getTaskType);
    }


    public CompensationTaskProcessor getProcessor(String taskType) {
        return processorContext.get(taskType);
    }
}
