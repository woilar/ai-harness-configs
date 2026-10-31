import test from "node:test";
import assert from "node:assert/strict";
import {
  Detours,
  transition,
  type Store,
  type Session,
  type Host,
} from "../src/core.js";
import {
  CheckpointInput,
  ResultInput,
  renderResult,
  type Detour,
} from "../src/contracts.js";
const question = {
  workSummary: "Improve request admission",
  nextAction: "Review the agreed bounded step",
  learningQuestion: "Why does atomicity matter here?",
  activeFlags: ["/strict"],
};
const answer = {
  summary: "Explored one atomic operation",
  resolvedQuestion: "Operations can interleave",
  remainingUncertainties: ["Failure handling"],
  understandingStatus: "partial" as const,
};
export function fixture() {
  const data = new Map<string, unknown>(),
    sessions = new Map<string, Session>([
      [
        "source",
        { id: "source", agent: "copilot", location: { directory: "/work" } },
      ],
    ]);
  const creates: any[] = [];
  const store: Store = {
    get: async (k) => data.get(k),
    set: async (k, v) => {
      data.set(k, JSON.parse(JSON.stringify(v)));
    },
    remove: async (k) => {
      data.delete(k);
    },
    scan: async () =>
      [...data]
        .filter(([k]) => k.startsWith("detour:"))
        .map(([key, value]) => ({ key, value })),
  };
  const host: Host = {
    get: async (id) => {
      const s = sessions.get(id);
      if (!s) throw { _tag: "SessionNotFoundError" };
      return s;
    },
    hasAgent: async () => true,
    create: async (id, directory, agent, detourId) => {
      creates.push({ id, directory, agent, detourId });
      const s = {
        id,
        agent,
        location: { directory },
        metadata: { learningDetour: detourId },
      };
      sessions.set(id, s);
      return s;
    },
  };
  return {
    data,
    sessions,
    store,
    host,
    creates,
    m: new Detours(store, host, "coach"),
  };
}
test("checkpoint validation rejects oversized data, code and credentials", () => {
  assert.throws(() =>
    CheckpointInput.parse({ ...question, workSummary: "x".repeat(1001) }),
  );
  assert.throws(() =>
    CheckpointInput.parse({
      ...question,
      workSummary: "```java privateCode```",
    }),
  );
  assert.throws(() =>
    CheckpointInput.parse({
      ...question,
      workSummary: "Bearer abcdefghijklmnopqrstuvwxyz",
    }),
  );
  assert.throws(() => CheckpointInput.parse({ ...question, history: [] }));
  assert.equal(CheckpointInput.parse(question).activeFlags[0], "/strict");
});
test("allowed and forbidden state transitions", () => {
  const d = { state: "CREATING" } as Detour;
  assert.equal(transition(d, "ACTIVE").state, "ACTIVE");
  assert.throws(() => transition(d, "COMPLETED"));
});
test("independent session and ID association persisted before create", async () => {
  const f = fixture();
  const create = f.host.create;
  f.host.create = async (...args) => {
    assert.equal(f.data.size, 1);
    return create(...args);
  };
  const d = await f.m.start("source", "sidecar", question);
  assert.equal(d.state, "ACTIVE");
  assert.equal(d.sourceRole, "copilot");
  assert.equal(d.sourceServerId, "sidecar");
  assert.equal(f.sessions.get(d.coachSessionId)?.parentID, undefined);
  assert.equal(f.creates[0].agent, "coach");
  assert.equal(
    (await f.m.byId(d.id)).checkpoint?.learningQuestion,
    question.learningQuestion,
  );
});
test("duplicate events, distinct sources and nested detours", async () => {
  const f = fixture();
  const [a, b] = await Promise.all([
    f.m.start("source", "sidecar", question),
    f.m.start("source", "sidecar", question),
  ]);
  assert.equal(a.id, b.id);
  assert.equal(f.creates.length, 1);
  await assert.rejects(
    f.m.start(a.coachSessionId, "sidecar", question),
    /Nested/,
  );
  f.sessions.set("second", {
    id: "second",
    agent: "executor",
    location: { directory: "/work" },
  });
  assert.notEqual((await f.m.start("second", "sidecar", question)).id, a.id);
});
test("restart reconstructs active relationship without duplication", async () => {
  const f = fixture(),
    a = await f.m.start("source", "sidecar", question);
  const fresh = new Detours(f.store, f.host, "coach");
  assert.equal((await fresh.start("source", "sidecar", question)).id, a.id);
  assert.equal(f.creates.length, 1);
});
test("failed create recover reuses reserved id; partial side effect is adopted", async () => {
  const f = fixture(),
    create = f.host.create;
  f.host.create = async () => {
    throw Error("offline");
  };
  const d = await f.m.start("source", "sidecar", question);
  assert.equal(d.state, "FAILED");
  f.sessions.set(d.coachSessionId, {
    id: d.coachSessionId,
    location: { directory: "/work" },
    metadata: { learningDetour: d.id },
  });
  f.host.create = create;
  assert.equal((await f.m.recover(d.id)).state, "ACTIVE");
  assert.equal(f.creates.length, 0);
});
test("missing source keeps saved result; return and acknowledgement are idempotent", async () => {
  const f = fixture(),
    d = await f.m.start("source", "sidecar", question);
  f.sessions.delete("source");
  const a = await f.m.finish(d.coachSessionId, answer),
    b = await f.m.finish(d.coachSessionId, { ...answer, summary: "duplicate" });
  assert.equal(a.state, "RETURNING");
  assert.deepEqual(a.result, b.result);
  assert.match(renderResult(a), /NOT permission/);
  assert.ok(renderResult(a).length < 4000);
  await f.m.acknowledge(d.id);
  await f.m.acknowledge(d.id);
  assert.equal((await f.m.byId(d.id)).state, "COMPLETED");
});
test("cancel preserves sessions and releases source", async () => {
  const f = fixture(),
    d = await f.m.start("source", "sidecar", question);
  await f.m.cancel(d.id);
  assert.equal(f.sessions.size, 2);
  assert.notEqual((await f.m.start("source", "sidecar", question)).id, d.id);
});
test("retention removes terminal transient records and preserves active", async () => {
  const f = fixture(),
    d = await f.m.start("source", "sidecar", question);
  await f.m.cleanup(1, Date.now() + 2 * 86400000);
  assert.equal((await f.m.all()).length, 1);
  await f.m.cancel(d.id);
  await f.m.cleanup(1, Date.now() + 2 * 86400000);
  assert.equal((await f.m.all()).length, 0);
});
test("result limits and status reject unsupported claims", () => {
  assert.throws(() =>
    ResultInput.parse({ ...answer, understandingStatus: "mastered" }),
  );
  assert.throws(() =>
    ResultInput.parse({ ...answer, summary: "x".repeat(1001) }),
  );
});
