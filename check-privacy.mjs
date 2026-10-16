// Grep final do repo publico. Distingue o placeholder <user> de um caminho
// real, coisa que o -SimpleMatch do PowerShell nao faz - por isso nao passou
// de "C:\Users\" e acusou o placeholder como se fosse o teu nome.
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const files = execFileSync("git", ["ls-files"], { encoding: "utf8" })
  .split("\n").filter(Boolean);

const CHECKS = [
  ["hostname do servidor", /strefizstreamsrv/i],
  ["caminho da sandbox", /\/home\/claude\b/],
  ["hostname de teste", /casa\.ddns\.net/i],
  ["caminho Windows real", /[A-Za-z]:\\+Users\\+(?!<)[^\\\s"'<>]+/],
  ["IP publica", /\b(?!(?:10|127|0)\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|255\.)(?:\d{1,3}\.){3}\d{1,3}\b(?!\.\d)/],
  ["chave privada", /-----BEGIN [A-Z ]*PRIVATE KEY-----/],
  ["token GitHub", /\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,}/],
];

let bad = 0;
for (const [name, re] of CHECKS) {
  const hits = [];
  for (const f of files) {
    let text;
    try {
      text = readFileSync(f, "utf8");
    } catch {
      continue;
    }
    text.split(/\r?\n/).forEach((line, i) => {
      // Um numero de versao com 4 componentes parece um IP; e o mesmo falso
      // positivo que a guarda trata. A lista tem de ser a mesma da guarda,
      // senao este checker testa regras diferentes e daitrogencias.
      const subject = name === "IP publica"
        ? line.replace(/\b(?:version|versao|versão|v|watermedia|ffmpeg|jcef|cef|chromium|mcef[-\w]*|loader|api|fabric|gradle|java|minecraft)\b[\s:=@*`-]{0,4}v?\d+(?:\.\d+){2,3}/gi, " ")
        : line;
      if (re.test(subject)) hits.push(`${f}:${i + 1}`);
    });
  }
  if (hits.length) {
    bad++;
    console.log(`FALHOU  ${name.padEnd(20)} ${hits.length} hit(s)`);
    hits.slice(0, 6).forEach((h) => console.log(`          ${h}`));
  } else {
    console.log(`PASS    ${name.padEnd(20)} limpo`);
  }
}

console.log(`\n${files.length} ficheiros verificados, ${bad} categoria(s) com achados`);
process.exit(bad ? 1 : 0);