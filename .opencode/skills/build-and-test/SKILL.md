---
name: build-and-test
description: Compilar, correr o cliente e validar o mod, registando resultados. Usar depois de cada grupo de alterações e antes de commits importantes.
---
# Build e teste

## Comandos
- `./gradlew build` — compila e empacota (jar em `build/libs/`).
- `./gradlew runClient` — arranca o cliente de desenvolvimento.
- `./gradlew build --refresh-dependencies` — se houver problemas de dependências.
- `./gradlew compileJava 2>&1 | head -50` — primeiros erros, para agrupar por ficheiro.

## Ciclo
1. Corrige por **grupos de erros** (por ficheiro/causa comum), não um a um aleatório.
2. Compila → corrige → compila. Commit quando verde.
3. Quando compila: `runClient`, carrega um mundo, usa a funcionalidade, vê `logs/latest.log`.

## Checklist de teste (adapta)
- [ ] O jogo arranca sem crash e o mod aparece na lista
- [ ] Sem erros/avisos novos do mod no `latest.log`
- [ ] Cada funcionalidade principal: abre, usa, fecha, usa de novo
- [ ] Entrar/sair de mundo e de servidor sem crash
- [ ] Sem fugas óbvias (memória cresce sem parar) em 5 minutos
- [ ] Servidor dedicado (se o mod não for client-only)

## Registo
Escreve em `progresso.md`: o que testaste, como, o resultado, e o que ainda não foi testado.
Se não consegues correr o jogo (sem display, etc.), diz isso claramente e não afirmes que "funciona".
