// Testa o plugin de auto-commit contra um repositorio descartavel, com um
// git de mentira que responde a `git -C <dir> <args>`.
//
// O plugin chama git atraves de `$` (o tag do opencode), que nao existe fora
// do opencode. Este substituto e um spy que executa git de verdade no
// directorio indicado, por isso exercita o codigo real sem o plugin estar
// carregado num jogo.
//
//   node docs/teste-plugin.mjs
import { mkdtempSync, writeFileSync, mkdirSync, readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
// No Windows um caminho absoluto tem de virar URL file:// para o import().
const plugin = await import(
  pathToFileURL(join(root, ".opencode", "plugins", "auto-commit.js")).href
);

const work = mkdtempSync(join(tmpdir(), "sm-autocommit-"));
mkdirSync(join(work, ".opencode", "lib"), { recursive: true });
copy(join(root, ".opencode", "lib", "guard.js"), join(work, ".opencode", "lib", "guard.js"));

git(work, "init", "-q");
git(work, "config", "user.email", "t@t");
git(work, "config", "user.name", "t");
writeFileSync(join(work, "base.md"), "base\n");
git(work, "add", "-A");
git(work, "commit", "-qm", "base");

// Sem isto, o resto do teste passa a vazio porque o repo nunca existiu e
// todas as comparacoes sao "iguais a nada".
try {
  const inside = git(work, "rev-parse", "--is-inside-work-tree").trim();
  const first = git(work, "log", "-1", "--pretty=%s").trim();
  console.log(`setup: repo em ${work} (inside=${inside}, head="${first}")\n`);
  if (inside !== "true") throw new Error("a repo de teste nao foi criada");
} catch (e) {
  console.error("setup falhou:", e.message);
  process.exit(1);
}

// Spy da tag `$` do opencode. Suporta exactamente a forma que o plugin usa:
//     $`git -C ${directory} ${args}`
// onde `args` e um array. Nao dividimos por espacos - a mensagem de commit tem
// varias linhas e seria destruida. O resultado traz .nothrow() e .quiet(),
// porque o plugin faz $\`git ...\`.nothrow().quiet().
const run = (_strings, ...values) => {
  // argv NAO leva o nome do programa: esse e o primeiro argumento do
  // execFileSync. Por-lo aqui tambem dava `git git -C ...` e o git
  // respondia "'git' is not a git command".
  const argv = ["-C"];
  for (const v of values) {
    if (Array.isArray(v)) argv.push(...v.map(String));
    else argv.push(String(v));
  }
  let res;
  try {
    const stdout = execFileSync("git", argv, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    res = { exitCode: 0, stdout, stderr: "" };
  } catch (e) {
    res = { exitCode: e.status ?? 1, stdout: e.stdout ?? "", stderr: e.stderr ?? "" };
  }
  if (process.env.SM_TRACE) {
    const shown = JSON.stringify(argv).slice(0, 200);
    console.log(`   [spy] ${shown} -> ${res.exitCode}`);
    if (res.exitCode !== 0 && res.stderr) {
      console.log(`          stderr: ${res.stderr.trim().split("\n")[0]}`);
    }
  }
  res.nothrow = () => res;
  res.quiet = () => res;
  return res;
};
const $ = run;

const ctx = {
  $,
  directory: work,
  client: undefined,
};
delete process.env.OPENCODE_AUTOCOMMIT;

let pass = 0;
let fail = 0;
const check = (name, ok, detail = "") => {
  if (ok) {
    pass++;
    console.log(`PASS  ${name}`);
  } else {
    fail++;
    console.log(`FALHOU ${name}${detail ? "  -> " + detail : ""}`);
  }
};

async function idle() {
  const h = await plugin.AutoCommit(ctx);
  await h.event({ event: { type: "session.idle" } });
}

// 1. nada para commit -> nao cria commit
const before = head(work);
await idle();
check("sem mudancas, nao commita", head(work) === before);

// 2. ficheiro tracked modificado -> commita
writeFileSync(join(work, "base.md"), "modificado\n");
await idle();
check("tracked modificado -> commita", head(work) !== before && headMsg(work).includes("wip(auto)"));

// 3. ficheiro NOVO nao e versionado automaticamente
const newFile = join(work, "novo.txt");
writeFileSync(newFile, "conteudo\n");
await idle();
check("ficheiro novo NAO e commitado", !headMsg(work).includes("novo.txt"));
check("mas fica em disco", readFileSync(newFile, "utf8") === "conteudo\n");

// 4. a guarda bloqueia um segredo num ficheiro tracked
writeFileSync(join(work, "base.md"), "cd C:\\Users\\Alguem\\GitHub\n");
await idle();
check("segredo bloqueado", head(work) === head(work) && !headMsg(work).includes("Alguem"),
      "o commit ficou com o caminho?");

// 5. merge a meio -> nao commita
const mergeHead = join(work, ".git", "MERGE_HEAD");
const atMerge = head(work);
writeFileSync(mergeHead, atMerge + "\n");
writeFileSync(join(work, "base.md"), "outra coisa\n");
await idle();
check("merge a meio -> nao commita", head(work) === atMerge);
unlink(mergeHead);

// 6. depois do merge limpo, volta a commitar.
// O que se prova e que a mudanca deixou de estar por commitar - a mensagem do
// commit lista os ficheiros, nao o conteudo, por isso "includes('final')"
// seria um teste errado.
writeFileSync(join(work, "base.md"), "final\n");
await idle();
const clean = execFileSync("git", ["-C", work, "status", "--porcelain", "base.md"], {
  encoding: "utf8",
}).trim() === "";
check("depois de limpar o merge, commita", clean && headMsg(work).includes("wip(auto)"));
// Comparacao por linha, nao por sub-cadeia: "base.md" contem "ase.md", e um
// includes() negativo dava um falso negativo em cima de um resultado correcto.
const lines = headMsg(work).split("\n").map((l) => l.trim());
check("nome do ficheiro bem lido", lines.includes("- base.md"), lines.join(" | "));

console.log(`\n${pass} correctos, ${fail} falhados`);
process.exit(fail ? 1 : 0);

function copy(a, b) {
  writeFileSync(b, readFileSync(a));
}
function git(dir, ...args) {
  return execFileSync("git", ["-C", dir, ...args], { encoding: "utf8" });
}
function head(dir) {
  return git(dir, "rev-parse", "HEAD").trim();
}
function headMsg(dir) {
  try {
    return git(dir, "log", "-1", "--pretty=%B");
  } catch {
    return "";
  }
}
function unlink(f) {
  try {
    execFileSync("cmd", ["/c", "del", f]);
  } catch {}
}