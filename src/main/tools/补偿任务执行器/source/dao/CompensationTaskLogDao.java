package com.jst.aftersale.task.compensation.dao;

import com.jst.aftersale.common.datasource.DataSourceNameConstant;
import com.jst.aftersale.task.compensation.model.po.CompensationTaskLogPo;
import com.jst.base.dao.Dao;
import com.jst.datasource.dynamic.DynamicDataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @description: 补偿任务记录表
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2024-09-05 17:11
 */
@Mapper
@DynamicDataSource(value = DataSourceNameConstant.INNER_DRP)
public interface CompensationTaskLogDao extends Dao<CompensationTaskLogPo>  {

    /**
     * 新增记录
     * @param param
     * @return
     */
    int insert(CompensationTaskLogPo param);

    /**
     * 更新状态
     * @param id ID
     * @param newStatus 新状态
     * @param currentStatus 当前状态
     * @return 影响行数
     */
    int updateStatus(@Param("id") String id, @Param("newStatus") String newStatus, @Param("currentStatus") String currentStatus);

    /**
     * 更新重试次数
     * @param id ID
     * @param retryCount 之前的重试次数
     * @return 影响行数
     */
    int updateRetryCount(@Param("id") String id,@Param("retryCount") Integer retryCount);

    /**
     * 更新下次执行时间
     * @param id ID
     * @param nextExecutionTime 下次执行时间
     */
    void updateNextExecutionTime(@Param("id") String id,@Param("nextExecutionTime") LocalDateTime nextExecutionTime);

    /**
     * 查询需要补偿的任务
     * @param lastId 上次查询到的ID
     *
     * @return 任务记录
     */
    List<CompensationTaskLogPo> queryTrackByStatus(@Param("taskType") String taskType,
                                                   @Param("waitingStatus") List<String> waitingStatus,
                                                   @Param("lastId") String lastId);

    /**
     * 根据批次号和公司ID查询记录
     * @param batchNo 批次号
     * @param coId 公司ID
     * @return 任务记录
     */
    CompensationTaskLogPo queryByBatchNoAndCoId(@Param("batchNo") String batchNo,
                                                @Param("coId") String coId,
                                                @Param("uid") String uid,
                                                @Param("taskType") String taskType);


    /**
     * 根据批次号号码和公司ID查询记录是否存在
     * @param batchNo 批次号
     * @param coId 公司ID
     * @return 任务记录
     */
    String  queryIdByBatchNoAndCoId(@Param("batchNo") String batchNo,
                                   @Param("coId") String coId,
                                   @Param("uid") String uid,
                                   @Param("taskType") String taskType);

    /**
     * 根据批次号号码和公司ID查询记录是否存在
     * @param coId 公司ID
     * @return 任务记录
     */
    String  queryIdByCoIdAndTaskType(@Param("coId") String coId,
                                    @Param("uid") String uid,
                                    @Param("taskType") String taskType,
                                     @Param("taskStatus") String taskStatus);

    /**
     * 根据ID删除记录
     * @param taskId ID
     */
    void deleteById(@Param("taskId") String taskId);
}
