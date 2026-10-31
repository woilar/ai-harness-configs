import { randomUUID } from "node:crypto";
import {
  CheckpointInput,
  ResultInput,
  RecordSchema,
  type Detour,
  type State,
} from "./contracts.js";
export interface Store {
  get(key: string): Promise<unknown>;
  set(key: string, value: unknown): Promise<void>;
  scan(): Promise<Array<{ key: string; value: unknown }>>;
  remove(key: string): Promise<void>;
}
export interface Session {
  id: string;
  parentID?: string;
  agent?: string;
  location: { directory: string };
  metadata?: Record<string, unknown>;
}
export interface Host {
  get(id: string): Promise<Session>;
  create(
    id: string,
    directory: string,
    agent: string,
    detourId: string,
  ): Promise<Session>;
  hasAgent(id: string): Promise<boolean>;
}
const transitions: Record<State, State[]> = {
  CREATING: ["ACTIVE", "FAILED", "CANCELLED"],
  ACTIVE: ["RETURNING", "CANCELLED"],
  RETURNING: ["COMPLETED", "CANCELLED"],
  COMPLETED: [],
  CANCELLED: [],
  FAILED: ["CREATING", "CANCELLED"],
};
export function transition(d: Detour, state: State): Detour {
  if (!transitions[d.state].includes(state))
    throw new Error(`Invalid transition ${d.state} -> ${state}`);
  return { ...d, state, updatedAt: new Date().toISOString(), error: undefined };
}
// Serializes this project's plugin instance; server KV owns durability, never a GUI Map.
export class Detours {
  private tail: Promise<unknown> = Promise.resolve();
  constructor(
    readonly store: Store,
    readonly host: Host,
    readonly coachAgent: string,
  ) {}
  private lock<T>(f: () => Promise<T>): Promise<T> {
    const next = this.tail.then(f);
    this.tail = next.catch(() => {});
    return next;
  }
  async all() {
    return (await this.store.scan()).map((x) => RecordSchema.parse(x.value));
  }
  async save(d: Detour) {
    await this.store.set("detour:" + d.id, d);
    return d;
  }
  async byId(id: string) {
    const x = await this.store.get("detour:" + id);
    if (!x) throw new Error("Detour not found");
    return RecordSchema.parse(x);
  }
  async start(sourceId: string, serverId: string, raw: unknown) {
    return this.lock(async () => {
      const input = CheckpointInput.parse(raw);
      const all = await this.all();
      if (all.some((d) => d.coachSessionId === sourceId))
        throw new Error("Nested detours are not supported");
      const existing = all.find(
        (d) =>
          d.sourceSessionId === sourceId &&
          ["CREATING", "ACTIVE", "RETURNING", "FAILED"].includes(d.state),
      );
      if (existing) return existing;
      const source = await this.host.get(sourceId);
      if (source.parentID) throw new Error("Source must be a root session");
      if (!(await this.host.hasAgent(this.coachAgent)))
        throw new Error("Configure a primary Coach agent first");
      const id = randomUUID(),
        now = new Date().toISOString();
      const d: Detour = {
        id,
        sourceSessionId: sourceId,
        sourceServerId: serverId,
        sourceRole: source.agent ?? "unknown",
        sourceDirectory: source.location.directory,
        coachSessionId: "ses_ld_" + id.replaceAll("-", ""),
        state: "CREATING",
        checkpoint: input,
        createdAt: now,
        updatedAt: now,
      };
      await this.save(d); // Durable reserved coach ID BEFORE side effects.
      return this.create(d);
    });
  }
  private async create(d: Detour) {
    try {
      let coach: Session | undefined;
      try {
        coach = await this.host.get(d.coachSessionId);
      } catch (e) {
        if (!isMissing(e)) throw e;
      }
      if (!coach)
        coach = await this.host.create(
          d.coachSessionId,
          d.sourceDirectory,
          this.coachAgent,
          d.id,
        );
      if (coach.parentID || coach.metadata?.learningDetour !== d.id)
        throw new Error("Unexpected Coach session identity");
      return this.save(transition(d, "ACTIVE"));
    } catch (e) {
      return this.save({
        ...transition(d, "FAILED"),
        error:
          "Coach creation failed; recover after checking server availability",
      });
    }
  }
  async recover(id: string) {
    return this.lock(async () => {
      let d = await this.byId(id);
      if (d.state === "FAILED") {
        d = transition(d, "CREATING");
        await this.save(d);
      }
      if (d.state === "CREATING") return this.create(d);
      return d;
    });
  }
  async finish(coachId: string, raw: unknown) {
    return this.lock(async () => {
      const result = ResultInput.parse(raw);
      const d = (await this.all()).find((d) => d.coachSessionId === coachId);
      if (!d) throw new Error("Not a detour Coach session");
      if (d.state === "RETURNING" || d.state === "COMPLETED") return d; // First result wins; no duplicate delivery.
      if (d.state !== "ACTIVE") throw new Error("Detour is not active");
      return this.save({ ...transition(d, "RETURNING"), result }); // Persist even when source was deleted/offline.
    });
  }
  async acknowledge(id: string) {
    return this.lock(async () => {
      const d = await this.byId(id);
      if (d.state === "COMPLETED") return true;
      if (d.state !== "RETURNING") return false;
      await this.save(transition(d, "COMPLETED"));
      return true;
    });
  }
  async cancel(id: string) {
    return this.lock(async () => {
      const d = await this.byId(id);
      if (d.state === "CANCELLED") return true;
      if (d.state === "COMPLETED") return false;
      await this.save(transition(d, "CANCELLED"));
      return true;
    });
  }
  async cleanup(days: number, now = Date.now()) {
    return this.lock(async () => {
      for (const d of await this.all())
        if (
          ["COMPLETED", "CANCELLED"].includes(d.state) &&
          now - Date.parse(d.updatedAt) > days * 86400000
        ) {
          // Expire terminal records; sessions themselves follow OpenCode retention.
          await this.store.remove("detour:" + d.id);
        }
    });
  }
}
export function isMissing(e: unknown) {
  const v = e as { status?: number; statusCode?: number; _tag?: string };
  return (
    v?._tag === "SessionNotFoundError" ||
    v?.status === 404 ||
    v?.statusCode === 404
  );
}
