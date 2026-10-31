import test from "node:test";
import assert from "node:assert/strict";
import { deliverDraft, type DraftHost } from "../src/draft.js";
import type { Detour } from "../src/contracts.js";
const d: Detour = {
  id: "d",
  sourceSessionId: "s",
  sourceServerId: "sidecar",
  sourceRole: "copilot",
  sourceDirectory: "/work",
  coachSessionId: "c",
  state: "RETURNING",
  createdAt: "now",
  updatedAt: "now",
  checkpoint: {
    workSummary: "Task",
    nextAction: "Review",
    learningQuestion: "Atomicity",
    activeFlags: ["/strict"],
  },
  result: {
    summary: "One concept",
    resolvedQuestion: "Interleavings",
    remainingUncertainties: [],
    understandingStatus: "unverified",
  },
};
function fixture() {
  let text = "My existing draft",
    acks = 0,
    focus = 0;
  const h: DraftHost = {
    exists: async () => {},
    busy: async () => false,
    acknowledge: async () => {
      acks++;
    },
    current: () => ({
      server: "sidecar",
      id: "s",
      composer: {
        text: () => text,
        insert: (s) => {
          text += s;
        },
        focus: () => {
          focus++;
        },
      },
    }),
  };
  return { h, text: () => text, acks: () => acks, focus: () => focus };
}
test("return preserves user draft, verifies insertion then acknowledges only once per marker", async () => {
  const f = fixture();
  assert.equal(await deliverDraft(d, f.h), true);
  assert.match(f.text(), /^My existing draft/);
  assert.equal(f.acks(), 1);
  await deliverDraft(d, f.h);
  assert.equal(f.acks(), 2);
  assert.equal(f.text().split("[Learning Detour").length, 2);
  assert.equal(f.focus(), 1);
});
test("busy, absent and differently routed source never receive writes", async () => {
  const f = fixture();
  f.h.busy = async () => true;
  await deliverDraft(d, f.h);
  assert.equal(f.text(), "My existing draft");
  f.h.busy = async () => false;
  f.h.current = () => undefined;
  await deliverDraft(d, f.h);
  assert.equal(f.acks(), 0);
  f.h.exists = async () => {
    throw { _tag: "SessionNotFoundError" };
  };
  await assert.rejects(deliverDraft(d, f.h));
  assert.equal(f.acks(), 0);
});
test("navigation during awaited status request cannot write into another composer", async () => {
  const f = fixture();
  f.h.busy = async () => {
    f.h.current = () => ({
      id: "other",
      server: "sidecar",
      composer: {
        text: () => "",
        insert: () => assert.fail("wrong session"),
        focus: () => {},
      },
    });
    return false;
  };
  assert.equal(await deliverDraft(d, f.h), false);
  assert.equal(f.acks(), 0);
});
test("same session ID on another server is not the target", async () => {
  const f = fixture();
  const cur = f.h.current()!;
  f.h.current = () => ({ ...cur, server: "remote" });
  await deliverDraft(d, f.h);
  assert.equal(f.text(), "My existing draft");
});

test("queued composer writes are enqueued only once across polls", async () => {
  const f = fixture(),
    pending = new Set<string>();
  let queued = 0,
    text = "";
  f.h.current = () => ({
    server: "sidecar",
    id: "s",
    composer: {
      text: () => text,
      insert: () => {
        queued++;
      },
      focus: () => {},
    },
  });
  await deliverDraft(d, f.h, pending);
  await deliverDraft(d, f.h, pending);
  assert.equal(queued, 1);
  assert.equal(f.acks(), 0);
  text = "[Learning Detour d context";
  await deliverDraft(d, f.h, pending);
  assert.equal(queued, 1);
  assert.equal(f.acks(), 1);
});
