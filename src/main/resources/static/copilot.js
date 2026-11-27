"use strict";

const modes = {
    stream: {
        name: "流式对话", endpoint: "/api/chat/stream", hint: "边生成边显示，适合查单和政策问答。",
        preview: [["接收问题", "建立本轮对话"], ["流式生成", "逐段接收模型回复"], ["完成回复", "保留在会话记录中"]]
    },
    agents: {
        name: "多智能体", endpoint: "/api/chat/multi-agent", hint: "拆解复杂任务，查看计划和审查结果。",
        preview: [["Planner", "识别意图并制定计划"], ["Executor", "执行查询或业务工具"], ["Critic", "复核政策与工具回执"]]
    },
    graph: {
        name: "LangGraph", endpoint: "/api/chat/langgraph", hint: "运行可追踪的规则工作流。",
        preview: [["读取订单", "回调 Java 查询本人订单"], ["核对政策", "判断适用的处理路径"], ["返回结果", "咨询答复、工单或人工交接"]]
    }
};

const stepNames = {
    fetch_orders: ["读取订单", "Java 返回订单数据"],
    analyze_policy: ["核对政策", "根据订单与售后规则分流"],
    auto_ticket_via_java: ["登记工单", "交由 Java 事务接口执行"],
    human_escalation: ["人工核对", "生成交接草稿"],
    compose_reply: ["汇总答复", "整理工作流结果"],
    listUserOrders: ["列出本人订单", ""],
    queryOrderByNo: ["查询订单详情", ""],
    queryTicketByNo: ["查询工单", ""],
    checkPolicy: ["核对售后政策", ""],
    createAfterSalesTicket: ["登记售后工单", ""],
    composeReply: ["整理回复", ""]
};
const routeNames = { INFO_ONLY: "只读咨询", AUTO_TICKET: "自动建单", HUMAN_ESCALATION: "人工核对" };
const orderStatuses = { PENDING_SHIP: "待发货", SHIPPED: "已发货", COMPLETED: "已完成", REFUNDED: "已退款" };
const storageKey = "enterprise-copilot.workspace.v1";
const personas = ["U1001", "U1002"];
const $ = id => document.getElementById(id);
const ui = Object.fromEntries([
    "sidebar", "details", "main-panel", "new-chat", "session-list", "persona", "profile-avatar",
    "open-sessions", "open-details", "welcome", "starters", "thread", "messages", "conversation-scroll",
    "composer", "prompt", "send", "stop", "mode-hint", "trace-title", "trace-status", "trace-caption", "steps",
    "trace-result", "refresh", "services", "backdrop", "announcement"
].map(id => [id, $(id)]));

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function icon(name) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    const use = document.createElementNS(ns, "use");
    svg.setAttribute("class", "icon");
    svg.setAttribute("aria-hidden", "true");
    use.setAttribute("href", "#i-" + name);
    svg.append(use);
    return svg;
}

function readWorkspace() {
    try {
        const stored = JSON.parse(localStorage.getItem(storageKey));
        if (!Array.isArray(stored?.sessions)) return { sessions: [] };
        stored.sessions = stored.sessions.filter(chat =>
            typeof chat?.id === "string" && typeof chat.title === "string"
            && personas.includes(chat.userId) && Object.hasOwn(modes, chat.mode) && Array.isArray(chat.messages));
        return stored;
    } catch {
        return { sessions: [] };
    }
}

const saved = readWorkspace();
const sessions = saved.sessions;
for (const chat of sessions) {
    for (const message of chat.messages) {
        if (message.status === "pending") {
            message.status = "stopped";
            message.note = "上次接收未完成。";
        }
    }
}

let active = sessions.find(chat => chat.id === saved.activeId) || sessions[0];
let pending = null;
let selectedTrace = null;
let drawer = null;
let drawerTrigger = null;

function createSession(userId, mode = "stream") {
    const chat = { id: crypto.randomUUID(), title: "新会话", userId, mode, draft: "", messages: [] };
    sessions.unshift(chat);
    return chat;
}

function persist() {
    try {
        localStorage.setItem(storageKey, JSON.stringify({
            activeId: active.id,
            sessions: sessions.slice(0, 40).map(chat => ({ ...chat, messages: chat.messages.slice(-60) }))
        }));
    } catch {
        // The current session remains usable when browser storage is unavailable.
    }
}

function renderSessions() {
    const buttons = sessions.filter(chat => chat.userId === active.userId).map(chat => {
        const button = element("button", "session-button");
        button.type = "button";
        button.title = chat.title;
        if (chat === active) button.setAttribute("aria-current", "true");
        button.append(icon("chat"), element("span", "", chat.title));
        button.addEventListener("click", () => selectSession(chat));
        return button;
    });
    ui["session-list"].replaceChildren(...buttons);
}

function selectSession(chat) {
    active = chat;
    selectedTrace = chat.messages.findLast(message => message.role === "assistant");
    ui.persona.value = chat.userId;
    ui["profile-avatar"].textContent = chat.userId.slice(-2);
    ui.prompt.value = chat.draft || "";
    closeDrawer(false);
    renderSessions();
    renderStarters();
    renderMessages(true);
    renderComposer();
    renderTrace();
    persist();
}

function renderStarters() {
    const order = active.userId === "U1001" ? "ORD-2026001" : "ORD-2026003";
    const starters = [
        ["orders", "", "查询订单", "看看 " + order + " 的最新状态", "stream", "请帮我查询订单 " + order + " 的当前状态。"],
        ["policy", "tone-gold", "核对退款条件", "按订单状态查看退款规则", "graph", "订单 " + order + " 可以申请退款吗？我只想核对政策。"],
        ["list", "tone-blue", "逐笔梳理售后", "列出本人订单并核对政策", "agents", "请先列出我名下的订单，再逐笔说明各订单适用的售后条款。只查询，不提交申请。"],
        ["flow", "tone-violet", "查看订单工作流", "获取本人订单并查看节点轨迹", "graph", "请列出我名下的订单、当前状态和可办理的售后类型。"]
    ];
    ui.starters.replaceChildren(...starters.map(([glyph, tone, title, subtitle, mode, prompt]) => {
        const button = element("button", "starter");
        button.type = "button";
        const badge = element("span", "starter-icon " + tone);
        badge.append(icon(glyph));
        const text = element("span", "starter-text");
        text.append(element("strong", "", title), element("small", "", subtitle));
        button.append(badge, text, icon("arrow"));
        button.addEventListener("click", () => {
            active.draft = prompt;
            ui.prompt.value = prompt;
            switchMode(mode);
            ui.prompt.focus();
        });
        return button;
    }));
}

function switchMode(mode) {
    active.mode = mode;
    selectedTrace = null;
    renderComposer();
    renderTrace();
    persist();
}

function renderComposer() {
    const ownsRun = pending?.chat === active;
    for (const button of ui.composer.querySelectorAll("[data-mode]")) {
        button.setAttribute("aria-pressed", String(button.dataset.mode === active.mode));
    }
    ui.send.hidden = ownsRun;
    ui.stop.hidden = !ownsRun;
    ui.send.disabled = Boolean(pending) || !ui.prompt.value.trim();
    ui["mode-hint"].textContent = pending && !ownsRun ? "另一个会话正在接收回复。" : modes[active.mode].hint;
}

function inlineText(node, value) {
    const pattern = /\*\*([^*]+)\*\*|\x60([^\x60]+)\x60/g;
    let offset = 0;
    for (const match of value.matchAll(pattern)) {
        node.append(value.slice(offset, match.index));
        node.append(match[1] ? element("strong", "", match[1]) : element("code", "", match[2]));
        offset = match.index + match[0].length;
    }
    node.append(value.slice(offset));
}

function workflowOrder(line) {
    const [number, product, status, policy, extra] = line.trim().split("｜");
    if (!/^ORD-[A-Z0-9-]+$/.test(number) || !product || !Object.hasOwn(orderStatuses, status) || !policy || extra !== undefined) {
        return null;
    }
    const card = element("div", "order-result");
    const header = element("div", "order-result-header");
    header.append(element("span", "order-result-id", number), element("span", "order-result-status", orderStatuses[status]));
    card.append(header, element("div", "order-result-product", product), element("p", "", policy));
    return card;
}

function replyMarkup(value, mode) {
    const result = document.createDocumentFragment();
    const lines = value.split(/\r?\n/);
    for (let i = 0; i < lines.length;) {
        const line = lines[i++];
        if (!line.trim()) continue;
        const order = mode === "graph" ? workflowOrder(line) : null;
        if (order) {
            result.append(order);
        } else if (/^\x60{3}/.test(line)) {
            const code = [];
            while (i < lines.length && !/^\x60{3}/.test(lines[i])) code.push(lines[i++]);
            if (i < lines.length) i++;
            const pre = element("pre");
            pre.append(element("code", "", code.join("\n")));
            result.append(pre);
        } else if (/^#{1,6}\s/.test(line)) {
            const heading = element("h3");
            inlineText(heading, line.replace(/^#{1,6}\s+/, ""));
            result.append(heading);
        } else if (/^\s*([-*+]|\d+\.)\s/.test(line)) {
            const ordered = /^\s*\d+\./.test(line);
            const marker = ordered ? /^\s*\d+\.\s+/ : /^\s*[-*+]\s+/;
            const list = element(ordered ? "ol" : "ul");
            let item = line;
            do {
                const li = element("li");
                inlineText(li, item.replace(marker, ""));
                list.append(li);
                item = lines[i];
                if (!marker.test(item || "")) break;
                i++;
            } while (true);
            result.append(list);
        } else if (/^\s*\|/.test(line) && /^\s*\|?[\s:|-]*-+[\s:|-]*$/.test(lines[i] || "")) {
            const table = element("table");
            const head = element("thead");
            const body = element("tbody");
            function row(value, tag) {
                const tr = element("tr");
                for (const part of value.trim().replace(/^\||\|$/g, "").split("|")) {
                    const cell = element(tag);
                    if (tag === "th") cell.scope = "col";
                    inlineText(cell, part.trim());
                    tr.append(cell);
                }
                return tr;
            }
            head.append(row(line, "th"));
            i++;
            while (i < lines.length && /^\s*\|/.test(lines[i])) body.append(row(lines[i++], "td"));
            table.append(head, body);
            const scroll = element("div", "table-scroll");
            scroll.append(table);
            result.append(scroll);
        } else {
            const paragraph = element("p");
            inlineText(paragraph, line);
            result.append(paragraph);
        }
    }
    return result;
}

function nearBottom() {
    const scroll = ui["conversation-scroll"];
    return scroll.scrollHeight - scroll.clientHeight - scroll.scrollTop < 95;
}

function scrollBottom() {
    const scroll = ui["conversation-scroll"];
    scroll.scrollTop = scroll.scrollHeight;
}

function renderMessages(forceScroll = false) {
    const follow = forceScroll || nearBottom();
    ui.welcome.hidden = active.messages.length > 0;
    ui.thread.hidden = !ui.welcome.hidden;
    ui.messages.replaceChildren(...active.messages.map(messageView));
    if (ui.thread.hidden) ui["conversation-scroll"].scrollTop = 0;
    else if (follow) scrollBottom();
}

function messageView(message) {
    const item = element("article", "message " + message.role);
    item.id = "message-" + message.id;
    if (message.role === "user") {
        item.append(element("div", "message-content", message.content));
        return item;
    }

    const mark = element("span", "message-mark");
    mark.setAttribute("aria-hidden", "true");
    mark.append(...Array.from({ length: 4 }, () => element("span")));
    const body = element("div", "message-body");
    const heading = element("div", "message-heading", "Copilot");
    heading.append(element("span", "message-mode", modes[message.mode].name));
    const content = element("div", "message-content");
    content.append(replyMarkup(message.content, message.mode));
    body.append(heading, content);
    if (message.status === "pending" && !message.content) {
        const note = element("div", "message-note");
        const dots = element("span", "typing");
        dots.setAttribute("aria-hidden", "true");
        dots.append(element("i"), element("i"), element("i"));
        note.append(dots, document.createTextNode(message.mode === "stream" ? "正在接收回复" : "正在执行工作流"));
        body.append(note);
    } else if (message.note) {
        body.append(element("p", "message-note " + (message.status === "error" ? "error" : ""), message.note));
    }
    if (message.status !== "pending") {
        const actions = element("div", "message-tools");
        const action = (label, glyph, perform) => {
            const button = element("button", "text-button");
            button.type = "button";
            button.append(icon(glyph), document.createTextNode(label));
            button.addEventListener("click", () => perform(button));
            actions.append(button);
        };
        if (message.content) action("复制回复", "copy", async button => {
            try {
                await navigator.clipboard.writeText(message.content);
                button.lastChild.textContent = "已复制";
                setTimeout(() => { button.lastChild.textContent = "复制回复"; }, 1400);
            } catch {
                announce("复制失败，请选择文本后复制。");
            }
        });
        if (message.status === "complete") action("查看轨迹", "flow", button => {
            selectedTrace = message;
            renderTrace();
            if (matchMedia("(max-width: 970px)").matches) openDrawer("details", button);
            else ui["trace-title"].focus();
        });
        if (message.note && message.query) action("恢复问题到输入框", "refresh", () => {
            active.draft = message.query;
            ui.prompt.value = active.draft;
            switchMode(message.mode);
            ui.prompt.focus();
        });
        if (actions.childElementCount) body.append(actions);
    }
    item.append(mark, body);
    return item;
}

function renderTrace() {
    let run = pending?.chat === active && !selectedTrace ? pending.answer : selectedTrace;
    if (run && !active.messages.includes(run)) run = null;
    const mode = modes[run?.mode || active.mode];
    const phase = run?.status || "idle";
    ui["trace-status"].dataset.state = phase;
    ui["trace-status"].textContent = {
        idle: "等待请求", pending: "运行中", complete: "已完成", stopped: "已停止", error: "请求失败"
    }[phase];
    ui["trace-caption"].textContent = mode.name + " · " + (phase === "idle" ? "流程预览" : "本轮执行");
    const info = run?.details;
    let steps = mode.preview;
    if (info?.node_trace?.length) {
        steps = info.node_trace.map(step => stepNames[step] || [step, ""]);
    } else if (info?.plan?.intentType === "SIMPLE_QUERY") {
        steps = [["Planner", "识别为简单请求"], ["普通对话", "由原有对话链路返回回复"]];
    }
    ui.steps.replaceChildren(...steps.map(([title, desc], index) => {
        const step = element("li", "step" + (phase === "complete" ? " is-complete" : ""));
        const marker = element("span", "step-marker");
        marker.append(icon(phase === "complete" ? "check" : ["chat", "flow", "policy"][index % 3]));
        const copy = element("div");
        copy.append(element("strong", "", title));
        if (desc) copy.append(element("span", "step-description", desc));
        step.append(marker, copy);
        return step;
    }));
    ui["trace-result"].replaceChildren();
    if (info?.route_taken) traceCard("处理路径", routeNames[info.route_taken] || info.route_taken);
    if (info?.human_review) {
        const review = info.human_review;
        traceCard("人工交接草稿", [review.order_no, review.reason].filter(Boolean).join(" · "), true);
    }
    if (info?.plan) {
        const card = traceCard("执行计划", info.plan.goalSummary);
        if (info.plan.steps?.length) {
            const list = element("ol");
            list.append(...info.plan.steps.map(step => element("li", "", stepNames[step]?.[0] || step)));
            card.append(list);
        }
    }
    if (info?.review) {
        traceCard(info.review.approved ? "审核通过" : "需要人工核对", info.review.critiqueComment, !info.review.approved);
    }
    if (info?.reflectionTriggered) traceCard("反思修订", "已执行一轮修订。");
    if (info?.toolsInvoked && info.toolsInvoked !== "NONE") traceCard("调用工具", info.toolsInvoked);
    if (Number.isFinite(info?.totalLatencyMs)) traceCard("处理耗时", (info.totalLatencyMs / 1000).toFixed(2) + " 秒");
}

function traceCard(title, text, review = false) {
    const card = element("div", "trace-card" + (review ? " is-review" : ""));
    card.append(element("strong", "", title));
    if (text) card.append(element("p", "", text));
    ui["trace-result"].append(card);
    return card;
}

function renderServices(state, data) {
    const targets = [["Java 网关", state], ["MySQL", data && Number.isFinite(data.mysqlOrderCount) ? "up" : "unknown"],
        ["Redis", data?.redisStatus === "OK" ? "up" : "unknown"]];
    ui.services.replaceChildren(...targets.map(([title, status]) => {
        const row = element("div", "service-row");
        const label = element("span", "service-state", { pending: "检查中", up: "在线", down: "不可用", unknown: "未确认" }[status]);
        label.dataset.state = status;
        row.append(element("span", "", title), label);
        return row;
    }));
}

async function checkServices() {
    ui.refresh.disabled = true;
    renderServices("pending");
    try {
        const response = await fetch("/api/health/check", { signal: AbortSignal.timeout(6000), cache: "no-store" });
        const data = await responseData(response);
        renderServices(data?.service === "UP" ? "up" : "down", data);
    } catch {
        renderServices("down");
    } finally {
        ui.refresh.disabled = false;
    }
}

async function responseData(response) {
    const value = await response.json().catch(() => null);
    if (!response.ok || value?.code !== 200) {
        throw new Error(value?.message || "请求失败（HTTP " + response.status + "）");
    }
    return value.data;
}

async function readStream(response, append) {
    if (!response.ok || !response.headers.get("content-type")?.includes("text/event-stream")) {
        await responseData(response);
        throw new Error("服务未返回流式回复。");
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    function flush(complete) {
        const events = buffer.split(/\r?\n\r?\n/);
        buffer = complete ? "" : events.pop();
        for (const event of events) {
            const lines = event.split(/\r?\n/).filter(line => line.startsWith("data:"));
            if (lines.length) append(lines.map(line => line.slice(5).replace(/^ /, "")).join("\n"));
        }
    }
    try {
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            flush(false);
        }
        buffer += decoder.decode();
        flush(true);
    } finally {
        reader.releaseLock();
    }
}

async function send(event) {
    event.preventDefault();
    const query = ui.prompt.value.trim();
    if (!query || pending) return;

    const chat = active;
    const mode = modes[chat.mode];
    chat.draft = "";
    ui.prompt.value = "";
    if (!chat.messages.length) chat.title = query.replace(/\s+/g, " ").slice(0, 38);
    const index = sessions.indexOf(chat);
    sessions.unshift(...sessions.splice(index, 1));
    const answer = { id: crypto.randomUUID(), role: "assistant", content: "", mode: chat.mode, status: "pending", query };
    chat.messages.push({ id: crypto.randomUUID(), role: "user", content: query }, answer);
    const controller = new AbortController();
    pending = { chat, answer, controller };
    selectedTrace = answer;
    renderSessions();
    renderMessages(true);
    renderComposer();
    renderTrace();
    persist();
    if (matchMedia("(max-width: 650px)").matches) ui.prompt.blur();

    let queued = false;
    function append(chunk) {
        answer.content += chunk;
        if (queued) return;
        queued = true;
        requestAnimationFrame(() => {
            queued = false;
            if (active !== chat || answer.status !== "pending") return;
            const follow = nearBottom();
            const node = $("message-" + answer.id);
            node?.querySelector(".message-content")?.replaceChildren(replyMarkup(answer.content, answer.mode));
            node?.querySelector(".message-note")?.remove();
            if (follow) scrollBottom();
        });
    }

    try {
        const response = await fetch(mode.endpoint, {
            method: "POST",
            headers: { "Content-Type": "application/json", Accept: answer.mode === "stream" ? "text/event-stream" : "application/json" },
            body: JSON.stringify({ userId: chat.userId, sessionId: chat.id, message: query }),
            signal: controller.signal
        });
        if (answer.mode === "stream") {
            await readStream(response, append);
        } else {
            const data = await responseData(response);
            answer.details = data;
            answer.content = data?.finalReply || data?.final_reply || "";
        }
        if (!answer.content.trim()) throw new Error("服务未返回可显示的回复。");
        answer.status = "complete";
        announce("本轮回复已完成。");
    } catch (error) {
        answer.status = controller.signal.aborted ? "stopped" : "error";
        answer.note = controller.signal.aborted ? "已停止接收回复。" : error.message || "暂时无法连接服务。";
        announce(answer.note);
    } finally {
        pending = null;
        if (active === chat) {
            renderMessages();
            renderTrace();
        }
        renderComposer();
        persist();
    }
}

function announce(text) {
    ui.announcement.textContent = text;
}

function openDrawer(name, trigger) {
    drawer = name;
    drawerTrigger = trigger;
    document.querySelector(".workspace").dataset.drawer = name;
    ui.backdrop.hidden = false;
    ui["open-sessions"].setAttribute("aria-expanded", String(name === "sessions"));
    ui["open-details"].setAttribute("aria-expanded", String(name === "details"));
    ui["main-panel"].inert = true;
    ui.sidebar.inert = name === "details";
    ui.details.inert = name === "sessions";
    const panel = name === "sessions" ? ui.sidebar : ui.details;
    panel.querySelector("[data-close]").focus();
}

function closeDrawer(restoreFocus = true) {
    if (!drawer) return;
    drawer = null;
    delete document.querySelector(".workspace").dataset.drawer;
    ui.backdrop.hidden = true;
    ui["main-panel"].inert = false;
    ui.sidebar.inert = false;
    ui.details.inert = false;
    ui["open-sessions"].setAttribute("aria-expanded", "false");
    ui["open-details"].setAttribute("aria-expanded", "false");
    if (restoreFocus) drawerTrigger?.focus();
    drawerTrigger = null;
}

ui.composer.addEventListener("submit", send);
ui.composer.querySelectorAll("[data-mode]").forEach(button =>
    button.addEventListener("click", () => switchMode(button.dataset.mode)));
ui.prompt.addEventListener("input", () => { active.draft = ui.prompt.value; renderComposer(); });
ui.prompt.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        if (!ui.send.disabled) ui.composer.requestSubmit();
    }
});
ui.stop.addEventListener("click", () => pending?.controller.abort());
ui.persona.addEventListener("change", () => {
    const userId = ui.persona.value;
    selectSession(sessions.find(chat => chat.userId === userId) || createSession(userId));
});
ui["new-chat"].addEventListener("click", () => {
    selectSession(active.messages.length || active.draft ? createSession(active.userId, active.mode) : active);
    ui.prompt.focus();
});
ui["open-sessions"].addEventListener("click", event => openDrawer("sessions", event.currentTarget));
ui["open-details"].addEventListener("click", event => openDrawer("details", event.currentTarget));
ui.backdrop.addEventListener("click", () => closeDrawer());
document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => closeDrawer()));
document.addEventListener("keydown", event => { if (event.key === "Escape") closeDrawer(); });
for (const [cutoff, panel] of [[650, "sessions"], [970, "details"]]) {
    matchMedia("(max-width: " + cutoff + "px)").addEventListener("change", event => {
        if (!event.matches && drawer === panel) closeDrawer(false);
    });
}
ui.refresh.addEventListener("click", checkServices);
window.addEventListener("pagehide", persist);

selectSession(active || createSession("U1001"));
checkServices();
