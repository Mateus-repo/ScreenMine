// Extrai a constante AGENT_JS do .class compilado e valida a sintaxe do JS.
// Feito em node porque o escape do javap (\\' e \\) e chato de desfazer em
// PowerShell, e aqui o erro seria silencioso: um JS partido na pagina nao dá
// erro de compilacao Java nenhum.
import { execFileSync } from "node:child_process";
import { writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const clsDir = "build/classes/java/main";
const javap = "C:\\Program Files\\Java\\jdk-25\\bin\\javap.exe";

const dump = execFileSync(
  javap,
  ["-p", "-v", "-cp", clsDir, "dev.screenmine.browser.BrowserInstance"],
  { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
);

// A entrada certa e "#NNN = Utf8   (function(){...". Escolhemos por tres
// condicoes explicitas em vez de uma regex que ja apanhou a linha errada.
const line = dump
  .split(/\r?\n/)
  .map((l) => l.trim())
  .find(
    (l) =>
      /^#\d+ = Utf8\s/.test(l) &&
      l.includes("(function(){if(window.__smAgent)"),
  );

if (!line) {
  console.error("AGENT_JS nao encontrada no constant pool");
  process.exit(1);
}
const at = line.indexOf("= Utf8");
// O javap escapa o dump: newline real sai como \n, tab como \t, aspa como \'
// e barra como \\. Percorremos de uma vez porque replaces encadeados partem
// mal o caso "\\n" - barra literal seguida de n, e nao uma newline.
function unescapeJavap(s) {
  let out = "";
  for (let i = 0; i < s.length; i++) {
    if (s[i] !== "\\") {
      out += s[i];
      continue;
    }
    const c = s[++i];
    if (c === "n") out += "\n";
    else if (c === "t") out += "\t";
    else if (c === "r") out += "\r";
    else if (c === "'") out += "'";
    else if (c === '"') out += '"';
    else if (c === "\\") out += "\\";
    else out += "\\" + c;
  }
  return out;
}
let js = unescapeJavap(line.slice(at + "= Utf8".length).trim());

const dir = mkdtempSync(join(tmpdir(), "sm-agent-"));
const file = join(dir, "agent.js");
writeFileSync(file, js, "utf8");

const checks = [
  ["comeca por IIFE", js.startsWith("(function(){")],
  ["fecha com IIFE", js.trimEnd().endsWith("})();")],
  ["estado vai para o console", js.includes('console.log("SM|"')],
  ["prefixo do agente dobrado", js.includes('console.log("SMA|vol=')],
  ["ainda escreve o titulo", js.includes("document.title=")],
  ["guard do titulo", js.includes("!=='SM|'")],
  ["lastSent separado", js.includes("if(s!==lastSent){")],
  ["shim de edicao", js.includes("k==='backspace'")],
  ["Ctrl atalhos", js.includes("k==='a'") && js.includes("k==='v'") && js.includes("k==='c'")],
  ["reporta href e top", js.includes("|top=") && js.includes("|href=")],
  ["volume aplicado", js.includes("e.volume=window.__smVol")],
];

for (const [name, ok] of checks) {
  console.log(`${ok ? "PASS" : "FALHOU"}  ${name}`);
}

let syntaxOk = true;
try {
  execFileSync(process.execPath, ["--check", file]);
} catch (e) {
  syntaxOk = false;
  console.error("SINTAXE INVALIDA:\n" + e.stderr);
}
console.log(`${syntaxOk ? "PASS" : "FALHOU"}  sintaxe (node --check)`);
console.log(`\nJS: ${js.length} chars`);
process.exit(syntaxOk && checks.every(([, ok]) => ok) ? 0 : 1);