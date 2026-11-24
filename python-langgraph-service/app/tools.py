import httpx
from langchain_core.tools import tool
from pydantic import TypeAdapter, ValidationError

from app.state import Order, TicketReceipt, TicketType


class JavaCallbackError(RuntimeError):
    pass


def build_java_tools(base_url: str, token: str):
    def request(method: str, path: str, **kwargs):
        try:
            with httpx.Client(
                base_url=base_url, headers={"Authorization": f"Bearer {token}"}, timeout=5.0, trust_env=False
            ) as client:
                response = client.request(method, path, **kwargs)
                response.raise_for_status()
                body = response.json()
                if not isinstance(body, dict) or body.get("code") != 200 or body.get("data") is None:
                    raise JavaCallbackError("Java callback returned an invalid result")
                return body["data"]
        except (httpx.HTTPError, ValueError) as error:
            raise JavaCallbackError("Java callback failed or timed out") from error

    @tool
    def fetch_user_orders_from_java(user_id: str) -> list[Order]:
        """Load the user's owned orders and current after-sales eligibility from Java."""
        try:
            return TypeAdapter(list[Order]).validate_python(
                request("GET", "/api/internal/orders", params={"userId": user_id})
            )
        except ValidationError as error:
            raise JavaCallbackError("Invalid order data from Java") from error

    @tool
    def create_ticket_via_java(user_id: str, order_no: str, ticket_type: TicketType, reason: str) -> TicketReceipt:
        """Submit an after-sales ticket through the Java transaction endpoint."""
        try:
            receipt = TicketReceipt.model_validate(request(
                "POST", "/api/internal/tickets",
                json={"userId": user_id, "orderNo": order_no, "ticketType": ticket_type, "reason": reason},
            ))
        except ValidationError as error:
            raise JavaCallbackError("Invalid ticket receipt from Java") from error
        if receipt.status != "REJECTED" and not receipt.ticket_no:
            raise JavaCallbackError("Java did not confirm the ticket number")
        return receipt

    return fetch_user_orders_from_java, create_ticket_via_java
