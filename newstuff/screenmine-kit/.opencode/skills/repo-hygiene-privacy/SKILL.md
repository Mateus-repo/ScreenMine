---
name: repo-hygiene-privacy
description: O repo é público e o auto-commit faz push. Evitar que hostnames, IPs, tokens, nomes de utilizador do SO e logs em bruto entrem no git. Usar antes de qualquer commit/push e sempre que colares logs ou configs em ficheiros.
---
# Higiene e privacidade (repo público)

## Porque existe
Um hostname `ddns` do utilizador esteve em **três commits** num repo público, e um log de Jellyfin com IP, utilizador e **token de sessão** foi partilhado. Remover do ficheiro atual **não desexpõe** o que já está no histórico.

## O que nunca entra num ficheiro versionado
- Hostnames/IPs públicos, DDNS, URLs de servidores pessoais.
- Tokens, `api_key=`, `X-Emby-Token`, cookies, chaves privadas, `.env`.
- Caminhos absolutos com o nome de utilizador (`C:\Users\<nome>\...`, `/home/<nome>/`).
- Logs em bruto (`run/`, `*.log`, `hs_err_pid*`, `crash-reports/`), `.jar`, dumps.
- Excertos de `latest.log` sem redigir (têm utilizador, caminhos, às vezes IPs).

## Placeholders
`<my-server>` · `<user>` · `<token>` · `<public-ip>`. Caminhos relativos (`../CoScreen-main`) em vez de absolutos.
Valores reais e privados vão em ficheiros **ignorados pelo git**: `AGENTS.local.md`, `.opencode/guard.local.json`, `run/`.

## Guarda automática
- `.opencode/lib/guard.js` deteta: caminhos locais, DDNS, tokens/Bearer/chaves, IPv4 público, ficheiros proibidos (`run/`, `*.log`, `*.jar`, `.env`, …) e **qualquer termo** de `.opencode/guard.local.json`:
  ```json
  { "deny": ["o-meu-servidor", "NomeDeUtilizadorDoWindows"] }
  ```
- Ligada em dois sítios: o plugin `auto-commit.js` (desfaz o stage e não commita nem faz push se houver achado) e o hook `.githooks/pre-commit` (`git config core.hooksPath .githooks`, uma vez por clone).
- Falso positivo: corrige o texto (placeholder). Só como último recurso `git commit --no-verify`, e explica no commit.

## Antes de commit/push (30 segundos)
1. `git diff --cached` — procura os termos acima à vista.
2. `git status` — nada de `run/`, `*.log`, jars.
3. Logs colados em `progresso.md`/`codecs.md`: redigidos e **mínimos** (só as linhas que provam).

## Se algo vazou
1. **Pára e diz ao utilizador**, com o commit e o ficheiro. Não escondas.
2. Remove do estado atual e substitui por placeholder (reversível).
3. **Rodar a credencial é o que protege** (logout/login do Jellyfin, novo token). Hostnames/IPs expostos não se recuperam: avisa que já foram vistos.
4. **Reescrever o histórico (`git filter-repo`) só com OK explícito**: é destrutivo, muda hashes e exige force-push; as tags de segurança `backup/*` e quaisquer clones/forks continuam a ter o conteúdo.
5. Regista no `progresso.md` ("Segurança e privacidade"), sem repetir o valor.
