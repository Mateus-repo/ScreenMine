// Valida o HTML da pagina de teste extraido do .class compilado: o <script>
// tem de pasar o node --check, e o data: URL tem de ser bem formado.
//
// Porque isto e' necessario: um erro de sintaxe no script aparece em jogo
// como "a pagina nao carregou", que e' exactamente o mesmo sintoma de o
// browser estar partido. Sem esta verificacao nao se distingue.
import { execFileSync } from "node:child_process";
import { writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const javap = "C:\\Program Files\\Java\\jdk-25\\bin\\javap.exe";
const dump = execFileSync(
  javap,
  ["-p", "-v", "-cp", "build/classes/java/main", "dev.screenmine.browser.TestPage"],
  { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
);

// As constantes de text block aparecem no constant pool. Procuramos a que
// comeca por <!doctype html> e acaba em </html>.
const lines = dump.split(/\r?\n/).map((l) => l.trim());
const html = lines
  .filter((l) => /^#\d+ = Utf8\s/.test(l))
  .map((l) => l.slice(l.indexOf("= Utf8") + 6).trim())
  .filter((l) => l.includes("<!doctype html>"))
  .pop();

if (!html) {
  console.error("HTML da pagina de teste nao encontrado no .class");
  process.exit(1);
}

// O javap escapa o dump: uma newline real sai como \n, uma tab como \t, uma
// aspa como \' e uma barra como \\. Processamos de uma vez, da esquerda para a
// direita, porque replaces encadeados partem mal o caso "\\n" - que e uma barra
// literal seguida de n, e nao uma newline.
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

const page = unescapeJavap(html);

const checks = [];
const has = (name, cond) => checks.push([name, !!cond]);

has("comeca com doctype", page.startsWith("<!doctype html>"));
has("fecha o html", page.trimEnd().endsWith("</html>"));
has("tem input", page.includes('id="in"'));
has("tem textarea", page.includes('id="ta"'));
has("tem select (Bug D)", page.includes('id="sel"'));
has("truque do select", page.includes("sel.size="));
has("loga keydown", page.includes("KEYDOWN"));
has("loga keypress", page.includes("KEYPRESS"));
has("mostra volume do video", page.includes("v.volume"));
has("mostra error do video", page.includes("v.error?v.error.code"));

const script = page.slice(page.indexOf("<script>") + 8, page.lastIndexOf("</script>"));
has("script extraido", script.length > 100);

const dir = mkdtempSync(join(tmpdir(), "sm-testpage-"));
const f = join(dir, "page.js");
writeFileSync(f, script, "utf8");

let syntaxOk = true;
let detail = "";
try {
  execFileSync(process.execPath, ["--check", f]);
} catch (e) {
  syntaxOk = false;
  detail = String(e.stderr).split("\n").slice(0, 3).join(" ");
}
checks.push(["script passa node --check", syntaxOk]);

let pass = 0;
for (const [name, ok] of checks) {
  console.log(`${ok ? "PASS" : "FALHOU"}  ${name}`);
  if (ok) pass++;
}
console.log(`\npagina: ${page.length} chars, script: ${script.length} chars`);
if (!syntaxOk) console.log(detail);
process.exit(pass === checks.length ? 0 : 1);