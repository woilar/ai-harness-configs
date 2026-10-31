import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { plan } from "../scripts/install.mjs";
const uri = "file:///portable/learning-detour";
test("JSONC preserves comments and other configuration, repeat is idempotent", () => {
  const old = '{// keep\n"model":"example", "plugins":["other"],}';
  const a = plan(old, uri, { createCoach: true });
  assert.match(a, /\/\/ keep/);
  assert.match(a, /example/);
  assert.match(a, /other/);
  assert.equal(plan(a, uri), a);
  const b = plan(a, uri, { remove: true });
  assert.match(b, /other/);
  assert.ok(!b.includes(uri));
  assert.ok(!b.includes("managed Coach"));
});
test("conflicts rejected before any writes", () => {
  for (const data of [
    '{"plugins":{}}',
    '{"plugins":[],"plugins":[]}',
    '{"agents":{"coach":{"mode":"subagent"}}}',
    "invalid",
  ])
    assert.throws(() => plan(data, uri, { createCoach: true }));
  assert.throws(() => plan("{}", uri), /primary Coach/);
});
test("native Node installer supports dry-run, verify, repeated install and removal", () => {
  const dir = mkdtempSync(join(tmpdir(), "detour-install-")),
    config = join(dir, "config.jsonc");
  const run = (...args: string[]) =>
    spawnSync(
      process.execPath,
      [
        "scripts/install.mjs",
        "--config",
        config,
        "--version",
        "2.2.5",
        ...args,
      ],
      { encoding: "utf8" },
    );
  try {
    writeFileSync(config, '{// user\n"plugins":["other"]}');
    const old = readFileSync(config, "utf8");
    assert.equal(run("--create-coach", "--dry-run").status, 0);
    assert.equal(readFileSync(config, "utf8"), old);
    assert.equal(run("--create-coach").status, 0);
    const installed = readFileSync(config, "utf8");
    assert.equal(run().status, 0);
    assert.equal(readFileSync(config, "utf8"), installed);
    assert.equal(run("--verify").status, 0);
    assert.equal(run("--remove").status, 0);
    assert.match(readFileSync(config, "utf8"), /other/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
