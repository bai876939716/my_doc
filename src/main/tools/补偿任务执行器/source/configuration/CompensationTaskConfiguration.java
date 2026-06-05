package com.jst.aftersale.task.compensation.configuration;

import org.dromara.dynamictp.common.em.QueueTypeEnum;
import org.dromara.dynamictp.common.em.RejectedTypeEnum;
import org.dromara.dynamictp.core.support.ThreadPoolBuilder;
import com.jst.util.concurrent.ThreadPoolBuilderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 补偿任务配置
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-09-07 15:38
 */
@Configuration
public class CompensationTaskConfiguration {


    /**
     * 补偿任务查询线程池
     * @return
     */
    @Bean(name = "compensationTaskSelectThreadPool")
    public ThreadPoolExecutor compensationTaskSelectThreadPool() {
        return ThreadPoolBuilderFactory.newDtpBuilder()
                // 核心线程池
                .corePoolSize(1)
                // 最大线程池
                .maximumPoolSize(1)
                // 配置队列容量为100(ioIntensive为false时，通过queueCapacity配置无效)
                .workQueue(QueueTypeEnum.VARIABLE_LINKED_BLOCKING_QUEUE.getName(), 5000, false)
                // 设置线程活跃时间
                .keepAliveTime(30)

                // 允许空闲的核心线程销毁
                .allowCoreThreadTimeOut(true)
                // 任务执行超时阈值，目前只做告警用，单位（ms）,默认为0，不告警
                .runTimeout(0)
                // 任务在队列等待超时阈值，目前只做告警用，单位（ms），默认为0，不告警
                .queueTimeout(0)
                // 队列满后使用调用者线程执行
                .rejectedExecutionHandler(RejectedTypeEnum.CALLER_RUNS_POLICY.getName())
                // 线程池名称(唯一标识)
                .threadPoolName("compensationTaskSelectThreadPool")
                // 线程名称前缀(建议与threadPoolName一样)
                .threadFactory("compensationTaskSelectThreadPool")
                // 动态线程池
                .buildDynamic();
    }

    /**
     * 补偿任务工作线程池
     * @return
     */
    @Bean(name = "compensationTaskWorkThreadPool")
    public ThreadPoolExecutor compensationTaskWorkThreadPool() {
        return ThreadPoolBuilderFactory.newDtpBuilder()
                // 核心线程池
                .corePoolSize(20)
                // 最大线程池
                .maximumPoolSize(20)
                // 配置队列容量为100(ioIntensive为false时，通过queueCapacity配置无效)
                .workQueue(QueueTypeEnum.VARIABLE_LINKED_BLOCKING_QUEUE.getName(), 5000, false)
                // 设置线程活跃时间
                .keepAliveTime(30)

                // 允许空闲的核心线程销毁
                .allowCoreThreadTimeOut(true)
                // 任务执行超时阈值，目前只做告警用，单位（ms）,默认为0，不告警
                .runTimeout(0)
                // 任务在队列等待超时阈值，目前只做告警用，单位（ms），默认为0，不告警
                .queueTimeout(0)
                // 队列满后使用调用者线程执行
                .rejectedExecutionHandler(RejectedTypeEnum.CALLER_RUNS_POLICY.getName())
                // 线程池名称(唯一标识)
                .threadPoolName("compensationTaskWorkThreadPool")
                // 线程名称前缀(建议与threadPoolName一样)
                .threadFactory("compensationTaskWorkThreadPool")
                // 动态线程池
                .buildDynamic();
    }
}
