package com.jst.aftersale.task.compensation.model.po;

import com.jst.base.model.Model;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * 售后补偿任务记录表
 */
@Data
public class CompensationTaskLogPo implements Model {

    // ID
    private String id;

    // 公司ID
    private String coId;

    // 用户ID
    private String uid;

    // 批次号
    private String batchNo;

    // 重试次数
    private Integer retryCount;

    // 上次重试时间
    private LocalDateTime lastRetryTime;

    // 下次重试时间
    private LocalDateTime nextExecutionTime;

    // 任务类型
    private String taskType;

    // 状态
    private String status;

    // 参数
    private String param;

    // 版本号
    private Long version;

    // 逻辑删除标志
    private Boolean deleted;

    // 创建时间
    private LocalDateTime created;

    // 创建人
    private String createBy;

    // 更新时间
    private LocalDateTime updated;

    // 更新人
    private String updateBy;
}
