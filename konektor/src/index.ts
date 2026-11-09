import { DurableObject } from "cloudflare:workers";

interface Env {
  TELEFON: DurableObjectNamespace<Telefon>;
  SECRET: string;
}

type Json = Record<string, any>;

// Narrow on purpose: an `any` in an RPC signature sends the stub types into endless recursion.
interface ToolResult {
  content: unknown[];
  isError?: boolean;
}

// Longer than the phone's worst case (a scan is three printer requests), so its own error comes back
// instead of a relay timeout. Claude's own limit per tool call is unverified (~40 s reported).
const PHONE_TIMEOUT_MS = 55_000;

const INSTRUCTIONS =
  "Domácí asistent: telefon s aplikací Claude a tiskárna na domácí Wi-Fi. " +
  "Nástroje mění hlasitost telefonu, tisknou a skenují. Mluv česky a stručně.";

/** One phone, one WebSocket; the Worker only relays tool calls to it. */
export class Telefon extends DurableObject<Env> {
  private pending = new Map<string, (reply: ToolResult) => void>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Answered by the runtime, so the phone's keep-alive never wakes a hibernated object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected WebSocket", { status: 426 });
    for (const old of this.ctx.getWebSockets()) old.close(1000, "replaced");
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  // Served from storage: the connector settings list tools the moment he adds it, phone online or not.
  async listTools(): Promise<unknown[]> {
    return (await this.ctx.storage.get<unknown[]>("tools")) ?? [];
  }

  async callTool(params: unknown): Promise<ToolResult> {
    const sockets = this.ctx.getWebSockets();
    const phone = sockets[sockets.length - 1];
    if (!phone) return toolError("Telefon teď není připojený k internetu, nástroj nejde použít.");
    const id = crypto.randomUUID();
    return new Promise<ToolResult>((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve(toolError(`Telefon neodpověděl do ${PHONE_TIMEOUT_MS / 1000} s.`));
      }, PHONE_TIMEOUT_MS);
      this.pending.set(id, (reply) => {
        clearTimeout(timer);
        resolve(reply);
      });
      phone.send(JSON.stringify({ jsonrpc: "2.0", id, method: "tools/call", params }));
    });
  }

  async webSocketMessage(_ws: WebSocket, data: string | ArrayBuffer): Promise<void> {
    if (typeof data !== "string") return;
    const message = JSON.parse(data) as Json;
    if (message.method === "tools/announce") {
      await this.ctx.storage.put("tools", message.params?.tools ?? []);
      return;
    }
    const resolve = this.pending.get(String(message.id));
    if (!resolve) return;
    this.pending.delete(String(message.id));
    resolve(message.error ? toolError(String(message.error.message)) : (message.result as ToolResult));
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    // 1005 and 1006 are reserved and cannot be sent back.
    try {
      ws.close(code, reason);
    } catch {}
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const [kind, ...rest] = new URL(request.url).pathname.split("/").filter(Boolean);
    // claude.ai keeps request headers hidden but shows the URL; the phone's URL is never shown anywhere.
    const key =
      kind === "mcp" && rest.length === 0 ? request.headers.get("x-api-key")
      : kind === "phone" && rest.length === 1 ? rest[0]
      : null;
    // A wrong key must look like nothing is here: a 401 would send Claude into OAuth discovery.
    if (!key || !env.SECRET || !sameSecret(key, env.SECRET)) return notFound();
    const telefon = env.TELEFON.get(env.TELEFON.idFromName("telefon"));
    if (kind === "phone") return telefon.fetch(request);
    if (kind === "mcp") return mcp(request, telefon);
    return notFound();
  },
} satisfies ExportedHandler<Env>;

// Streamable HTTP with plain JSON replies; no SSE stream, no sessions.
async function mcp(request: Request, telefon: DurableObjectStub<Telefon>): Promise<Response> {
  if (request.method !== "POST") return new Response(null, { status: 405, headers: { Allow: "POST" } });
  let body: any;
  try {
    body = await request.json();
  } catch {
    return reply({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400);
  }
  const messages: Json[] = Array.isArray(body) ? body : [body];
  const replies: Json[] = [];
  for (const message of messages) {
    const answer = await handle(message, telefon);
    if (answer) replies.push(answer);
  }
  if (!replies.length) return new Response(null, { status: 202 });
  return reply(Array.isArray(body) ? replies : replies[0]);
}

async function handle(message: Json, telefon: DurableObjectStub<Telefon>): Promise<Json | null> {
  // Notifications and responses need no answer.
  if (typeof message?.method !== "string" || message.id === undefined) return null;
  const result = (value: unknown) => ({ jsonrpc: "2.0", id: message.id, result: value });
  switch (message.method) {
    case "initialize":
      return result({
        protocolVersion: message.params?.protocolVersion ?? "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: { name: "hey-claude-relay", version: "1.0.0" },
        instructions: INSTRUCTIONS,
      });
    case "ping":
      return result({});
    case "tools/list":
      return result({ tools: await telefon.listTools() });
    case "tools/call":
      return result(await telefon.callTool(message.params));
    default:
      return { jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "Method not found" } };
  }
}

function toolError(text: string): ToolResult {
  return { content: [{ type: "text", text }], isError: true };
}

function reply(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}

function notFound(): Response {
  return new Response("Not found", { status: 404 });
}

function sameSecret(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
