// Auto commit (e push) quando a sessao do opencode fica idle.
//
// Rede de seguranca, em tres camadas:
//   1. Nao commita com merge/rebase/cherry-pick a meio. Neste repo um merge
//      errado apaga o mod; ver progresso.md "Topologia das branches".
//   2. Usa `git add -u`, que so stageia ficheiros JA versionados. `git add -A`
//      e o que versou os zips e as transcricoes de outro agente para um repo
//      publico. Ficheiros novos passam a ser adicionados de proposito, um a
//      um, e nao por um `add` cego. Eles aparecem na mensagem para nao passar
//      despercebidos.
//   3. Corre a guarda de privacidade sobre o que ficou em stage. Se encontrar
//      um segredo, um hostname privado, um caminho local ou um ficheiro que
//      nao deve ser versionado, desstageia e nao commita nem faz push.
//
// Nunca faz push --force.
//
// Ambiente:
//   OPENCODE_AUTOCOMMIT=0   desliga o commit automatico
//   OPENCODE_AUTOPUSH=0     commita mas nao faz push
import { existsSync, readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { join } from "node:path";

export const AutoCommit = async ({ $, directory, client }) => {
  let running = false;

  const git = async (...args) =>
    await $`git -C ${directory} ${args}`.nothrow().quiet();

  const out = async (...args) => (await git(...args)).stdout.toString();

  const say = async (level, message) => {
    const line = `[auto-commit] ${message}`;
    try {
      await client?.app?.log?.({
        body: { service: "auto-commit", level, message },
      });
    } catch {
      /* sem logger: cai para a consola */
    }
    (level === "error" || level === "warn" ? console.error : console.log)(line);
  };

  const gitPathExists = async (name) => {
    const p = (await out("rev-parse", "--git-path", name)).trim();
    if (!p) return false;
    return existsSync(p) || existsSync(join(directory, p));
  };

  // A guarda vive em .opencode/lib. Se nao estiver la - repo clonado sem esse
  // commit, por exemplo - nao bloqueamos o fluxo: o commit automatico e uma
  // comodidade, nao uma barreira.
  const loadGuard = async () => {
    try {
      const url = pathToFileURL(
        join(directory, ".opencode", "lib", "guard.js"),
      ).href;
      return await import(url);
    } catch {
      return null;
    }
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

        for (const marker of [
          "MERGE_HEAD",
          "CHERRY_PICK_HEAD",
          "REVERT_HEAD",
          "BISECT_LOG",
          "rebase-merge",
          "rebase-apply",
        ]) {
          if (await gitPathExists(marker)) {
            await say("warn", `operacao git a meio (${marker}); nao commito.`);
            return;
          }
        }

        // Nao usamos .trim() aqui. O codigo porcelain de duas colunas comeca com
        // um espaco, e trim() remove-o da primeira linha - o codigo dessa
        // linha passa a "M " em vez de " M" e o nome fica "ase.md" em vez
        // de "base.md". Apanhei isso com o teste de docs/teste-plugin.mjs.
        const status = (await out("status", "--porcelain")).replace(/\s+$/, "");
        if (!status) return;

        const changed = [];
        const untracked = [];
        for (const l of status.split("\n")) {
          const code = l.slice(0, 2);
          const name = l.slice(3);
          if (code === "??") untracked.push(name);
          else changed.push(name);
        }

        // So tracked. Um ficheiro novo entra a mao, com uma decisao associada.
        if (changed.length) {
          await git("add", "-u");
          await guard(changed);
        }

        const listed = [...changed, ...untracked].slice(0, 10)
          .map((f) => `- ${f}`).join("\n");
        const more = changed.length + untracked.length > 10
          ? `\n- ... (+${changed.length + untracked.length - 10})`
          : "";

        const untouched = untracked.length
          ? `\n\nNAO versionados, adicionados a mao: ${untracked.length}\n` +
            untracked.slice(0, 5).map((f) => `  ? ${f}`).join("\n")
          : "";

        const msg = `wip(auto): ${changed.length} file(s) changed\n\n${listed}${more}${untouched}`;
        const c = await git("commit", "-m", msg);
        if (c.exitCode !== 0) return;

        if (process.env.OPENCODE_AUTOPUSH !== "0") {
          const remote = await git("remote", "get-url", "origin");
          if (remote.exitCode === 0) {
            const p = await git("push");
            if (p.exitCode !== 0) {
              await say("warn", "push falhou; o commit ficou local.");
            }
          }
        }
      } finally {
        running = false;
      }

      async function guard(changed) {
        const g = await loadGuard();
        if (!g) {
          await say("warn", ".opencode/lib/guard.js ausente; commit automatico sem guarda.");
          return;
        }
        try {
          const raw = (await out("diff", "--cached", "-U0", "--no-color"));
          const { deny, allow } = readLocal();
          const hits = [...g.checkPaths(changed, allow), ...g.scanDiff(raw, deny)];
          if (hits.length) {
            await git("reset", "-q");
            await say("error", `commit automatico BLOQUEADO (repo publico).\n${g.formatHits(hits)}`);
            throw new Error("guard");
          }
        } catch (e) {
          if (e.message === "guard") return;
          await say("warn", `guard nao pode correr: ${e.message}`);
          await git("reset", "-q");
          throw e;
        }
      }

      // Sync e propositado: isto corre dentro de um handler de evento e nao vale
      // um await por um ficheiro de duas linhas. O BOM e removido porque o
      // PowerShell 5.1 escreve um com -Encoding utf8 e o JSON.parse lanca -
      // o que deixava a guarda instalada e a nao fazer nada, em silencio.
      function readLocal() {
        try {
          const f = join(directory, ".opencode", "guard.local.json");
          if (!existsSync(f)) return { deny: [], allow: [] };
          const raw = readFileSync(f, "utf8").replace(/^\uFEFF/, "");
          const j = JSON.parse(raw);
          const clean = (v) =>
            Array.isArray(v) ? v.filter((s) => typeof s === "string" && s.length > 2) : [];
          return { deny: clean(j.deny), allow: clean(j.allow) };
        } catch (e) {
          say("warn", `.opencode/guard.local.json ilegivel (${e.message}); a guarda usa so as regras fixas.`);
          return { deny: [], allow: [] };
        }
      }
    },
  };
};