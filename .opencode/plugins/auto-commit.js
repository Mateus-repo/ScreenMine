// Auto commit (e push) quando a sessão do opencode fica idle.
// Rede de segurança: os commits "bons" são feitos pelo agente (skill auto-commit).
export const AutoCommit = async ({ $, directory }) => {
  let running = false;

  const git = async (...args) =>
    await $`git -C ${directory} ${args}`.nothrow().quiet();

  return {
    event: async ({ event }) => {
      if (event.type !== "session.idle") return;
      if (process.env.OPENCODE_AUTOCOMMIT === "0") return;
      if (running) return;
      running = true;
      try {
        const inside = await git("rev-parse", "--is-inside-work-tree");
        if (inside.exitCode !== 0) return;

        const status = (await git("status", "--porcelain")).stdout.toString().trim();
        if (!status) return;

        const files = status.split("\n").map((l) => l.slice(3));
        const shown = files.slice(0, 10).map((f) => `- ${f}`).join("\n");
        const more = files.length > 10 ? `\n- ... (+${files.length - 10})` : "";
        const msg = `wip(auto): ${files.length} file(s) changed\n\n${shown}${more}`;

        await git("add", "-A");
        const c = await git("commit", "-m", msg);
        if (c.exitCode !== 0) return;

        if (process.env.OPENCODE_AUTOPUSH !== "0") {
          const remote = await git("remote", "get-url", "origin");
          if (remote.exitCode === 0) await git("push");
        }
      } finally {
        running = false;
      }
    },
  };
};
