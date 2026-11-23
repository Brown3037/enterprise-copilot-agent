# 企业级产品与服务 2026 版售后退换货政策

<!--
知识库维护：每条独立条款以 ### 开头，标题首项为稳定条款编号。
可选 rule 注释由建单服务读取，格式为 rule: 条款编号 状态列表 工单类型 决策。
多个状态以逗号分隔，ANY 匹配所有状态。ALLOW 可登记待审核工单，REVIEW 需人工确认，DENY 拦截。
更改可执行条件时，请同步修改条款文字和对应注释。未匹配到适用规则的申请转人工确认。
-->

## 一、云服务器与软件订阅类产品（如企业级云服务器年付套餐）

### CLOUD-PENDING-REFUND 云服务器未发货或未开通退款

<!-- rule: CLOUD-PENDING-REFUND PENDING_SHIP REFUND ALLOW -->

**未发货/未开通状态（PENDING_SHIP）**：用户下单后尚未分配实例资源的，支持 100% 无理由全额退款。Copilot 可登记 REFUND 工单，退款在后续审核通过后原路退回。在当前订单状态模型中，云服务器的 PENDING_SHIP 表示尚未开通、尚未分配资源。

### CLOUD-ACTIVE-REFUND 云服务器已开通或发货退款

<!-- rule: CLOUD-ACTIVE-REFUND SHIPPED,COMPLETED REFUND REVIEW -->

**已开通/已发货状态（SHIPPED 或 COMPLETED）**：开通后 5 天内（含第 5 天）且流量消耗低于 10GB 的，支持全额退款。超过 5 天不支持无理由退款，仅支持折算剩余天数转入账户余额。开通时间与流量信息需人工核验。

## 二、智能硬件设备类产品（如智能硬件开发套件 Pro 版）

### HARDWARE-RETURN 智能硬件 7 天无理由退换货

<!-- rule: HARDWARE-RETURN ANY REFUND REVIEW -->
<!-- rule: HARDWARE-RETURN ANY EXCHANGE REVIEW -->

**7 天无理由退换货**：自物流签收之日起 7 日内，外包装完好、未激活绑定的设备支持无理由退货（REFUND）或换货（EXCHANGE）。签收时间、包装及激活状态需人工核验。

### HARDWARE-WARRANTY 智能硬件质保期故障换新

<!-- rule: HARDWARE-WARRANTY ANY EXCHANGE REVIEW -->

**质保期故障换新**：自签收之日起 1 年内出现非人为硬件故障，经售后审核后免费更换全新设备（需提交 EXCHANGE 工单并注明故障现象）。质保期限和故障性质需人工核验。

## 三、企业专线与定制化网络服务（如高速企业专线宽带月付包）

### DEDICATED-COMPLETED 企业专线宽带完工后的售后处理

<!-- rule: DEDICATED-COMPLETED COMPLETED REFUND DENY -->
<!-- rule: DEDICATED-COMPLETED COMPLETED COMPLAINT ALLOW -->

专线宽带属于现场施工定制类服务，一旦施工完成并进入 **COMPLETED** 状态，不支持线上自助申请退款。可提交 **COMPLAINT** 投诉或人工评估工单，由客户经理在 24 小时内线下核实处理。
