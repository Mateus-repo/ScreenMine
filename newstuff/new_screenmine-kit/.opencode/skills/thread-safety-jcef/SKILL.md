---
name: thread-safety-jcef
description: Mapa de threads do ScreenMine (render, CEF UI, rede) e regras para estado partilhado, texturas e ciclo de vida do browser. Usar em bugs intermitentes, fugas, crashes de render e ao mexer em BrowserInstance/BrowserTexture/NetClient.
---
# Threads e ciclo de vida

## Mapa **[V]** (`review.md`)
| Thread | O que toca |
|---|---|
| Render / cliente (`onTick`, `extractRenderState`, `render`) | quase tudo |
| `ScreenMine-Net` (leitura) | só `inbound.add(...)` |
| `ScreenMine-Net-Writer` (escrita) | só `outbound` e o socket |
| **CEF UI thread** | `onLoadEnd`, `onTitleChange`, `onAddressChange`, `onConsoleMessage` |
| CEF render | frames para a `GpuTextureView` |

## Regras
1. **Estado escrito numa thread e lido noutra** → `volatile`, fila concorrente ou `synchronized`. Os callbacks do CEF correm na UI thread do CEF, não na do jogo (achados A3/A4: `applyingRemote`, `agentInjected`, `appliedGain`, `browser`, `closed`).
2. **Um `volatile` não torna composto atómico.** Se precisas de "ler-decidir-escrever", usa lock/`AtomicReference`/CAS. Documenta quando um campo **nunca protegeu nada** (como `applyingRemote`).
3. **Nada de render/GL fora da render thread.** Callbacks do CEF só enfileiram trabalho para o tick; não tocam em texturas nem no `TextureManager`.
4. **Texturas:** `BrowserTexture.close()` é **no-op** (a `GpuTexture` é do browser; fechar seria double free). `textureView` e `sampler` seguem **juntos** (nulos ou não, nunca um só). Registo por `Identifier` no `TextureManager`.
5. **`detach` + `attach` no mesmo frame** pode deixar a textura registada a apontar para um browser fechado (A5, corrigido em `e514c82`): desregista antes de registar, e verifica o identificador.
6. **`createBrowser` e `MCEFApi.getInstance()` podem bloquear.** `initialize().isDone()` / `getInstanceFuture()` não. Não bloqueies a render thread; `try/catch` à volta de `ensureCreated()` para não repetir a falha todos os frames (C1).
7. **`LIVE` só cresce se `close()` não correr** (B9); `handlersInstalled` não se repõe (B10). Todo o `create` tem o seu `close` no mesmo desenho.
8. **Rede:** `send()` em `CONNECTING` não pode deitar o pacote fora (A6); uma writer thread por tentativa (B4) e `inbound` sem limite (B6) estão anotados — não os agraves.

## Ao corrigir
- Escreve o **mapa de quem chama** antes de mexer (`grep` dos callers).
- Depois da correção, pede a corrida que a prova; um build verde não apanha concorrência nem fugas (`playtest-handoff`).
- Mede memória/texturas em 5 minutos (`build-and-test`), e abre/fecha o ecrã várias vezes.
