---
name: code-review
description: Auto-revisão rápida antes de cada commit relevante: corretude, threads, privacidade, restos de debug e consistência. Usar antes de commit/push de mudanças significativas.
---
# Auto-revisão

Antes de commitar, lê o `git diff` e verifica:

## Corretude
- [ ] Faz o que deveria? Casos limite (null, vazio, mundo a carregar/descarregar, browser ainda a arrancar)?
- [ ] Recursos libertados (texturas, buffers, threads, listeners, `LIVE`)? Todo o `create` tem o seu `close`.
- [ ] Sem chamadas de render/GL fora da render thread; callbacks do CEF só enfileiram trabalho (`thread-safety-jcef`).
- [ ] Estado partilhado entre threads é `volatile`/fila/lock, e o comentário diz qual thread escreve e qual lê.
- [ ] `try/catch` à volta do que pode falhar em loop por frame (`createBrowser`, `Lang.t`).

## Port
- [ ] Nenhum rename automático ambíguo ficou por rever (`TODO(port)` listados)
- [ ] Nomes de API confirmados (`api-verification`); nada adivinhado, sobretudo `CefFrame`/`CefBrowser`
- [ ] Nada hardcoded de versão que devia estar em `gradle.properties`

## Afirmações
- [ ] Comentários, commits e `progresso.md` com `[V]/[I]/[H]` onde afirmam causas (`evidence-labels`)
- [ ] Nenhum "resolvido"/"funciona" sem log de corrida

## Privacidade (repo público)
- [ ] Sem hostnames, IPs, tokens, cookies, caminhos locais (`C:\Users\<user>\...`), logs em bruto (`repo-hygiene-privacy`)
- [ ] A guarda passou (hook/plugin) e `git status` sem `run/`, `*.log`, jars
- [ ] Logs colados em docs: redigidos e mínimos

## Higiene
- [ ] Sem `System.out.println`/prints de debug esquecidos; logs ruidosos (`GAIN`) só em debug
- [ ] Sem código comentado em bloco; javadoc que descreve o que o código faz *agora*
- [ ] Sem ficheiros gerados no diff

## Mensagem
- [ ] Commit descreve o **porquê**; uma mudança lógica por commit

Se encontrares problemas, corrige primeiro e só depois commita.
