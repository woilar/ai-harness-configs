import { Plugin, Agent } from "@opencode/plugin";
import { Session } from "@opencode/schema/session";
import {
  Bridge,
  ID,
  CheckpointInput,
  ResultInput,
  settings,
} from "./contracts.js";
import { Detours, type Store } from "./core.js";

export default Plugin.define({
  id: ID,
  async setup(ctx) {
    if (!/^2\.2\.5(?:$|[-+])/.test(ctx.app.version))
      throw new Error(
        "Learning Detour targets OpenCode 2.2.5; verify API before upgrading",
      );
    const options = settings.parse(ctx.options);
    if (options.sourceAgents.includes(options.coachAgent))
      throw new Error("Coach and source agent IDs must differ");
    const store: Store = {
      get: (key) => ctx.storage.get(key),
      set: async (key, value) =>
        ctx.storage.set(key, JSON.parse(JSON.stringify(value))),
      remove: (key) => ctx.storage.remove(key),
      scan: async () => {
        const out: Array<{ key: string; value: unknown }> = [];
        let after: string | undefined;
        do {
          const p = await ctx.storage.scan({
            prefix: "detour:",
            limit: 100,
            after,
          });
          out.push(...p.entries);
          after = p.next;
        } while (after);
        return out;
      },
    };
    const manager = new Detours(
      store,
      {
        get: (id) => ctx.session.get({ sessionID: Session.ID.make(id) }),
        create: (id, directory, agent, detourId) =>
          ctx.session.create({
            id: Session.ID.make(id),
            agent: Agent.ID.make(agent),
            location: { directory },
            title: "Learning Detour",
            metadata: { learningDetour: detourId },
            permissions: [{ action: "*", resource: "*", effect: "ask" }],
          }),
        hasAgent: async (id) => {
          const a = await ctx.agent.get({ agentID: Agent.ID.make(id) });
          return a.data.mode !== "subagent";
        },
      },
      options.coachAgent,
    );
    await manager.cleanup(options.retentionDays);
    let afterBinding: string | undefined;
    do {
      const page = await ctx.storage.scan({
        prefix: "binding:",
        limit: 100,
        after: afterBinding,
      });
      for (const entry of page.entries) {
        const lease = entry.value as { expiresAt?: number };
        if (!lease?.expiresAt || lease.expiresAt < Date.now())
          await ctx.storage.remove(entry.key);
      }
      afterBinding = page.next;
    } while (afterBinding);

    const rpc = await ctx.rpc.register(Bridge, {
      bind: async ({ sessionId, serverId }) => {
        await ctx.session.get({ sessionID: Session.ID.make(sessionId) });
        await store.set("binding:" + sessionId, {
          serverId,
          expiresAt: Date.now() + 15000,
        });
        return true;
      },
      list: async () => manager.all(),
      acknowledge: async ({ id }) => manager.acknowledge(id),
      returnUnverified: async ({ id }) => {
        const d = await manager.byId(id);
        const returned = await manager.finish(d.coachSessionId, {
          summary: "User returned via GUI without a verified learning report.",
          resolvedQuestion: "Not independently verified",
          remainingUncertainties: [
            "Review learning evidence before implementation",
          ],
          understandingStatus: "unverified",
        });
        await changed(returned.id);
        return returned;
      },
      cancel: async ({ id }) => manager.cancel(id),
      recover: async ({ id }) => manager.recover(id),
    });
    const changed = async (id: string) => {
      await rpc.events.emit("changed", { id });
    };
    const tools = await ctx.tool.transform((editor) => {
      editor.add({
        name: "learning_detour_start",
        options: { codemode: false },
        input: CheckpointInput,
        description:
          "Create an independent interactive Coach session for substantial learning. Use only after a clear learning intent; checkpoint must be a short safe summary, never source code. Stop working after success. Not for short syntax questions.",
        execute: async (input, call) => {
          if (!options.sourceAgents.includes(call.agent))
            throw new Error("Not an allowed source agent");
          const lease = (await store.get("binding:" + call.sessionID)) as
            { serverId?: unknown; expiresAt?: unknown } | undefined;
          if (
            typeof lease?.serverId !== "string" ||
            typeof lease.expiresAt !== "number" ||
            lease.expiresAt < Date.now()
          )
            throw new Error(
              "Desktop GUI is not connected; continue with normal handoff, no session created",
            );
          const d = await manager.start(call.sessionID, lease.serverId, input);
          await changed(d.id);
          return {
            content: JSON.stringify({
              id: d.id,
              state: d.state,
              coachSessionId: d.coachSessionId,
              instruction:
                "Work paused. Desktop opens Coach; do not continue implementation.",
            }),
          };
        },
      });
      editor.add({
        name: "learning_detour_return",
        options: { codemode: false },
        input: ResultInput,
        description:
          "Return from this Coach detour after the user asks in natural language. Summarize only what was learned; sufficient requires independent evidence. Saves result and opens source draft without sending it.",
        execute: async (input, call) => {
          const d = await manager.finish(call.sessionID, input);
          await changed(d.id);
          return {
            content: `Result saved (${d.id}). Return pending in source composer; no work is authorized.`,
          };
        },
      });
    });
    // An actual user sending the marked draft is also proof of delivery (no fake user message).
    const submitted = await ctx.session.hook("prompt", async (event) => {
      for (const d of await manager.all()) {
        if (
          d.state === "RETURNING" &&
          d.sourceSessionId === event.sessionID &&
          event.prompt.text.includes(`[Learning Detour ${d.id} `)
        )
          await manager.acknowledge(d.id);
      }
    });
    const context = await ctx.session.hook("context", async (event) => {
      const records = await manager.all();
      const d = records.find(
        (d) =>
          d.coachSessionId === event.sessionID &&
          ["ACTIVE", "RETURNING"].includes(d.state),
      );
      if (d?.checkpoint) {
        event.system.push({
          type: "text",
          text: `Focused Learning detour ${d.id}. Read the configured Coach skill. Scope: ${d.checkpoint.learningQuestion}. Work goal (safe summary): ${d.checkpoint.workSummary}. Teach one concept then practice. No project modifications. Side topics go to existing Learning Backlog, not nested sessions. On natural return intent call learning_detour_return with a brief evidence-based result. No timer/message limit. Occasionally mention unfinished work only at a natural topic boundary.`,
        });
      }
      const paused = records.some(
        (d) =>
          d.sourceSessionId === event.sessionID &&
          ["CREATING", "ACTIVE", "RETURNING", "FAILED"].includes(d.state),
      );
      if (paused) {
        event.system.push({
          type: "text",
          text: "Working session paused for a Learning Detour. Do not execute work. Wait for the user to return and explicitly authorize continuation.",
        });
        for (const key of Object.keys(event.tools)) delete event.tools[key];
      }
    });
    // Guard batched tool requests as well as subsequent model dispatches.
    const guard = await ctx.tool.hook("execute.before", async (e) => {
      if (e.tool === "learning_detour_start") return;
      const all = await manager.all();
      if (
        all.some(
          (d) =>
            d.sourceSessionId === e.sessionID &&
            ["CREATING", "ACTIVE", "RETURNING", "FAILED"].includes(d.state),
        )
      )
        throw new Error("Working tools paused during Learning Detour");
    });
    return async () => {
      await submitted.dispose();
      await guard.dispose();
      await context.dispose();
      await tools.dispose();
      await rpc.dispose();
    };
  },
});
