package com.enterprise.copilot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_audit_log")
public class ChatAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String userId;
    private String userMessage;
    private String assistantReply;
    private String toolsInvoked;
    private Long latencyMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
