---
name: auto-commit
description: Como e quando fazer commits (e push) durante o port. Usar sempre que terminares uma mudança lógica, antes de mudar de tarefa, ou antes de uma operação arriscada.
---
# Auto commit

## Quando
- Depois de cada **mudança lógica** concluída (um ficheiro migrado, um fix, um passo do progresso).
- **Antes** de operações arriscadas (rename em massa, mixins, upgrade de dependências, trazer ficheiros de outra branch).
- No fim da sessão, com `progresso.md` já atualizado.

## Como
1. `git status` e `git diff --stat` — vê o que vais commitar.
2. **Repo público:** confirma que não há segredos, hostnames, IPs, caminhos locais, logs nem ficheiros gerados (`build/`, `run/`, `.gradle/`). O hook `.githooks/pre-commit` e o plugin correm a guarda (`repo-hygiene-privacy`).
3. Se compila: commit normal. Se não compila e queres guardar trabalho: prefixo `wip:`.
4. `git add` só do que pertence a esta mudança (evita `git add -A` cego).
5. Commit e, se houver remote, `git push`.

## Mensagens (Conventional Commits, em inglês)
- `feat:` funcionalidade · `fix:` correção · `refactor:` migração sem mudar comportamento
- `build:` gradle/loom/deps · `docs:` progresso/README · `chore:` resto · `wip:` incompleto
- Primeira linha ≤ 72 caracteres, imperativo: `fix: inject page agent in every frame`.
- Corpo opcional: o **porquê**. Fixes de causa duvidosa: diz `[V]/[I]/[H]` e que corrida falta.

## Nunca
- `git push --force` (a não ser que o utilizador peça explicitamente).
- Commitar `.env`, tokens, chaves, jars/builds, `run/`, logs.
- Reescrever histórico já enviado sem OK explícito.
- `git merge` entre `port/26.3`, `main` e `spike/browser-input`.

## Plugin `.opencode/plugins/auto-commit.js`
Faz commits `wip(auto):` quando a sessão fica parada. Agora:
- **Não commita** com merge/rebase/cherry-pick/revert a meio.
- Faz stage, corre a guarda e, se houver achado, **desfaz o stage, não commita e não faz push** (mensagem no log do opencode).
- `OPENCODE_AUTOCOMMIT=0` desliga tudo (cirurgias delicadas); `OPENCODE_AUTOPUSH=0` só o push.
- Não substitui commits com mensagens boas: faz os teus primeiro.
