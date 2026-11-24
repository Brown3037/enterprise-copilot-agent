import hmac
import os
from dataclasses import dataclass, field
from functools import lru_cache

from fastapi import FastAPI, Header, HTTPException
from langchain_openai import ChatOpenAI

from app.graph import PolicyModelError, build_workflow_graph
from app.state import PolicyDecision, WorkflowRequest, WorkflowResponse
from app.tools import JavaCallbackError, build_java_tools


@dataclass(frozen=True)
class Settings:
    java_url: str = "http://127.0.0.1:8080"
    token: str = field(default="", repr=False)
    mode: str = "mock"
    api_key: str = field(default="", repr=False)
    model: str = "deepseek-flash"

    @classmethod
    def from_environment(cls):
        return cls(java_url=os.getenv("JAVA_COPILOT_URL", cls.java_url),
                   token=os.getenv("COPILOT_LANGGRAPH_TOKEN", ""),
                   mode=os.getenv("LANGGRAPH_MODE", cls.mode).lower().strip(),
                   api_key=os.getenv("DEEPSEEK_API_KEY", "").strip(),
                   model=os.getenv("DEEPSEEK_MODEL", cls.model))


def get_llm(settings: Settings):
    return ChatOpenAI(
        model=settings.model, api_key=settings.api_key, base_url="https://api.deepseek.com",
        temperature=0.1, timeout=18, max_retries=0, max_tokens=600,
        extra_body={"thinking": {"type": "disabled"}},
    ).with_structured_output(PolicyDecision, method="json_mode")


def create_app(settings: Settings) -> FastAPI:
    app = FastAPI(title="Enterprise Copilot LangGraph Workflow Engine")

    @lru_cache(maxsize=1)
    def graph():
        if settings.mode == "mock":
            model = None
        elif settings.mode == "live" and settings.api_key not in {"", "sk-placeholder"}:
            model = get_llm(settings)
        else:
            raise HTTPException(status_code=503, detail="LangGraph model configuration is unavailable")
        return build_workflow_graph(*build_java_tools(settings.java_url, settings.token), policy_model=model)

    @app.get("/health")
    def health():
        return {"status": "UP", "engine": "LangChain + LangGraph", "mode": settings.mode}

    @app.post("/workflow/execute", response_model=WorkflowResponse)
    def execute_workflow(request: WorkflowRequest, authorization: str | None = Header(default=None)):
        if not settings.token:
            raise HTTPException(status_code=503, detail="Workflow service credentials are unavailable")
        expected = f"Bearer {settings.token}".encode("utf-8")
        if not authorization or not hmac.compare_digest(expected, authorization.encode("utf-8")):
            raise HTTPException(status_code=401, detail="Invalid workflow service credentials")
        try:
            result = graph().invoke(request.model_dump(), {"recursion_limit": 8})
        except (JavaCallbackError, PolicyModelError) as error:
            raise HTTPException(status_code=502, detail=str(error)) from error
        return WorkflowResponse.model_validate(result)

    return app


app = create_app(Settings.from_environment())
