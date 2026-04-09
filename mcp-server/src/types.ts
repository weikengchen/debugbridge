export interface BridgeRequest {
    id: string;
    type: "execute" | "search" | "snapshot" | "runCommand" | "status";
    payload: Record<string, unknown>;
}

export interface BridgeResponse {
    id: string;
    success: boolean;
    result?: unknown;
    output?: string;
    error?: string;
}

export interface SessionInfo {
    version: string;
    mappingStatus: "mojang" | "passthrough";
    obfuscated: boolean;
    refs: number;
}

export interface SearchResult {
    type: "class" | "method" | "field";
    name: string;
    owner?: string;
    signature?: string;
}
