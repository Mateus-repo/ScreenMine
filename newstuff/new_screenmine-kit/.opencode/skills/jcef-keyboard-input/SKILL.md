---
name: jcef-keyboard-input
description: Teclado e rato no browser CEF offscreen do mcef-modern — Backspace/Enter, Ctrl+A/C/V/X/Z/Y, Alt+setas, seleção com o rato, control mode no mundo. Usar em qualquer bug de edição de texto ou atalhos.
---
# Teclado e rato no CEF offscreen

## Cadeia verificada com `javap` (mcef-modern 0.3.3 + jcef-api) **[V]**
```
MCEFBrowser.onKeyPressed(KeyEvent)      -> java.awt.event.KeyEvent 401 (KEY_PRESSED)
MCEFBrowser.onCharTyped(CharacterEvent) -> java.awt.event.KeyEvent 400 (KEY_TYPED, codepoint)
MCEFBrowser.onKeyReleased(KeyEvent)     -> java.awt.event.KeyEvent 402 (KEY_RELEASED)
        -> CefBrowser_N.sendKeyEvent (protected final) -> N_SendKeyEvent (NATIVO)
```
`KeyboardHandler` do Minecraft tem `keyPress(long,int,KeyEvent)` e `charTyped(long,CharacterEvent)`.

## O que **não** se sabe **[H]**
- Se o JCEF sintetiza o evento `CHAR` para teclas não imprimíveis (Enter 13, Backspace 8, Tab 9). O passo final é nativo: **o `javap` não decide**.
- Se o Minecraft/SDL3 emite `charTyped` para essas teclas. Decide-se em jogo.

## Primeiro, o teste sem código
Com o overlay (`B`) aberto numa caixa de texto: escrever `abcdef`, `Backspace`, `Enter`, `Ctrl+A`, `Ctrl+C`. Depois:
```powershell
Select-String "IN keyPressed|IN charTyped" run\logs\latest.log | Select -Last 20
```
| Vês | Conclusão |
|---|---|
| `keyPressed` **e** `charTyped` | o Minecraft emite os dois; problema a jusante (JCEF/CEF) |
| `keyPressed` **sem** `charTyped` nas teclas de edição | hipótese confirmada; sintetizar o `CHAR` / usar a API de frame |

## Atalhos com API pública (sem mixin, sem shim JS) **[V]**
- `CefFrame`: `selectAll() copy() cut() paste() undo() redo()`.
- `CefBrowser`: `canGoBack() goBack() canGoForward() goForward()` → `Alt+←/→`.
- Mapeamento: `Ctrl+A` selectAll · `Ctrl+C` copy · `Ctrl+X` cut · `Ctrl+V` paste · `Ctrl+Z` undo · `Ctrl+Y` / `Ctrl+Shift+Z` redo · `Alt+←/→` back/forward (só se `canGo*`).
- O CEF offscreen **não** executa estes atalhos sozinho.
- **[I]** Obter o frame com `getCefBrowser().getFocusedFrame()` (ou `getMainFrame()` se não houver foco). **Confirma com `javap`** antes de usar; é o tipo de nome que não se adivinha.
- **[H]** Se `paste()` usa o clipboard do SO. Se não, lê o texto do clipboard do sistema (o `KeyboardHandler` do Minecraft tem um gestor) e envia-o por codepoint com `onCharTyped`. Testa com texto que tenha acentos e quebras de linha.

## Seleção com o rato
Arrastar com o botão esquerdo tem de enviar movimento **com o modificador do botão premido** (`EVENTFLAG_LEFT_MOUSE_BUTTON` ou equivalente); sem isso o CEF não seleciona. Verifica `mouseDragged`, duplo clique (palavra) e triplo clique (linha). Coordenadas: pixéis da janela vs escalados pelo GUI scale — confirma qual usas (o overlay e o mundo já divergiram na roda).

## Teste por tecla (tabela obrigatória no `progresso.md`)
Página local via `loadURL("data:text/html;...")` com um `<textarea>` e este registo:
```html
<textarea id=t rows=8 cols=60></textarea><pre id=o></pre>
<script>
for (const ev of ['keydown','keypress','input','keyup'])
  t.addEventListener(ev, e => o.textContent =
    ev+' key='+e.key+' code='+e.code+' kc='+e.keyCode+' val='+JSON.stringify(t.value)+'\n'+o.textContent);
</script>
```
Para cada tecla: chega `keydown`? `keypress`? `input`? mudou o valor? Uma linha por tecla.

## Control mode no mundo
- O teclado só vai para a página quando um campo de texto tem foco **lá**; senão o WASD tem de continuar a andar. Quem sabe se há foco de edição é o agente (`isEditing()`): se o canal do agente estiver morto, a edição no mundo fica bloqueada. Ver `page-agent-channel`.
- `Esc` sai do control mode e **não** abre o menu do jogo. Ao abrir menus, larga o foco do browser (achado B1).
- A roda no mundo usa mixins em `MouseHandler`/`KeyboardHandler` (não há evento Fabric em 26.3). Mixins nomeiam os alvos diretamente; sem annotation processor (ver `build.gradle`).

## Não fazer
- Mapear códigos GLFW antigos: não existem; SDL3 usa outros valores. Mapeia explicitamente e testa.
- Declarar "Backspace funciona" por ter compilado.
