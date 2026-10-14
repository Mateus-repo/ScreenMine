---
name: mc-port-workflow
description: Roteiro completo para portar um mod de Minecraft para outra versão. Usar no início do port e sempre que precisares de decidir o próximo passo.
---
# Fluxo de port

## Fase 0 — Reconhecimento
- Lê o `build.gradle`, `gradle.properties`, `fabric.mod.json`, mixins e a lista de ficheiros.
- Lista as **áreas de risco**: render, GUI, input, mixins, networking, dependências nativas, libs de terceiros.
- Estima o tamanho (nº de ficheiros e linhas por área). Escreve em `progresso.md`.

## Fase 1 — Build vazio
- Faz compilar um mod mínimo na versão destino (ver `fabric-loom-setup`).
- Só depois trazes código.

## Fase 2 — Dependências
- Cada dependência tem build para a versão destino? (ver `dependency-check`). Se não, decide: esperar, fork, substituir ou cortar a funcionalidade.

## Fase 3 — Spikes
- Para cada risco grande, faz o **mínimo que prova** que é possível (ex.: desenhar um quad, abrir um ecrã, ler uma tecla).
- Se um spike falha, replaneia **antes** de migrar o resto.

## Fase 4 — Migração
- Por área, do mais fácil para o mais arriscado, a compilar sempre.
- Ordem típica: utilitários/config → registos → GUI → input → mixins → render.
- Usa `api-verification` para cada nome duvidoso.

## Fase 5 — Teste
- `runClient` + checklist de funcionalidades (ver `build-and-test`).

## Fase 6 — Release
- Ver `release-checklist`.

## Princípios
- Mantém a lista de erros do compilador agrupada por ficheiro e ataca por grupos.
- Não "arranjes" erros apagando funcionalidade sem avisar o utilizador.
- Se a versão destino ainda está em evolução (snapshots/ligeiras mudanças), regista a versão exata usada.
