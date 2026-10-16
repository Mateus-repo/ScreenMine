// Auto commit (e push) quando a sessao do opencode fica idle.
// Rede de seguranca: os commits "bons" sao feitos pelo agente (skill auto-commit).
//
// Diferencas para a versao anterior:
//  - NAO commita se houver merge/rebase/cherry-pick a meio (neste repo um merge
//    errado apaga o mod; ver progresso.md "Topologia das branches").
//  - Faz stage, corre a guarda de privacidade (.opencode/lib/guard.js) e, se
//    encontrar segredo, hostname privado, caminho local ou ficheiro proibido,
//    desfaz o stage e NAO commita nem faz push. O repo e publico.
//  - Nunca faz push --force.
// Variaveis: OPENCODE_AUTOCOMMIT=0 desliga tudo; OPENCODE_AUTOPUSH=0 so o push.
import { existsSync } from "node:fs";
import { loadLocalDeny, checkPaths, scanDiff, formatHits } from "../lib/guard.js";

export const AutoCommit = async ({ $, directory, client }) => {
  let running = false;

  const git = async (...args) =>
    await $`git -C ${directory} ${args}`.nothrow().quiet();

  const out = async (...args) => (await git(...args)).stdout.toString();

  const say = async (level, message) => {
    try {
      await client?.app?.log?.({ body: { service: "auto-commit", level, message } });
    } catch {
      /* sem logger: cai para a consola */
    }
    (level === "error" || level === "warn" ? console.error : console.log)(`[auto-commit] ${message}`);
  };

  const gitPathExists = async (name) => {
    const p = (await out("rev-parse", "--git-path", name)).trim();
    if (!p) return false;
    // --git-path pode devolver relativo ao directorio do repo
    return existsSync(p) || existsSync(`${directory}/${p}`);
  };

  return {
    event: async ({ event }) => {
      if (event.type !== "session.idle") return;
      if (process.env.OPENCODE_AUTOCOMMIT === "0") return;
      if (running) return;
      running = true;
      try {
        const inside = await git("rev-parse", "--is-inside-work-tree");
        if (inside.exitCode !== 0) return;

        for (const marker of ["MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"]) {
          if (await gitPathExists(marker)) {
            await say("warn", `operacao git a meio (${marker}); nao commito.`);
            return;
          }
        }

        const status = (await out("status", "--porcelain")).trim();
        if (!status) return;

        const files = status.split("\n").map((l) => l.slice(3));
        await git("add", "-A");

        // Guarda de privacidade sobre o que ficou em stage.
        const staged = (await out("diff", "--cached", "--name-only", "--diff-filter=ACMR"))
          .split("\n")
          .filter(Boolean);
        const diff = await out("diff", "--cached", "-U0", "--no-color");
        const hits = [...checkPaths(staged), ...scanDiff(diff, loadLocalDeny(directory))];
        if (hits.length) {
          await git("reset", "-q");
          await say(
            "error",
            `commit automatico BLOQUEADO (repo publico). Corrige e tenta de novo:\n${formatHits(hits)}`,
          );
          return;
        }

        const shown = files.slice(0, 10).map((f) => `- ${f}`).join("\n");
        const more = files.length > 10 ? `\n- ... (+${files.length - 10})` : "";
        const msg = `wip(auto): ${files.length} file(s) changed\n\n${shown}${more}`;

        const c = await git("commit", "-m", msg);
        if (c.exitCode !== 0) return;

        if (process.env.OPENCODE_AUTOPUSH !== "0") {
          const remote = await git("remote", "get-url", "origin");
          if (remote.exitCode === 0) {
            const p = await git("push");
            if (p.exitCode !== 0) await say("warn", "push falhou (sem upstream ou sem rede); o commit ficou local.");
          }
        }
      } finally {
        running = false;
      }
    },
  };
};
