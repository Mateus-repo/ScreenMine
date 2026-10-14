---
name: git-github
description: Fluxo git/GitHub para o port: branches, remote, push, tags e releases. Usar ao iniciar o repo, ao criar branches ou ao preparar uma versão.
---
# Git e GitHub

## Início
- `git init` (se necessário), `.gitignore` presente **antes** do primeiro commit.
- `git remote add origin <url>` e primeiro push da branch principal.

## Branches
- `main` estável (compila). Trabalho em `port/<versao-destino>` (ex.: `port/26.3`).
- Experiências arriscadas: `spike/<tema>`; se falhar, apaga a branch e regista a conclusão em `progresso.md`.

## Rotina
- `git pull --rebase` antes de começar se houver mais do que uma pessoa/máquina.
- Push depois de cada commit relevante.
- Merge para `main` só quando compila **e** arranca no cliente.

## Tags e releases
- Tag por versão: `v<versao-mod>+<versao-mc>` (ex.: `v1.0.0+26.3`).
- `git tag -a <tag> -m "..."` e `git push --tags`.
- Release no GitHub com o jar (não commitar o jar no repo).

## Referência
- O repo/pasta original é só leitura. Se precisares de comparar, usa `git diff --no-index` ou abre em paralelo — não copies ficheiros por cima.
