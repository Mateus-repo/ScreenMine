// Uso: node .opencode/lib/guard-cli.js   (verifica o que esta em stage)
// Sai com codigo 1 se encontrar algo. Chamado por .githooks/pre-commit.
import { execFileSync } from "node:child_process";
import { loadLocalDeny, checkPaths, scanDiff, formatHits } from "./guard.js";

const git = (...a) => execFileSync("git", a, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const root = git("rev-parse", "--show-toplevel").trim();
const files = git("diff", "--cached", "--name-only", "--diff-filter=ACMR").split("\n").filter(Boolean);
const diff = git("diff", "--cached", "-U0", "--no-color");
const hits = [...checkPaths(files), ...scanDiff(diff, loadLocalDeny(root))];

if (hits.length) {
  console.error("\n[guard] Commit bloqueado. Encontrei:\n" + formatHits(hits));
  console.error("\nCorrige (usa <my-server>, <user>, etc.) ou, se for falso positivo, commita com --no-verify e explica no commit.\n");
  process.exit(1);
}
