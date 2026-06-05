package com.jst.aftersale.task.compensation.service.impl;

import com.jst.aftersale.common.constant.Constant;
import com.jst.aftersale.task.compensation.configuration.CompensationTaskConfig;
import com.jst.aftersale.task.compensation.dao.CompensationTaskLogDao;
import com.jst.aftersale.task.compensation.model.po.CompensationTaskLogPo;
import com.jst.aftersale.task.compensation.service.CompensationTaskContext;
import com.jst.aftersale.task.compensation.service.CompensationTaskProcessor;
import com.jst.aftersale.task.compensation.service.CompensationTaskService;
import com.jst.base.system.SystemConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.joining;
import static org.apache.tomcat.util.json.JSONParserConstants.ZERO;


/**
 * @description: 补偿任务服务实现类
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-09-07 10:52
 */

@Service
@Slf4j
public class CompensationTaskServiceImpl implements CompensationTaskService {


    private static final String DIS_COMPENSATION_TASK_KEY = "dis::compensation::task:key";

    private final CompensationTaskContext compensationTaskContext;
    private final CompensationTaskLogDao compensationTaskLogDao;
    private final ThreadPoolExecutor compensationTaskWorkThreadPool;
    private final StringRedisTemplate redis;
    private final SystemConfig systemConfig;
    private final CompensationTaskConfig compensationTaskConfig;


    public CompensationTaskServiceImpl(CompensationTaskContext compensationTaskContext,
                                       CompensationTaskLogDao compensationTaskLogDao,
                                       @Qualifier(value = "compensationTaskWorkThreadPool")
                                       ThreadPoolExecutor compensationTaskWorkThreadPool,
                                       StringRedisTemplate stringRedisTemplate,
                                       SystemConfig systemConfig,
                                       CompensationTaskConfig compensationTaskConfig) {
        this.compensationTaskContext = compensationTaskContext;
        this.compensationTaskLogDao = compensationTaskLogDao;
        this.compensationTaskWorkThreadPool = compensationTaskWorkThreadPool;
        this.redis = stringRedisTemplate;
        this.systemConfig = systemConfig;
        this.compensationTaskConfig = compensationTaskConfig;
    }

    @Async("compensationTaskSelectThreadPool")
    @Override
    public void executeCompensationTask(String taskType) {
        executeCompensationTask(taskType, compensationTaskWorkThreadPool);
    }

    @Override
    public void executeCompensationTask(String taskType, ThreadPoolExecutor compensationTaskWorkThreadPool) {
        if (StringUtils.isBlank(taskType)) {
            log.info("补偿任务类型为空，不执行！");
            return;
        }
        // 获取处理器
        CompensationTaskProcessor processor = compensationTaskContext.getProcessor(taskType);
        if (Objects.isNull(processor)) {
            log.error("补偿任务处理器为空，taskType:{}", taskType);
            return;
        }
        log.info("补偿任务开始！类型:{}", taskType);
        // 查询可以执行的推单记录
        String lastId = null;
        // 最大循环次数
        int maxLoopCount = Constant.ONE_THOUSAND;
        int size;
        do {
            // 待执行的状态
            List<String> waitingStatus = processor.getWaitingStatus();
            // 分批获取 100 个一组
            List<CompensationTaskLogPo> tasks = compensationTaskLogDao.queryTrackByStatus(taskType, waitingStatus, lastId);
            if (CollectionUtils.isEmpty(tasks)) {
                return;
            }
            log.info("补偿任务批次号:{}", tasks.stream().map(CompensationTaskLogPo::getBatchNo).collect(joining(Constant.COMMA)));
            size = tasks.size();
            // 获取最后的id 防止深度分页
            lastId = tasks.get(size - Constant.ONE).getId();
            compensationTaskWorkThreadPool.execute(() -> {
                tasks.forEach(task -> executeCompensationTask(processor, task));
            });
        } while (size == Constant.ONE_HUNDRED && maxLoopCount-- > ZERO);
    }

    /**
     * 订单支付回调补偿任务
     * @param processor 补偿任务处理器
     * @param task 补偿任务
     */
    private void executeCompensationTask(CompensationTaskProcessor processor, CompensationTaskLogPo task) {
        Set<String> compensationTaskTestWhiteList = compensationTaskConfig.getCompensationTaskPreTestWhiteList();
        // 在白名单中的coId 不处理补偿任务
        if (systemConfig.isProdEnv() && CollectionUtils.isNotEmpty(compensationTaskTestWhiteList) && compensationTaskTestWhiteList.contains(task.getCoId())) {
            log.info("商家:{} 在预发测试名单中,正式环境不执行补偿！batchNo:{}}", task.getCoId(), task.getBatchNo());
            return;
        }
        // 获取redis锁
        log.info("补偿任务开始，key:{} batchNo:{} coId:{} uid:{}", task.getId(), task.getBatchNo(), task.getCoId(), task.getUid());
        // 如果重试次数超过了限制 需要告警
        if (BooleanUtils.isNotTrue(processor.checkMaxRetryCount(task))) {
            log.info("处理补偿任务结束，重试次数超过限制！id:{}", task.getId());
            // 更新失败状态
            compensationTaskLogDao.updateStatus(task.getId(), processor.failStatus(), task.getStatus());
            // 处理超过重试次数回调
            processor.maxRetryCountCallback(task);
            return;
        }
        String lockKey = buildLockKey(task);
        // 获取同步锁 60秒
        if (BooleanUtils.isNotTrue(redis.opsForValue().setIfAbsent(lockKey, Constant.ONE_STR, Constant.FIVE, TimeUnit.MINUTES))) {
            log.info("处理补偿任务结束，未获取到锁！key:{} coId:{} uid:{}", lockKey, task.getCoId(), task.getUid());
            return;
        }
        try {
            // cas 修改重试次数 和 状态 成功才能执行
            if (compensationTaskLogDao.updateRetryCount(task.getId(), task.getRetryCount()) == Constant.ZERO) {
                log.info("处理订单支付回调补偿任务结束，CAS更新重试次数失败！batchNo:{} coId:{} uid:{}", task.getBatchNo(), task.getCoId(), task.getUid());
                return;
            }
            log.info("处理订单支付回调补偿任务开始！batchNo:{} coId:{} uid:{}", task.getBatchNo(), task.getCoId(), task.getUid());
            // 循环执行补偿任务
            processTasksInLoop(processor, task);
        } catch (Exception e) {
            log.error("补偿任务异常！document:{}", task, e);
            processor.exceptionCallback(task, e);
        } finally {
            redis.delete(lockKey);
        }
    }

    /**
     * 构建同步锁的key
     */
    private String buildLockKey(CompensationTaskLogPo task) {
        StringBuilder lockKey = new StringBuilder(DIS_COMPENSATION_TASK_KEY);
        lockKey.append(task.getTaskType())
                .append(Constant.COLON);
        if (StringUtils.isNotBlank(task.getBatchNo())) {
            lockKey.append(task.getBatchNo())
                    .append(Constant.COLON);
        }
        if (StringUtils.isNotBlank(task.getCoId())) {
            lockKey.append(task.getCoId())
                    .append(Constant.COLON);
        }
        if (StringUtils.isNotBlank(task.getUid())) {
            lockKey.append(task.getUid());
        }
        return lockKey.toString();
    }

    /**
     * 循环处理任务
     */
    private void processTasksInLoop(CompensationTaskProcessor processor, CompensationTaskLogPo task) {
        String currentStatus = task.getStatus();
        // 最大循环次数 20次
        int maxLoop = Constant.TWENTY;
        do {
            if (StringUtils.isBlank(currentStatus)) {
                log.error("补偿任务状态为空，不执行！id:{}", task.getId());
                return;
            }
            // 执行任务处理
            String nextStatus = processor.process(task, currentStatus);
            if (StringUtils.isBlank(nextStatus)) {
                log.info("处理补偿任务结束，下一个状态为空！id:{} currentStatus:{}", task.getId(), currentStatus);
                return;
            }
            // 失败状态
            String failStatus = processor.failStatus();
            // 如果状态是失败状态
            if (nextStatus.equals(failStatus)) {
                log.info("处理补偿任务结束，状态为失败！id:{} currentStatus:{}", task.getId(), currentStatus);
                // 更新失败状态
                compensationTaskLogDao.updateStatus(task.getId(), failStatus, currentStatus);
                processor.failCallback(task, nextStatus);
                return;
            }
            // 如果下一个状态和当前状态一致，则更新下次执行时间
            if (nextStatus.equals(currentStatus)) {
                LocalDateTime nextExecutionTime = processor.nextExecutionTime(task, currentStatus);
                log.info("处理补偿任务结束，状态未改变！更新下次执行时间:{} id:{} currentStatus:{}", nextExecutionTime, task.getId(), currentStatus);
                // 更新下一次执行时间
                compensationTaskLogDao.updateNextExecutionTime(task.getId(), nextExecutionTime);
                return;
            }
            // 如果下一个状态是成功状态 结束任务
            if (processor.isSuccess(nextStatus)) {
                // 更新任务状态
                compensationTaskLogDao.updateStatus(task.getId(), nextStatus, currentStatus);
                log.info("处理补偿任务结束，状态已改变！id:{} currentStatus:{} nextStatus:{}", task.getId(), currentStatus, nextStatus);
                return;
            }
            currentStatus = nextStatus;
        } while (--maxLoop > Constant.ZERO);
        // 循环次数超过限制
        log.error("处理补偿任务结束，循环次数超过限制！id:{} currentStatus:{}", task.getId(), currentStatus);
        // 更新失败状态
        compensationTaskLogDao.updateStatus(task.getId(), processor.failStatus(), task.getStatus());
        processor.spinCountLimitCallback(task);
    }
}
