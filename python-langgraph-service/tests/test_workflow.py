from unittest.mock import Mock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import Settings, create_app
from app.state import Order, PolicyDecision, TicketReceipt

ORDER = {
    "orderNo": "ORD-2026001", "productName": "云服务器", "amount": "1299.00", "status": "PENDING_SHIP",
    "eligibleTicketTypes": ["REFUND"],
}
POLICY = "CLOUD-PENDING-REFUND：云服务器待发货时可登记退款申请。"
AUTH = {"Authorization": "Bearer test-workflow-token"}
SUBMIT = "请为订单 ORD-2026001 申请退款，原因是项目取消。"


@pytest.fixture
def callbacks():
    fetch = Mock()
    fetch.invoke.return_value = [Order.model_validate(ORDER)]
    create = Mock()
    create.invoke.return_value = TicketReceipt(
        status="CREATED", ticketNo="TKT-20261124-1000", message="已登记待审核工单 TKT-20261124-1000"
    )
    return fetch, create


def payload(message=SUBMIT, policy=POLICY):
    return {"session_id": "phase8-test", "user_id": "U1001", "message": message, "rag_policy": policy}


def execute(callbacks, body=None, mode="mock", model=None, headers=AUTH):
    settings = Settings(token="test-workflow-token", mode=mode, api_key="fake-test-key" if model else "")
    with patch("app.main.build_java_tools", return_value=callbacks), patch("app.main.get_llm", return_value=model):
        with TestClient(create_app(settings)) as client:
            return client.post("/workflow/execute", headers=headers, json=body or payload())


def test_auto_ticket_uses_java_receipt_in_mock_mode(callbacks):
    response = execute(callbacks)
    assert response.status_code == 200
    result = response.json()
    assert result["route_taken"] == "AUTO_TICKET"
    assert result["node_trace"] == ["fetch_orders", "analyze_policy", "auto_ticket_via_java", "compose_reply"]
    assert "TKT-20261124-1000" in result["final_reply"]
    callbacks[0].invoke.assert_called_once_with({"user_id": "U1001"})
    callbacks[1].invoke.assert_called_once_with({
        "user_id": "U1001", "order_no": "ORD-2026001", "ticket_type": "REFUND", "reason": "项目取消",
    })


def test_dedicated_service_escalates_without_writing(callbacks):
    callbacks[0].invoke.return_value = [Order.model_validate({
        **ORDER, "productName": "企业专线宽带", "status": "COMPLETED", "eligibleTicketTypes": [],
        "manualReviewReason": "企业专线订单需转人工核对。",
    })]

    response = execute(callbacks)
    assert response.status_code == 200
    result = response.json()
    assert result["route_taken"] == "HUMAN_ESCALATION"
    assert result["node_trace"] == ["fetch_orders", "analyze_policy", "human_escalation", "compose_reply"]
    assert result["human_review"] == {
        "user_id": "U1001", "order_no": "ORD-2026001", "reason": "企业专线订单需转人工核对。", "status": "DRAFT",
    }
    callbacks[1].invoke.assert_not_called()


@pytest.mark.parametrize("message", [
    "订单 ORD-2026001 可以申请退款吗？",
    "请为订单 ORD-2026001 申请退款，原因是项目取消；不要提交。",
    "请为订单 ORD-2026001 申请退款，原因是我不要提交工单。",
    "请为订单 ORD-2026001 申请退款，原因是 ORD-2026002 关联订单取消。",
    "请为订单 ORD-2026001 申请退款，原因是。",
])
def test_model_cannot_authorize_writes_for_consultations(callbacks, message):
    model = Mock()
    model.invoke.return_value = PolicyDecision(decision="AUTO_TICKET", explanation="建议自动建单。")
    response = execute(callbacks, payload(message), mode="live", model=model)
    assert response.status_code == 200
    assert response.json()["route_taken"] == "INFO_ONLY"
    callbacks[1].invoke.assert_not_called()


def test_java_rejection_after_read_is_escalated(callbacks):
    callbacks[1].invoke.return_value = TicketReceipt(status="REJECTED", message="订单状态已变更")
    response = execute(callbacks)
    assert response.status_code == 200
    result = response.json()
    assert result["route_taken"] == "HUMAN_ESCALATION"
    assert result["node_trace"] == [
        "fetch_orders", "analyze_policy", "auto_ticket_via_java", "human_escalation", "compose_reply",
    ]
    assert result["human_review"]["reason"] == "订单状态已变更"
    callbacks[1].invoke.assert_called_once()


def test_missing_policy_escalates_without_writing(callbacks):
    response = execute(callbacks, payload(policy=""))
    assert response.status_code == 200
    assert response.json()["route_taken"] == "HUMAN_ESCALATION"
    callbacks[1].invoke.assert_not_called()


def test_service_credentials_are_required_before_callbacks(callbacks):
    response = execute(callbacks, headers={})
    assert response.status_code == 401
    callbacks[0].invoke.assert_not_called()


def test_callback_preserves_java_user_identity_without_trimming(callbacks):
    callbacks[0].invoke.return_value = []
    response = execute(callbacks, {**payload(), "user_id": " U1001 "})
    assert response.status_code == 200
    callbacks[0].invoke.assert_called_once_with({"user_id": " U1001 "})
    callbacks[1].invoke.assert_not_called()
