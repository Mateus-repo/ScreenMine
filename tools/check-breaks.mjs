// Verifica que as duas versoes do mod nao podem carregar no mesmo perfil.
//
// Porquê um check e não confiança: um 'breaks' mal escrito - uma letra trocada
// no id, uma vírgula a menos - não dá erro de compilação. O jar fica seemingly
// bom e a protecção só aparece quando alguém põe os dois no mesmo perfil e o
// Fabric arranca os dois em vez de recusar.
//
// Lê os dois fabric.mod.json directamente do git, em vez dos jars. Assim
// funciona em qualquer das branches e não depende de ter as duas compiladas,
// que nunca acontece: cada branch só produz o seu jar.
//
// Corre: node tools/check-breaks.mjs
import { execFileSync, spawnSync } from "node:child_process";

const LOCAL = "port/26.3";
const MP = "multiplayer";

/** git cat-file -e: 0 existe, diferente de 0 nao existe. */
function exists(spec) {
  return spawnSync("git", ["cat-file", "-e", spec], { stdio: "ignore" }).status === 0;
}

function meta(branch) {
  const raw = execFileSync(
    "git",
    ["show", `${branch}:src/main/resources/fabric.mod.json`],
    { encoding: "utf8" },
  );
  return JSON.parse(raw);
}

function settings(branch) {
  const raw = execFileSync("git", ["show", `${branch}:src/main/java/dev/screenmine/Settings.java`], {
    encoding: "utf8",
  });
  const m = raw.match(/Paths\.get\("config",\s*"([^"]+)"\)/);
  return m ? m[1] : "(nao encontrado)";
}

function gradleName(branch) {
  const raw = execFileSync("git", ["show", `${branch}:gradle.properties`], {
    encoding: "utf8",
  });
  const m = raw.match(/archives_base_name=(.+)/);
  return m ? m[1].trim() : "(nao encontrado)";
}

function srcId(branch) {
  const raw = execFileSync(
    "git",
    ["show", `${branch}:src/main/java/dev/screenmine/ScreenMineClient.java`],
    { encoding: "utf8" },
  );
  const m = raw.match(/MOD_ID\s*=\s*"([^"]+)"/);
  return m ? m[1] : "(nao encontrado)";
}

const local = meta(LOCAL);
const mp = meta(MP);
const localId = local.id;
const mpId = mp.id;

const checks = [];
const has = (name, cond, detail = "") => checks.push([name, !!cond, detail]);

has("os dois jars existem", localId && mpId, `${localId} / ${mpId}`);
has("os ids sao diferentes", localId !== mpId, `${localId} != ${mpId}`);
has(
  `local declara breaks: ${mpId}`,
  local.breaks?.[mpId] !== undefined,
  JSON.stringify(local.breaks),
);
has(
  `multiplayer declara breaks: ${localId}`,
  mp.breaks?.[localId] !== undefined,
  JSON.stringify(mp.breaks),
);
has("MOD_ID no codigo bate com o id do jar (local)", srcId(LOCAL) === localId, srcId(LOCAL));
has("MOD_ID no codigo bate com o id do jar (mp)", srcId(MP) === mpId, srcId(MP));

const lc = settings(LOCAL);
const mc = settings(MP);
has("ficheiros de config diferentes", lc !== mc, `${lc} / ${mc}`);
has("config local tem o nome do mod local", lc.includes(localId), lc);
has("config mp tem o nome do mod mp", mc.includes(mpId), mc);

const lg = gradleName(LOCAL);
const mg = gradleName(MP);
has("nomes de jar diferentes", lg !== mg, `${lg} / ${mg}`);

has("ambos sao client-only", local.environment === "client" && mp.environment === "client");
has(
  "nomes de mixin config diferentes",
  JSON.stringify(local.mixins) !== JSON.stringify(mp.mixins),
  `${local.mixins} / ${mp.mixins}`,
);
has(
  "ficheiros de mixin existem em cada branch",
  exists(`${LOCAL}:src/main/resources/${local.mixins[0]}`) &&
    exists(`${MP}:src/main/resources/${mp.mixins[0]}`),
  `${local.mixins[0]} / ${mp.mixins[0]}`,
);
has(
  "cada branch nao tem o ficheiro de mixin da outra",
  !exists(`${LOCAL}:src/main/resources/${mp.mixins[0]}`) &&
    !exists(`${MP}:src/main/resources/${local.mixins[0]}`),
);

let pass = 0;
for (const [name, ok, detail] of checks) {
  console.log(`${ok ? "PASS" : "FALHOU"}  ${name}${detail ? "   (" + detail + ")" : ""}`);
  if (ok) pass++;
}
console.log(`\n${pass} de ${checks.length} passaram`);
console.log(
  `\nO que isto NAO prova: que o Fabric recusa os dois no arranque. Isso e' o`,
);
console.log(
  `ModSolver, e para o ver tem de ser um arranque real. As Declarações estão`,
);
console.log(
  `corretas e o Fabric Loader le 'breaks' (ModMetadata, ModDependency$Kind,`,
);
console.log(`ModSolver). O arranque tem de ser visto em jogo.`);
process.exit(pass === checks.length ? 0 : 1);