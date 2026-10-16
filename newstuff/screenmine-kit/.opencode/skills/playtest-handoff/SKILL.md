---
name: playtest-handoff
description: Pedir ao utilizador uma corrida em jogo com passos curtos, comandos PowerShell para o log e uma tabela "se vires X então Y". Usar sempre que a resposta só existe dentro do Minecraft.
---
# Pedido de corrida

O agente **não corre o cliente**. A única fonte de verdade sobre render, input,
vídeo e áudio é o `latest.log` de uma corrida feita por uma pessoa. Cada corrida
custa minutos ao utilizador: desenha-a para decidir o máximo possível.

## Antes de pedir
1. Compila (`.\gradlew.bat build`) e faz commit do que vai ser testado (para a corrida ter um hash).
2. **Instrumenta todas as hipóteses concorrentes de uma vez.** Se há três causas possíveis, a mesma corrida tem de as distinguir (lição das corridas 4 e 5: um canal de diagnóstico partilhado com a página perdeu a corrida duas vezes).
3. Logs a cada **mudança**, não uma só vez (uma leitura única apanha o estado errado — `media=0` antes de o YouTube criar o player).
4. Config necessária em `run\config\screenmine.properties` (ex.: `debug.codecs=1`). Diz exatamente a linha.

## O pedido (usa esta estrutura, curta)
```
Corrida para decidir: <a pergunta, numa linha>
Commit: <hash>   Config: <linhas de screenmine.properties>

Passos (≤ 8):
1. ...
2. ...

No fim, cola só isto:
  Select-String "agent report|ScreenMine/codecs|ScreenMine/input|ONLOADEND" run\logs\latest.log | Select -Last 40

Como ler:
| Se vires...                      | Conclusão |
|---|---|
| ...                              | ... |

Sucesso = <critério observável>
```

## Comandos úteis (PowerShell)
```powershell
Select-String "ScreenMine" run\logs\latest.log | Select -Last 60
Select-String "IN keyPressed|IN charTyped" run\logs\latest.log | Select -Last 20
Select-String "codec probe|canPlayType|error.code" run\logs\latest.log
Get-Content run\logs\latest.log -Tail 200
```
Loggers do projeto: `ScreenMine/codecs`, `ScreenMine/input`. Prefixos de canal da página: `SMX|` (diagnóstico), `SMA|` (relatório do agente).

## Pedir sempre onde foi testado
Overlay (`B`) · frente a um ecrã no mundo · control mode (`C`) · singleplayer · servidor multiplayer. O mesmo bug pode comportar-se diferente em cada um (o `N` falhou no mundo e não no overlay).

## Depois da corrida
- Redige o log antes de o colar em qualquer ficheiro do repo (`repo-hygiene-privacy`): hostnames, IPs, tokens e utilizador do SO saem.
- Regista "Corrida N" no `progresso.md` (`evidence-labels`).
- Se o resultado contradiz o que afirmaste, diz-o.

## Nunca
- Preencher uma tabela de resultados sem corrida.
- Pedir uma corrida sem critério de sucesso.
- Pedir que o utilizador cole logs inteiros: pede o `Select-String` mínimo.
