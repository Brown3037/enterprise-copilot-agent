package com.enterprise.copilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRequest {

    @NotBlank(message = "会话 ID 不能为空")
    @Size(max = 64, message = "会话 ID 长度不能超过 64")
    private String sessionId;

    @NotBlank(message = "用户 ID 不能为空")
    @Size(max = 32, message = "用户 ID 长度不能超过 32")
    private String userId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 16000, message = "消息内容长度不能超过 16000")
    private String message;
}
