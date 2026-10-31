import { deliverDraft } from "./draft.js";
import { Plugin, Command } from "@opencode/plugin/gui";
import { createEffect, createComponent, onCleanup } from "solid-js";
import { Bridge, renderResult, type Detour } from "./contracts.js";

export default Plugin.define({
  setup(ctx) {
    if (!/^2\.2\.5(?:$|[-+])/.test(ctx.app.version))
      throw new Error("Learning Detour needs OpenCode 2.2.5");
    const pendingDrafts = new Set<string>();
    let warned = false;
    let records: Detour[] = [],
      busy = false,
      alive = true;
    const opened = new Set<string>(),
      writing = new Set<string>(),
      seeded = new Set<string>();
    const warn = (title: string) => ctx.toast.show({ title, variant: "error" });
    async function refresh() {
      const current = ctx.sessions.current();
      if (busy || !current?.location) return;
      const server = current.server,
        location = current.location;
      const rpc = server.client.rpc(Bridge);
      busy = true;
      try {
        await rpc.bind(
          { sessionId: current.id, serverId: server.id },
          { location },
        );
        records = await rpc.list({}, { location });
        warned = false;
        if (!alive) return;
        for (const d of records.filter((d) => d.sourceServerId === server.id)) {
          // Do not steal focus merely on startup or every time the user switches back manually.
          if (d.state === "ACTIVE" && !opened.has(d.id)) {
            opened.add(d.id);
            if (
              d.sourceSessionId === current.id &&
              Date.now() - Date.parse(d.createdAt) < 15000
            ) {
              ctx.tabs.open(
                { server: server.id, id: d.coachSessionId },
                { focus: true },
              );
            }
          }
          if (
            d.state === "ACTIVE" &&
            Date.now() - Date.parse(d.createdAt) < 15000 &&
            d.coachSessionId === ctx.sessions.current()?.id &&
            server.id === ctx.sessions.current()?.server.id &&
            !seeded.has(d.id) &&
            ctx.screen.current()?.composer.text() === ""
          ) {
            seeded.add(d.id);
            ctx.screen
              .current()
              ?.composer.insert(
                "Давай разберём: " + d.checkpoint?.learningQuestion,
              );
          }
          if (d.state === "RETURNING" && d.result && d.checkpoint) {
            try {
              await server.client.session.get({ sessionID: d.sourceSessionId });
            } catch {
              continue;
            } // result stays durable
            if (!opened.has("return:" + d.id)) {
              opened.add("return:" + d.id);
              ctx.tabs.open(
                { server: server.id, id: d.sourceSessionId },
                { focus: true },
              );
            }
            if (writing.has(d.id)) continue;
            writing.add(d.id);
            try {
              await deliverDraft(
                d,
                {
                  exists: async (id) => {
                    await server.client.session.get({ sessionID: id });
                  },
                  busy: async (id) =>
                    !!(await server.client.session.active())[id],
                  current: () => {
                    const mounted = ctx.sessions.current(),
                      screen = ctx.screen.current();
                    return mounted && screen
                      ? {
                          id: mounted.id,
                          server: mounted.server.id,
                          composer: screen.composer,
                        }
                      : undefined;
                  },
                  acknowledge: (id) => rpc.acknowledge({ id }, { location }),
                },
                pendingDrafts,
              );
            } finally {
              writing.delete(d.id);
            }
          }
        }
      } catch (e) {
        if (alive)
          warn(
            "Learning Detour: connection unavailable; checkpoint remains on server",
          );
      } finally {
        busy = false;
      }
    }
    // Poll is a reconnect fallback, NOT an LLM classifier. Official RPC changes also trigger a refresh.
    let unsubscribe: (() => void) | undefined,
      lastKey = "";
    createEffect(() => {
      const cur = ctx.sessions.current();
      const key = cur?.key ?? "";
      if (key === lastKey) return;
      lastKey = key;
      unsubscribe?.();
      unsubscribe = undefined;
      if (cur) {
        unsubscribe = cur.server.client
          .rpc(Bridge)
          .events.on("changed", () => void refresh(), { signal: ctx.signal });
        void refresh();
      }
    });
    const timer = setInterval(() => void refresh(), 3000);
    ctx.add(Command, {
      id: "open-related",
      title: "Learning Detour: открыть связанную сессию",
      run: async () => {
        await refresh();
        const cur = ctx.sessions.current();
        if (!cur) return;
        const d = records.find(
          (d) =>
            d.sourceServerId === cur.server.id &&
            (d.sourceSessionId === cur.id || d.coachSessionId === cur.id) &&
            !["CANCELLED", "COMPLETED"].includes(d.state),
        );
        if (d)
          ctx.tabs.open(
            {
              server: cur.server.id,
              id:
                cur.id === d.sourceSessionId
                  ? d.coachSessionId
                  : d.sourceSessionId,
            },
            { focus: true },
          );
      },
    });
    ctx.add(Command, {
      id: "return-unverified",
      title: "Learning Detour: вернуться без проверки знаний",
      run: async () => {
        await refresh();
        const cur = ctx.sessions.current();
        if (!cur?.location) return;
        const d = records.find(
          (d) => d.coachSessionId === cur.id && d.state === "ACTIVE",
        );
        if (!d) return;
        await cur.server.client
          .rpc(Bridge)
          .returnUnverified({ id: d.id }, { location: cur.location });
        await refresh();
      },
    });
    ctx.add(Command, {
      id: "result",
      title: "Learning Detour: показать сохранённый итог",
      run: async () => {
        await refresh();
        const cur = ctx.sessions.current();
        if (!cur) return;
        const d = [...records]
          .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
          .find(
            (d) =>
              (d.sourceSessionId === cur.id || d.coachSessionId === cur.id) &&
              d.result &&
              d.checkpoint,
          );
        if (!d) return;
        ctx.dialogs.open(() =>
          createComponent(ctx.ui.Dialog, {
            title: "Learning Detour",
            get children() {
              return createComponent(ctx.ui.Markdown, {
                text: renderResult(d),
              });
            },
          }),
        );
      },
    });
    ctx.add(Command, {
      id: "recover",
      title: "Learning Detour: восстановить переход",
      run: async () => {
        await refresh();
        const cur = ctx.sessions.current();
        if (!cur?.location) return;
        const d = records.find(
          (d) =>
            d.sourceSessionId === cur.id &&
            ["CREATING", "FAILED"].includes(d.state),
        );
        if (!d) return;
        await cur.server.client
          .rpc(Bridge)
          .recover({ id: d.id }, { location: cur.location });
        await refresh();
      },
    });
    ctx.add(Command, {
      id: "cancel",
      title: "Learning Detour: отменить переход без удаления сессий",
      run: async () => {
        await refresh();
        const cur = ctx.sessions.current();
        if (!cur?.location) return;
        const d = records.find(
          (d) =>
            (d.sourceSessionId === cur.id || d.coachSessionId === cur.id) &&
            !["COMPLETED", "CANCELLED"].includes(d.state),
        );
        if (!d) return;
        await cur.server.client
          .rpc(Bridge)
          .cancel({ id: d.id }, { location: cur.location });
        await refresh();
      },
    });
    // A primary session has a full composer. First lesson is user-sent, never an impersonated user message.
    onCleanup(() => {
      alive = false;
      clearInterval(timer);
      unsubscribe?.();
    });
  },
});
