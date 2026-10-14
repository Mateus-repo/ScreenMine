---
name: auto-commit
description: Como e quando fazer commits (e push) durante o port. Usar sempre que terminares uma mudança lógica, antes de mudar de tarefa, ou antes de uma operação arriscada.
---
# Auto commit

## Quando
- Depois de cada **mudança lógica** concluída (um ficheiro migrado, um fix, um passo do progresso).
- **Antes** de operações arriscadas (rename em massa, mexer em mixins, upgrade de dependências).
- No fim da sessão, com `progresso.md` já atualizado.

## Como
1. `git status` e `git diff --stat` — vê o que vais commitar.
2. Garante que não há segredos nem ficheiros gerados (`build/`, `run/`, `.gradle/`).
3. Se compila: commit normal. Se não compila e queres guardar trabalho: prefixo `wip:`.
4. `git add` só do que pertence a esta mudança (evita `git add -A` cego).
5. Faz o commit e, se houver remote, `git push`.

## Mensagens (Conventional Commits, em inglês)
- `feat: ...` funcionalidade nova · `fix: ...` correção · `refactor: ...` migração sem mudar comportamento
- `build: ...` gradle/loom/deps · `docs: ...` progresso/README · `chore: ...` resto · `wip: ...` trabalho incompleto
- Primeira linha ≤ 72 caracteres, no imperativo: `refactor: migrate BrowserInstance to new render API`.
- Corpo opcional: o **porquê**, não o quê.

## Nunca
- `git push --force` (a não ser que o utilizador peça explicitamente).
- Commitar `.env`, tokens, chaves, ou jars/builds.
- Reescrever histórico já enviado para o remote.

## Nota
Existe um plugin (`.opencode/plugins/auto-commit.js`) que faz commits `wip(auto):` quando a sessão fica parada.
Isso não substitui commits com mensagens boas: faz os teus commits antes.
