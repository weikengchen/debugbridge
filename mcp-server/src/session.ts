import WebSocket from "ws";
import { BridgeRequest, BridgeResponse, SessionInfo } from "./types.js";

export class BridgeSession {
    private ws: WebSocket | null = null;
    private requestCounter = 0;
    private pendingRequests = new Map<string, {
        resolve: (resp: BridgeResponse) => void;
        reject: (err: Error) => void;
    }>();
    private defaultPort: number;

    constructor(defaultPort: number = 9876) {
        this.defaultPort = defaultPort;
    }

    async connect(port?: number): Promise<SessionInfo> {
        const targetPort = port ?? this.defaultPort;
        this.defaultPort = targetPort; // remember for future auto-connects
        return new Promise((resolve, reject) => {
            const wsUrl = `ws://127.0.0.1:${targetPort}`;
            this.ws = new WebSocket(wsUrl);

            const timeout = setTimeout(() => {
                this.ws?.close();
                this.ws = null;
                reject(new Error(`Connection timed out connecting to ${wsUrl}`));
            }, 5000);

            this.ws.on("open", async () => {
                clearTimeout(timeout);
                try {
                    const status = await this.send("status", {});
                    resolve(status.result as SessionInfo);
                } catch (e) {
                    reject(e);
                }
            });

            this.ws.on("message", (data: WebSocket.RawData) => {
                try {
                    const resp: BridgeResponse = JSON.parse(data.toString());
                    const pending = this.pendingRequests.get(resp.id);
                    if (pending) {
                        this.pendingRequests.delete(resp.id);
                        pending.resolve(resp);
                    }
                } catch (e) {
                    // Ignore malformed messages
                }
            });

            this.ws.on("error", (err) => {
                clearTimeout(timeout);
                reject(new Error(`WebSocket error: ${err.message}`));
            });

            this.ws.on("close", () => {
                this.ws = null;
                // Reject all pending requests
                for (const [id, pending] of this.pendingRequests) {
                    pending.reject(new Error("Connection closed"));
                }
                this.pendingRequests.clear();
            });
        });
    }

    async send(type: string, payload: Record<string, unknown>): Promise<BridgeResponse> {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            await this.connect();
        }

        const id = `req_${++this.requestCounter}`;
        const req: BridgeRequest = { id, type: type as BridgeRequest["type"], payload };

        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                this.pendingRequests.delete(id);
                reject(new Error("Request timed out (10s). The game may be frozen or the Lua script may be in an infinite loop."));
            }, 10000);

            this.pendingRequests.set(id, {
                resolve: (resp) => { clearTimeout(timeout); resolve(resp); },
                reject: (err) => { clearTimeout(timeout); reject(err); }
            });

            this.ws!.send(JSON.stringify(req));
        });
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        this.pendingRequests.clear();
    }

    get isConnected(): boolean {
        return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
    }
}
