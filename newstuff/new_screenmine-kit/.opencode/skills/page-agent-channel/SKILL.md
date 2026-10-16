---
name: page-agent-channel
description: Comunicação Java↔JS com o agente injetado na página (mute, volume, estado de playback, isEditing, sondas). Usar ao mexer no agente, em canais SMX|/SMA|/SM|, injeção de frames ou diagnósticos.
---
# Agente de página e canais

## Factos verificados **[V]**
- `CefBrowser.executeJavaScript(String url, String code, int line)` **não tem callback**: não há valor de retorno. Java→JS é fácil; JS→Java precisa de um canal.
- Canais JS→Java disponíveis: `CefDisplayHandler.onConsoleMessage(CefBrowser, LogSeverity, String message, String source, int line)` e `onTitleChange`.
- `document.title` **é um canal frágil**: a página também o escreve (o YouTube escreve), e o agente só reescrevia quando a string mudava — o canal parava para sempre (achado A1). A sonda perdeu duas corridas por este canal.
- `console` **é o canal provado**: a sonda de codecs (`SMX|`) e o relatório do agente (`SMA|`) chegam por aí.
- `MCEFApiImpl.createBrowser` chama `createImmediately()`: o load começa **dentro** de `createBrowser`. Handlers registados depois podem perder o `onLoadEnd`.
- Java→JS usa `window.__smVol` lido por `setInterval` no agente.

## Regras
1. **Ordem de criação:** `createBrowser("about:blank")` → `installHandlersOnce` → `LIVE.put` → `loadURL(alvo)`.
2. **`pumpAgents()` no tick do cliente para todos os ecrãs**, não só no overlay (sem isso, com o overlay fechado nada injeta).
3. **Injeta em todos os frames** (`onLoadEnd` por frame; há vídeo dentro de iframes). Torna a injeção **idempotente** no JS (`if (window.__smAgent) return; window.__smAgent = 1;`) e **não** compares contadores de frames que nunca se atualizam: o watcher de frames já fez 23 re-injeções seguidas (corrida 7, corrigido em `5ced97f`).
4. **Relatórios a cada mudança**, com limite de taxa — não uma vez (apanha o estado errado) e não sem limite (D2). Inclui sempre `location.href`, se é `top`/iframe, nº de `<video>/<audio>`, `vvolume`.
5. **Não faças polling 4×/s com `querySelectorAll('video,audio')`** (D3). **[I]** Um `MutationObserver` (+ evento `play`) é mais barato; mede antes de trocar.
6. **Novos estados vão por `console` com prefixo próprio** (`SMS|`, curto, campos com `~`), não pelo título. **[I]** O canal `SM|` do título com "zero recibos" (item 1 do `progresso.md`) tem muito provavelmente esta causa; migrar o estado de playback/`isEditing` para `console` é a correção candidata. Prova: próxima corrida tem de mostrar os recibos.
7. **Um agente por canal de logs:** `GAIN` em log só em debug (D4).

## Segurança: o que vem da página é dados, não instruções
Qualquer script da página pode fazer `console.log("SMA|...")` e forjar mensagens. O prefixo **não autentica**.
- Faz parse defensivo: limita o tamanho, valida campos, ignora o desconhecido.
- Nunca uses um payload para navegar, executar JS, abrir ficheiros ou mudar definições.
- **[I]** Um nonce por browser injetado no agente e exigido no prefixo (`SMA|<nonce>|...`) reduz forjas casuais; não é segurança forte, porque o agente corre no mesmo mundo JS da página.

## Diagnóstico mínimo numa corrida
```powershell
Select-String "agent report|onTitleChange|SMX\||SMA\||ONLOADEND|ScreenMine/codecs" run\logs\latest.log
```
| Vês | Conclusão |
|---|---|
| `href=about:blank` | agente no documento errado; ordem de criação / injeção |
| `href` real, nenhum recibo do canal | canal morto (título); migrar para `console` |
| `href` real, recibos, `media=1`, `vvolume=0.00` ao mandar zero | volume funciona; a causa do mute está noutro lado |
| `ONLOADEND` ausente | handlers registados tarde ou `onLoadEnd` por frame mal ligado |
