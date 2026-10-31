import { renderResult, type Detour } from "./contracts.js";
export interface DraftHost {
  current():
    | {
        server: string;
        id: string;
        composer: { text(): string; insert(text: string): void; focus(): void };
      }
    | undefined;
  exists(id: string): Promise<void>;
  busy(id: string): Promise<boolean>;
  acknowledge(id: string): Promise<unknown>;
}
/** No prompt, noReply, synthetic message, submit, role-switch or model call is exposed here. */
export async function deliverDraft(
  d: Detour,
  host: DraftHost,
  pending = new Set<string>(),
): Promise<boolean> {
  if (d.state !== "RETURNING" || !d.result || !d.checkpoint) return false;
  await host.exists(d.sourceSessionId);
  if (await host.busy(d.sourceSessionId)) return false;
  // Screen follows navigation: capture it AFTER network awaits and verify both server and session.
  const current = host.current();
  if (current?.id !== d.sourceSessionId || current.server !== d.sourceServerId)
    return false;
  const marker = `[Learning Detour ${d.id} `;
  if (current.composer.text().includes(marker)) {
    await host.acknowledge(d.id);
    return true;
  }
  if (pending.has(d.id)) return false;
  pending.add(d.id);
  current.composer.insert("\n" + renderResult(d));
  current.composer.focus();
  // Synchronous inserts are acknowledged before a user can send/clear the draft.
  if (current.composer.text().includes(marker)) {
    await host.acknowledge(d.id);
    return true;
  }
  // Keep the pending marker until visible, across polls/navigation; never enqueue another insert.
  return false;
}
