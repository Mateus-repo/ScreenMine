// Hook de pre-commit. Bloqueia e explica; nao desfaz, nao unstageia e nao
// perde trabalho. Se for falso positivo, commita com --no-verify e diz porquê
// na mensagem.
//
// Activar uma vez por clone:
//   git config core.hooksPath .githooks
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import { checkPaths, scanDiff, formatHits } from "./guard.js";

const git = (...a) =>
  execFileSync("git", a, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });

const root = git("rev-parse", "--show-toplevel").trim();

function local() {
  try {
    const f = `${root}/.opencode/guard.local.json`;
    if (!existsSync(f)) return { deny: [], allow: [] };
    const j = JSON.parse(readFileSync(f, "utf8"));
    const clean = (v) =>
      Array.isArray(v) ? v.filter((s) => typeof s === "string" && s.length > 2) : [];
    return { deny: clean(j.deny), allow: clean(j.allow) };
  } catch {
    return { deny: [], allow: [] };
  }
}

const cfg = local();
const files = git("diff", "--cached", "--name-only", "--diff-filter=ACMR")
  .split("\n").filter(Boolean);
const diff = git("diff", "--cached", "-U0", "--no-color");
const hits = [...checkPaths(files, cfg.allow), ...scanDiff(diff, cfg.deny)];

if (hits.length) {
  console.error("\n[guard] Commit bloqueado. Encontrei:\n" + formatHits(hits));
  console.error(
    "\nCorrige (usa <my-server>, <user>, etc.). Se for falso positivo:\n" +
      "  - commita com --no-verify e explica porquê na mensagem, ou\n" +
      "  - acrescenta o padrão em .opencode/guard.local.json (ignorado pelo git).\n",
  );
  process.exit(1);
}