// Guarda de privacidade: procura segredos, hostnames privados, caminhos locais
// e ficheiros que nunca devem ir para um repo publico.
// Usado pelo plugin auto-commit e pelo hook .githooks/pre-commit.
// Padroes privados (o teu hostname, o teu username) vao em
// .opencode/guard.local.json, que esta no .gitignore:
//   { "deny": ["o-meu-servidor", "UsernameDoWindows"] }
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

// Caminhos que nunca devem ser versionados, mesmo que o .gitignore falhe.
const BLOCKED_PATHS = [
  /(^|\/)run\//,
  /(^|\/)crash-reports\//,
  /(^|\/)hs_err_pid[^/]*$/,
  /(^|\/)\.env(\.|$)/,
  /\.(log|hprof|jar|pem|key|p12|pfx)$/i,
  /(^|\/)guard\.local\.json$/,
  /(^|\/)AGENTS\.local\.md$/,
];

// Linhas adicionadas que nao devem ser commitadas.
const PATTERNS = [
  { name: "caminho local Windows", re: /\b[A-Za-z]:\\+Users\\+(?!<)[^\\\s"'`]+/ },
  { name: "caminho local Unix", re: /\/(?:home|Users)\/(?!<)[A-Za-z0-9._-]+\// },
  { name: "hostname de DNS dinamico", re: /\b[a-z0-9-]+\.(?:ddns\.net|duckdns\.org|no-ip\.(?:org|com|biz)|hopto\.org|zapto\.org|myftp\.org|serveo\.net)\b/i },
  { name: "chave privada", re: /-----BEGIN [A-Z ]*PRIVATE KEY-----/ },
  { name: "token Bearer", re: /\bBearer\s+[A-Za-z0-9._~+\/-]{20,}/ },
  { name: "token em query/header", re: /(?:api_key|apikey|access_token|X-Emby-Token|X-MediaBrowser-Token)\s*[=:]\s*["']?[A-Za-z0-9._~+\/-]{16,}/i },
  { name: "segredo atribuido", re: /\b(?:secret|password|passwd|token)\s*[=:]\s*["'][^"'\s<]{8,}["']/i },
  { name: "GitHub token", re: /\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,}/ },
  { name: "IPv4 publico", re: /\b(?!(?:10|127|0)\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.|169\.254\.|255\.)(?:\d{1,3}\.){3}\d{1,3}\b(?!\.\d)/ },
];

// Falsos positivos conhecidos (versoes, exemplos de documentacao).
const ALLOW_LINE = [/\b0\.0\.0\.0\b/, /\b1\.[0-9]+\.[0-9]+\.[0-9]+\b.*(?:jcef|cef|chromium)/i, /chromium-\d+\.\d+\.\d+\.\d+/i, /cef-\d+\.\d+\.\d+/i, /\b(?:203\.0\.113|198\.51\.100|192\.0\.2)\.\d+\b/];

export function loadLocalDeny(dir) {
  try {
    const f = join(dir, ".opencode", "guard.local.json");
    if (!existsSync(f)) return [];
    const j = JSON.parse(readFileSync(f, "utf8"));
    return Array.isArray(j.deny) ? j.deny.filter((s) => typeof s === "string" && s.length > 2) : [];
  } catch {
    return [];
  }
}

export function checkPaths(files) {
  const hits = [];
  for (const f of files) {
    const p = f.replace(/\\/g, "/");
    if (BLOCKED_PATHS.some((re) => re.test(p))) hits.push({ file: p, rule: "ficheiro que nao deve ser versionado" });
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
    for (const d of localDeny) {
      if (line.toLowerCase().includes(d.toLowerCase())) hits.push({ file, line: lineNo, rule: "padrao privado (guard.local.json)" });
    }
    if (ALLOW_LINE.some((re) => re.test(line))) continue;
    for (const p of PATTERNS) {
      if (p.re.test(line)) hits.push({ file, line: lineNo, rule: p.name });
    }
  }
  return hits;
}

export function formatHits(hits) {
  return hits.map((h) => `  - ${h.file}${h.line ? ":" + h.line : ""}  ${h.rule}`).join("\n");
}
