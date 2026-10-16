---
name: build-and-test
description: Compilar, preparar corridas do cliente e validar o mod, registando resultados com honestidade. Usar depois de cada grupo de alterações e antes de commits importantes.
---
# Build e teste

## Comandos (Windows / PowerShell)
- `.\gradlew.bat build` — compila e empacota (jar em `build\libs\`).
- `.\gradlew.bat runClient` — arranca o cliente de desenvolvimento (**corre-o a pessoa**, não o agente).
- `.\gradlew.bat build --refresh-dependencies` — problemas de dependências.
- `.\gradlew.bat compileJava 2>&1 | Select -First 50` — primeiros erros, para agrupar por ficheiro.
- Log do jogo: `run\logs\latest.log`.

## Ciclo
1. Corrige por **grupos de erros** (ficheiro/causa comum).
2. Compila → corrige → compila. Commit quando verde.
3. Quando compila, **prepara a corrida** (`playtest-handoff`): commit, config, passos, comandos de log, tabela de leitura. O utilizador corre e cola o mínimo.
4. Regista "Corrida N" no `progresso.md` (`evidence-labels`).

## Checklist de teste (adapta)
- [ ] O jogo arranca sem crash e o mod aparece na lista
- [ ] Sem erros/avisos novos do mod no `latest.log`
- [ ] Cada funcionalidade principal: abre, usa, fecha, usa de novo (e várias vezes: ciclo de vida de texturas)
- [ ] Entrar/sair de mundo e de servidor sem crash
- [ ] Sem fugas óbvias (memória cresce sem parar) em 5 minutos
- [ ] Onde foi testado: overlay / mundo / control mode / singleplayer / multiplayer

## Honestidade
- "Compila" e "corre no jogo" são coisas diferentes: regista as duas.
- Um build verde já escondeu três bugs de render. Se não correste o jogo, diz **"não testado em jogo"**; não escrevas "funciona".
- Antes de commit: guarda de privacidade (`repo-hygiene-privacy`).
