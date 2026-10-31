import { z } from "zod";
import { Rpc } from "@opencode/plugin/rpc";
export const ID = "learning-detour";
const text = (max: number) =>
  z
    .string()
    .min(1)
    .max(max)
    .refine(
      (s) => !/```|-----BEGIN|(?:Bearer\s+|sk-)[A-Za-z0-9_-]{15,}/i.test(s),
      "Do not include code, credentials or logs",
    );
export const CheckpointInput = z
  .object({
    workSummary: text(1000),
    nextAction: text(600),
    learningQuestion: text(800),
    currentPhase: text(80).optional(),
    agreementReference: text(300).optional(),
    activeFlags: z.array(text(60)).max(8).default([]),
    // Safe summaries only, even though these stay on the work server.
  })
  .strict();
export type CheckpointInput = z.infer<typeof CheckpointInput>;
export const ResultInput = z
  .object({
    summary: text(1000),
    resolvedQuestion: text(600),
    remainingUncertainties: z.array(text(250)).max(6),
    understandingStatus: z.enum(["sufficient", "partial", "unverified"]),
  })
  .strict();
export type ResultInput = z.infer<typeof ResultInput>;
export type State =
  "CREATING" | "ACTIVE" | "RETURNING" | "COMPLETED" | "CANCELLED" | "FAILED";
export type Detour = {
  id: string;
  sourceSessionId: string;
  sourceServerId: string;
  sourceRole: string;
  sourceDirectory: string;
  coachSessionId: string;
  state: State;
  checkpoint?: CheckpointInput;
  result?: ResultInput;
  createdAt: string;
  updatedAt: string;
  error?: string;
};
export const RecordSchema = z.object({
  id: z.string(),
  sourceSessionId: z.string(),
  sourceServerId: z.string(),
  sourceRole: z.string(),
  sourceDirectory: z.string(),
  coachSessionId: z.string(),
  state: z.enum([
    "CREATING",
    "ACTIVE",
    "RETURNING",
    "COMPLETED",
    "CANCELLED",
    "FAILED",
  ]),
  checkpoint: CheckpointInput.optional(),
  result: ResultInput.optional(),
  createdAt: z.string(),
  updatedAt: z.string(),
  error: z.string().optional(),
});
export const Bridge = Rpc.define({
  id: ID,
  methods: {
    bind: {
      input: z.object({ sessionId: z.string(), serverId: z.string() }),
      output: z.boolean(),
    },
    list: { input: z.object({}), output: z.array(RecordSchema) },
    acknowledge: { input: z.object({ id: z.string() }), output: z.boolean() },
    returnUnverified: {
      input: z.object({ id: z.string() }),
      output: RecordSchema,
    },
    cancel: { input: z.object({ id: z.string() }), output: z.boolean() },
    recover: { input: z.object({ id: z.string() }), output: RecordSchema },
  },
  events: { changed: { schema: z.object({ id: z.string() }) } },
});
export const settings = z
  .object({
    coachAgent: z.string().default("coach"),
    sourceAgents: z
      .array(z.string())
      .default(["build", "plan", "copilot", "executor"]),
    retentionDays: z.number().int().min(1).max(90).default(14),
  })
  .strict();
export function renderResult(d: Detour) {
  const r = d.result;
  if (!r || !d.checkpoint) throw new Error("Missing return context");
  return (
    `[Learning Detour ${d.id} — context draft, NOT permission to execute]\n` +
    `Work: ${d.checkpoint.workSummary}\nPhase: ${d.checkpoint.currentPhase ?? "unchanged"}; flags: ${d.checkpoint.activeFlags.join(", ") || "none"}\n` +
    `Agreement: ${d.checkpoint.agreementReference ?? "existing conversation"}\n` +
    `Learned: ${r.summary}\nQuestion: ${r.resolvedQuestion}\nUnderstanding: ${r.understandingStatus}\n` +
    `Still unknown: ${r.remainingUncertainties.join("; ") || "none recorded"}\nNext proposed step: ${d.checkpoint.nextAction}\n` +
    `Verify current code and agreement first. Wait for explicit user instruction before any implementation.\n`
  );
}
