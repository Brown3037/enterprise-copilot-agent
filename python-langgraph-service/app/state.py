from decimal import Decimal
from typing import Literal, TypedDict

from pydantic import BaseModel, ConfigDict, Field, field_validator

Route = Literal["AUTO_TICKET", "HUMAN_ESCALATION", "INFO_ONLY"]
TicketType = Literal["REFUND", "EXCHANGE", "COMPLAINT"]


class WorkflowRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    session_id: str = Field(min_length=1, max_length=64)
    user_id: str = Field(min_length=1, max_length=32)
    message: str = Field(min_length=1, max_length=2000)
    rag_policy: str = ""

    @field_validator("session_id", "user_id", "message")
    @classmethod
    def require_nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be blank")
        return value


class Order(BaseModel):
    order_no: str = Field(alias="orderNo")
    product_name: str = Field(alias="productName")
    amount: Decimal
    status: str
    eligible_ticket_types: list[TicketType] = Field(alias="eligibleTicketTypes")
    manual_review_reason: str | None = Field(default=None, alias="manualReviewReason")


class TicketReceipt(BaseModel):
    status: Literal["CREATED", "EXISTING", "REJECTED"]
    ticket_no: str | None = Field(default=None, alias="ticketNo")
    message: str = Field(min_length=1)


class TicketRequest(BaseModel):
    order_no: str
    ticket_type: TicketType
    reason: str = Field(min_length=1, max_length=255)


class PolicyDecision(BaseModel):
    decision: Route
    explanation: str = Field(min_length=1, max_length=2000)


class HumanReview(BaseModel):
    user_id: str
    order_no: str | None
    reason: str
    status: Literal["DRAFT"] = "DRAFT"


class WorkflowResponse(BaseModel):
    route_taken: Route
    node_trace: list[str]
    final_reply: str
    human_review: HumanReview | None = None


class WorkflowState(TypedDict, total=False):
    session_id: str
    user_id: str
    message: str
    rag_policy: str
    orders: list[Order]
    target_order_no: str | None
    ticket_request: TicketRequest | None
    decision: Route
    route_taken: Route
    action_result: str
    node_trace: list[str]
    final_reply: str
    human_review: HumanReview | None
