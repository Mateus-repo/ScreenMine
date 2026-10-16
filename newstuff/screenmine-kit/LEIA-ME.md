# Kit de melhorias — ScreenMine (branch `spike/codecs`)

Extrai por cima da raiz do repo (sobrepõe ficheiros com o mesmo caminho).

## O que traz
- `AGENTS.md` — reescrito: ambiente Windows/PowerShell, "o agente não corre o jogo",
  regras novas (etiquetas de evidência, privacidade, threads, nunca `git merge`
  entre branches), tabela "qual skill usar", DoD mais exigente. Sem caminho com
  o teu nome de utilizador.
- 9 skills novas: `evidence-labels`, `playtest-handoff`, `codec-probe`,
  `jcef-keyboard-input`, `page-agent-channel`, `thread-safety-jcef`,
  `repo-hygiene-privacy`, `security-hardening`, `spike-branch-lifecycle`.
- 5 skills atualizadas: `code-review`, `input-and-lwjgl`, `build-and-test`,
  `progress-tracking`, `auto-commit`.
- Plugin `auto-commit.js` melhorado + `.opencode/lib/guard.js`: não commita com
  merge/rebase a meio e **bloqueia commit/push** se encontrar segredo, DDNS,
  IP público, caminho `C:\Users\<nome>`, ficheiros `run/`/`*.log`/jar.
- Hook `.githooks/pre-commit` com a mesma guarda (para commits manuais).
- `.gitignore` completo (acrescenta `AGENTS.local.md`, `guard.local.json`, `crash-reports/`, `*.hprof`).

## Passos depois de extrair
1. `git config core.hooksPath .githooks`
2. Cria `.opencode/guard.local.json` (fica ignorado pelo git) com os teus termos privados:
   `{ "deny": ["o-teu-hostname", "NomeDeUtilizadorDoWindows"] }`
3. Se a pasta da referência não for `..\CoScreen-main`, põe o caminho em `AGENTS.local.md`.
4. Corrige no `progresso.md` (ver skill `spike-branch-lifecycle`):
   - linha `cd C:\Users\<nome>\...` (caminho com o teu utilizador, repo público);
   - "licença é MIT" → **AGPL-3.0-only**.
5. Necessita de Node (o opencode já traz runtime; o hook usa `node`).

## Não incluído de propósito
Nenhum código Java: não consigo compilar contra a 26.3 aqui, e não entrego
código por compilar. A extensão da sonda de codecs (item 8) está descrita, com o
JS pronto, em `.opencode/skills/codec-probe/SKILL.md`.
