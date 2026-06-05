package com.jst.aftersale.task.compensation.configuration;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;


import java.util.Set;

/**
 * 描述: 补偿任务配置
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2025-06-09 16:59
 */
@Data
@RefreshScope
@Configuration
public class CompensationTaskConfig {


    /**
     * 预发测试白名单
     */
    @Value("#{'${afterSale.compensationTask.preTest.whiteList:}'.split(',')}")
    private Set<String> compensationTaskPreTestWhiteList;

}
