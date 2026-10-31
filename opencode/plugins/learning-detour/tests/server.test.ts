import test from "node:test";
import assert from "node:assert/strict";
import plugin from "../src/index.js";
import type { Plugin } from "@opencode/plugin";

test("server adapter uses actual V2 shapes, RPC bridge, root-session creation and isolated context", async () => {
  const store = new Map<string, any>(),
    sessions = new Map<string, any>([
      [
        "ses_source",
        {
          id: "ses_source",
          agent: "build",
          location: { directory: "/project" },
        },
      ],
    ]);
  const hooks = new Map<string, Function>(),
    tools = new Map<string, any>(),
    emits: any[] = [],
    created: any[] = [];
  let handlers: any;
  const registration = { dispose: async () => {} };
  const ctx = {
    app: { version: "2.2.5" },
    options: {},
    storage: {
      get: async (k: string) => store.get(k),
      set: async (k: string, v: any) => {
        store.set(k, v);
      },
      remove: async (k: string) => {
        store.delete(k);
      },
      scan: async () => ({
        entries: [...store]
          .filter(([k]) => k.startsWith("detour:"))
          .map(([key, value]) => ({ key, value })),
      }),
    },
    agent: { get: async () => ({ data: { mode: "primary" } }) },
    session: {
      get: async ({ sessionID }: any) => {
        const v = sessions.get(sessionID);
        if (!v) throw { _tag: "SessionNotFoundError" };
        return v;
      },
      create: async (input: any) => {
        created.push(input);
        const s = { ...input, location: input.location };
        sessions.set(s.id, s);
        return s;
      },
      hook: async (n: string, fn: Function) => {
        hooks.set(n, fn);
        return registration;
      },
    },
    rpc: {
      register: async (_: unknown, h: any) => {
        handlers = h;
        return {
          ...registration,
          events: {
            emit: async (...e: any[]) => {
              emits.push(e);
            },
          },
        };
      },
    },
    tool: {
      transform: async (fn: Function) => {
        fn({ add: (t: any) => tools.set(t.name, t) });
        return registration;
      },
      hook: async (n: string, fn: Function) => {
        hooks.set(n, fn);
        return registration;
      },
    },
  };
  const cleanup = await plugin.setup(ctx as unknown as Plugin.Context);
  const call = { sessionID: "ses_source", agent: "build" };
  await assert.rejects(
    tools.get("learning_detour_start").execute(
      {
        workSummary: "Task",
        nextAction: "Review",
        learningQuestion: "Atomicity",
      },
      call,
    ),
    /GUI/,
  );
  await handlers.bind({ sessionId: "ses_source", serverId: "sidecar" });
  const lease = store.get("binding:ses_source");
  store.set("binding:ses_source", { serverId: "sidecar", expiresAt: 0 });
  await assert.rejects(
    tools
      .get("learning_detour_start")
      .execute(
        {
          workSummary: "Task",
          nextAction: "Review",
          learningQuestion: "Atomicity",
        },
        call,
      ),
    /GUI/,
  );
  store.set("binding:ses_source", lease);
  const result = await tools.get("learning_detour_start").execute(
    {
      workSummary: "Task",
      nextAction: "Review",
      learningQuestion: "Atomicity",
    },
    call,
  );
  const r = JSON.parse(result.content);
  assert.equal(r.state, "ACTIVE");
  assert.equal(created[0].parentID, undefined);
  assert.equal(created[0].agent, "coach");
  assert.deepEqual(Object.keys(created[0]).sort(), [
    "agent",
    "id",
    "location",
    "metadata",
    "permissions",
    "title",
  ]);
  const working = {
    sessionID: "ses_source",
    system: [],
    tools: { write: {}, read: {} },
  };
  await hooks.get("context")!(working);
  assert.deepEqual(working.tools, {});
  await assert.rejects(
    hooks.get("execute.before")!({ sessionID: "ses_source", tool: "write" }),
    /paused/,
  );
  const coach = { sessionID: r.coachSessionId, system: [], tools: {} };
  await hooks.get("context")!(coach);
  assert.match(JSON.stringify(coach.system), /Focused Learning/);
  assert.match(JSON.stringify(coach.system), /Atomicity/);
  await tools.get("learning_detour_return").execute(
    {
      summary: "Explored",
      resolvedQuestion: "Atomicity",
      remainingUncertainties: [],
      understandingStatus: "unverified",
    },
    { sessionID: r.coachSessionId },
  );
  assert.equal((await handlers.list())[0].state, "RETURNING");
  assert.ok(emits.length >= 2);
  await hooks.get("prompt")!({
    sessionID: "ses_source",
    prompt: { text: `[Learning Detour ${r.id} context draft]` },
  });
  assert.equal((await handlers.list())[0].state, "COMPLETED");
  assert.ok(!("prompt" in ctx.session));
  assert.ok(!("synthetic" in ctx.session));
  await cleanup?.();
});
