#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { randomUUID } from "node:crypto";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";
import { parse, parseTree, modify, applyEdits } from "jsonc-parser";
export const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
export const coachDefinition = {
  mode: "primary",
  description: "Learning Detour managed Coach",
  system:
    "You are a learning mentor. Use the available Coach skill if configured. Explain and practice the supplied narrow question. Do not implement the working task. No project changes without explicit user authorization.",
};
export function plan(
  old,
  uri,
  {
    coach = "coach",
    sources = ["build", "plan", "copilot", "executor"],
    remove = false,
    createCoach = false,
  } = {},
) {
  const errors = [];
  const data = parse(old, errors, { allowTrailingComma: true });
  if (errors.length || !data || Array.isArray(data) || typeof data !== "object")
    throw Error("Invalid JSON/JSONC");
  // Duplicate keys are ambiguous; parseTree exposes every property, including duplicates.
  const visit = (node) => {
    if (node?.type === "object") {
      const keys = node.children.map((p) => p.children[0].value);
      if (new Set(keys).size !== keys.length) throw Error("Duplicate JSON key");
    }
    for (const c of node?.children ?? []) visit(c);
  };
  visit(parseTree(old));
  if (data.plugins !== undefined && !Array.isArray(data.plugins))
    throw Error("plugins must be an array");
  const plugins = data.plugins ?? [];
  if (
    plugins.some(
      (p) => typeof p !== "string" && (!p || typeof p.package !== "string"),
    )
  )
    throw Error("Invalid plugins entry");
  const own = plugins.filter(
    (p) => (typeof p === "string" ? p : p.package) === uri,
  );
  const ambiguous = plugins.some(
    (p) =>
      (typeof p === "string" ? p : p.package) !== uri &&
      /(?:^|[/@])(?:opencode-learning-detour|learning-detour)(?:@|$|\/)/.test(
        typeof p === "string" ? p : p.package,
      ),
  );
  if (ambiguous)
    throw Error(
      "Another Learning Detour installation exists; resolve it first",
    );
  if (
    data.agents !== undefined &&
    (!data.agents ||
      Array.isArray(data.agents) ||
      typeof data.agents !== "object")
  )
    throw Error("agents must be an object");
  const existing = data.agents?.[coach];
  if (
    existing !== undefined &&
    (!existing || typeof existing !== "object" || Array.isArray(existing))
  )
    throw Error("Coach agent config must be an object");
  if (
    !remove &&
    existing &&
    (existing.mode === "subagent" || existing.disabled)
  )
    throw Error("Coach must be an enabled primary/all agent");
  if (!remove && !existing && !createCoach)
    throw Error("Configure a primary Coach agent or pass --create-coach");
  const entry = {
    package: uri,
    options: { coachAgent: coach, sourceAgents: sources },
  };
  const set = (text, at, value) =>
    applyEdits(
      text,
      modify(text, at, value, {
        formattingOptions: { insertSpaces: true, tabSize: 2 },
      }),
    );
  let out = set(
    old,
    ["plugins"],
    remove
      ? plugins.filter((p) => !own.includes(p))
      : [...plugins.filter((p) => !own.includes(p)), entry],
  );
  if (!remove && !existing && createCoach)
    out = set(out, ["agents", coach], coachDefinition);
  if (remove && JSON.stringify(existing) === JSON.stringify(coachDefinition))
    out = set(out, ["agents", coach], undefined);
  return out;
}
export function install(args = process.argv.slice(2)) {
  const value = (flag) => {
    const i = args.indexOf(flag);
    if (i < 0) return undefined;
    if (!args[i + 1] || args[i + 1].startsWith("--"))
      throw Error("Missing " + flag);
    return args[i + 1];
  };
  const coach = value("--coach-agent") ?? "coach",
    sources = (value("--source-agents") ?? "build,plan,copilot,executor").split(
      ",",
    );
  const flags = new Set([
    "--config",
    "--coach-agent",
    "--source-agents",
    "--version",
    "--dry-run",
    "--verify",
    "--remove",
    "--create-coach",
  ]);
  for (let i = 0; i < args.length; i++) {
    if (!flags.has(args[i])) throw Error("Unknown argument " + args[i]);
    if (
      ["--config", "--coach-agent", "--source-agents", "--version"].includes(
        args[i],
      )
    )
      i++;
  }
  if (!coach || sources.some((s) => !s) || sources.includes(coach))
    throw Error("Coach and source agents must be distinct");
  const dir = path.join(
    process.env.XDG_CONFIG_HOME ?? path.join(os.homedir(), ".config"),
    "opencode",
  );
  const candidates = [
    path.join(dir, "opencode.json"),
    path.join(dir, "opencode.jsonc"),
  ].filter((p) => fs.existsSync(p));
  if (!value("--config") && candidates.length > 1)
    throw Error("Both JSON and JSONC configs exist; specify --config");
  const config = path.resolve(
    value("--config") ?? candidates[0] ?? path.join(dir, "opencode.json"),
  );
  const uri = pathToFileURL(root).href;
  if (fs.existsSync(config) && fs.lstatSync(config).isSymbolicLink())
    throw Error("Refusing to replace a symlink config");
  const old = fs.existsSync(config) ? fs.readFileSync(config, "utf8") : "{}\n";
  const removing = args.includes("--remove");
  const cli = spawnSync("opencode", ["--version"], {
    encoding: "utf8",
    timeout: 10000,
    shell: false,
  });
  const version = value("--version") ?? cli.stdout?.trim();
  if (!removing && !/^2\.2\.5$/.test(version ?? ""))
    throw Error(
      "Need verified OpenCode 2.2.5; use --version 2.2.5 if CLI is absent and Desktop version is checked manually",
    );
  if (!removing)
    for (const p of ["dist/index.js", "dist/gui-experimental.cjs"])
      if (!fs.existsSync(path.join(root, p)))
        throw Error("Run npm ci and npm run build first: " + p);
  const out = plan(old, uri, {
    coach,
    sources,
    remove: removing,
    createCoach: args.includes("--create-coach"),
  });
  if (args.includes("--verify")) {
    if (
      plan(old, uri, {
        coach,
        sources,
        createCoach: args.includes("--create-coach"),
      }) !== old
    )
      throw Error("Plugin configuration does not match requested installation");
    console.log(
      "Config and bundles verified; runtime GUI/session behavior still needs Desktop smoke test",
    );
    return;
  }
  console.log((removing ? "Remove " : "Install ") + uri + " in " + config);
  if (args.includes("--dry-run")) {
    console.log("Dry-run: no writes");
    return;
  }
  if (out === old) {
    console.log("Already configured");
    return;
  }
  fs.mkdirSync(path.dirname(config), { recursive: true });
  // Serialize cooperating installers; reject concurrent edits, preserve permissions, one atomic config replacement.
  const lock = config + ".learning-detour.lock";
  const fd = fs.openSync(lock, "wx", 0o600);
  const tmp = config + ".learning-detour-" + process.pid + ".tmp";
  try {
    if (
      (fs.existsSync(config) ? fs.readFileSync(config, "utf8") : "{}\n") !== old
    )
      throw Error("Config changed during preflight");
    if (fs.existsSync(config))
      fs.writeFileSync(
        config + ".learning-detour-" + randomUUID() + ".backup",
        old,
        { flag: "wx", mode: 0o600 },
      );
    fs.writeFileSync(tmp, out, {
      flag: "wx",
      mode: fs.existsSync(config) ? fs.statSync(config).mode & 0o777 : 0o600,
    });
    fs.renameSync(tmp, config);
  } finally {
    if (fs.existsSync(tmp)) fs.unlinkSync(tmp);
    fs.closeSync(fd);
    fs.unlinkSync(lock);
  }
  console.log(
    "Saved. Restart/reopen OpenCode to load the plugin. Existing sessions and learning cards were not removed.",
  );
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    install();
  } catch (e) {
    console.error(String(e));
    process.exitCode = 1;
  }
}
