// Guarda de privacidade: procura segredos, hostnames privados, caminhos locais
// e ficheiros que nunca devem ir para um repo publico.
//
// Usado pelo hook .githooks/pre-commit. O hook so BLOQUEIA e explica; nao
// desfaz nada, nao toca na staging e nao perde trabalho.
//
// Padroes privados (o teu hostname, o teu username) e ficheiros que tens
// legitimo de versionar vao em .opencode/guard.local.json, que esta no
// .gitignore:
//   {
//     "deny":  ["o-meu-servidor", "UsernameDoWindows"],
//     "allow": ["mcef-modern-0.3.3.jar"]
//   }
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

// Caminhos que nunca devem ser versionados, mesmo que o .gitignore falhe.
const BLOCKED_PATHS = [
  /(^|\/)run\//,
  /(^|\/)crash-reports\//,
  /(^|\/)hs_err_pid[^/]*$/,
  /(^|\/)\.env(\.|$)/,
  /\.(log|hprof|pem|key|p12|pfx)$/i,
  /(^|\/)guard\.local\.json$/,
  /(^|\/)AGENTS\.local\.md$/,
];

// .jar fica de fora de proposito. Este repo nao versiona nenhum hoje, mas
// bloquear a regra inteira - sem forma de contornar - significaria que o
// primeiro uso legitimo bloqueia o commit e ninguem sabe porque. Quem quiser
// nao ter jar nenhum versionado acrescenta-o aqui e e so isso.
const JAR_ALLOWED = true;

// Linhas adicionadas que nao devem ser commitadas.
const PATTERNS = [
  { name: "caminho local Windows", re: /\b[A-Za-z]:\\+Users\\+(?!<)[^\\\s"'`]+/ },
  { name: "caminho local Unix", re: /\/(?:home|Users)\/(?!<)[A-Za-z0-9._-]+\// },
  { name: "hostname de DNS dinamico", re: /\b[a-z0-9-]+\.(?:ddns\.net|duckdns\.org|no-ip\.(?:org|com|biz)|hopto\.org|zapto\.org|myftp\.org|serveo\.net)\b/i },
  { name: "chave privada", re: /-----BEGIN [A-Z ]*PRIVATE KEY-----/ },
  { name: "token Bearer", re: /\bBearer\s+[A-Za-z0-9._~+/-]{20,}/ },
  { name: "token em query/header", re: /(?:api_key|apikey|access_token|X-Emby-Token|X-MediaBrowser-Token)\s*[=:]\s*["']?[A-Za-z0-9._~+/-]{16,}/i },
  { name: "segredo atribuido", re: /\b(?:secret|password|passwd|token)\s*[=:]\s*["'][^"'\s<]{8,}["']/i },
  { name: "GitHub token", re: /\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,}/ },
  { name: "IPv4 publico", re: /\b(?!(?:10|127|0)\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|169\.254\.|255\.)(?:\d{1,3}\.){3}\d{1,3}\b(?!\.\d)/ },
];

// Falsos positivos conhecidos (versoes, exemplos de documentacao).
const ALLOW_LINE = [/\b0\.0\.0\.0\b/, /\b1\.[0-9]+\.[0-9]+\.[0-9]+\b.*(?:jcef|cef|chromium)/i, /chromium-\d+\.\d+\.\d+\.\d+/i, /cef-\d+\.\d+\.\d+/i, /\b(?:203\.0\.113|198\.51\.100|192\.0\.2)\.\d+\b/];

// Numeros de versao com 4 componentes (ex.: "WaterMedia 3.0.0.24") parecem IPv4.
// Remove-os da linha antes de procurar IPs. Uma linha com o marcador
// `guard:ignore` e ignorada por inteiro (usar com parcimonia).
const VERSION_CONTEXT = /\b(?:version|versao|versão|v|watermedia|ffmpeg|jcef|cef|chromium|mcef[-\w]*|loader|api|fabric|gradle|java|minecraft)\b[\s:=@*`-]{0,4}v?\d+(?:\.\d+){2,3}/gi;

function readLocal(dir) {
  try {
    const f = join(dir, ".opencode", "guard.local.json");
    if (!existsSync(f)) return { deny: [], allow: [] };
    // Tira o BOM: o PowerShell 5.1 escreve um com -Encoding utf8 e o
    // JSON.parse lanca sem isso, levando o catch a devolver listas vazias.
    const raw = readFileSync(f, "utf8").replace(/^\uFEFF/, "");
    const j = JSON.parse(raw);
    const clean = (v) =>
      Array.isArray(v) ? v.filter((s) => typeof s === "string" && s.length > 2) : [];
    return { deny: clean(j.deny), allow: clean(j.allow) };
  } catch {
    return { deny: [], allow: [] };
  }
}

export function loadLocalDeny(dir) {
  return readLocal(dir).deny;
}

export function checkPaths(files, allow = []) {
  const hits = [];
  for (const f of files) {
    const p = f.replace(/\\/g, "/");
    // O allowlist e o unico sitio onde um ficheiro proibido passa, e compara
    // pelo nome do ficheiro, para "docs/a.jar" nao liberar "secrets/a.jar".
    const base = p.slice(p.lastIndexOf("/") + 1);
    if (allow.some((a) => a === p || a === base)) continue;
    if (JAR_ALLOWED && /\.jar$/i.test(p)) continue;
    if (BLOCKED_PATHS.some((re) => re.test(p))) {
      hits.push({ file: p, rule: "ficheiro que nao deve ser versionado" });
    }
  }
  return hits;
}

// diffText = saida de `git diff --cached -U0`
export function scanDiff(diffText, localDeny = []) {
  const hits = [];
  let file = "?";
  let lineNo = 0;
  for (const raw of diffText.split("\n")) {
    if (raw.startsWith("+++ b/")) { file = raw.slice(6); continue; }
    if (raw.startsWith("+++ ")) continue;
    const m = raw.match(/^@@ -\d+(?:,\d+)? \+(\d+)/);
    if (m) { lineNo = Number(m[1]) - 1; continue; }
    if (!raw.startsWith("+")) continue;
    lineNo++;
    const line = raw.slice(1);
    if (line.includes("guard:ignore")) continue;
    for (const d of localDeny) {
      if (line.toLowerCase().includes(d.toLowerCase())) {
        hits.push({ file, line: lineNo, rule: "padrao privado (guard.local.json)" });
      }
    }
    if (ALLOW_LINE.some((re) => re.test(line))) continue;
    const noVersions = line.replace(VERSION_CONTEXT, " ");
    for (const p of PATTERNS) {
      const subject = p.name === "IPv4 publico" ? noVersions : line;
      if (p.re.test(subject)) hits.push({ file, line: lineNo, rule: p.name });
    }
  }
  return hits;
}

export function formatHits(hits) {
  return hits.map((h) => `  - ${h.file}${h.line ? ":" + h.line : ""}  ${h.rule}`).join("\n");
}