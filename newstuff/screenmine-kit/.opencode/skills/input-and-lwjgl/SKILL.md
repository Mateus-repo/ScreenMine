---
name: input-and-lwjgl
description: Migrar teclado, rato, scroll, atalhos e janela quando o backend de input mudou (GLFW, SDL ou eventos novos). Usar em keybindings e envio de input para componentes embebidos. Para o browser CEF ver também jcef-keyboard-input.
---
# Input e janela

## Passos
1. Lista tudo o que usa input: `KeyMapping`, callbacks de rato/teclado, `InputConstants`, clipboard.
2. Confirma que backend a versão destino usa (em 26.3 é **SDL3**; não existe `org.lwjgl.glfw`) e que classes de key/botão existem (`api-verification`).
3. Prefere **APIs do próprio Minecraft/Fabric** a chamar o backend nativo diretamente.
4. Keybindings: confirma como se registam e a categoria (`KeyMapping.Category`); atualiza as chaves de tradução.
5. Para enviar input ao browser embebido (CEF offscreen) segue `jcef-keyboard-input`: o `javap` não chega para saber o que o passo nativo faz.

## Cuidados
- **Códigos de tecla/modificadores** diferem entre backends. Se enviares input a algo externo, mapeia explicitamente e testa.
- Eventos em 26.3 são records: `KeyEvent`, `MouseButtonEvent`, `CharacterEvent`.
- Rato: coordenadas em píxeis da janela vs escalonadas (GUI scale). Confirma qual usas, e usa o mesmo no overlay e no mundo.
- Scroll: sinal e escala podem mudar; aplica a mesma escala (`SCROLL_SPEED`) nos dois caminhos.
- Foco: o jogo pode estar a capturar o rato; liberta/captura corretamente e larga o foco do browser ao abrir menus.
- Não há evento Fabric para intercetar input antes do Minecraft no mundo: usa mixins em `KeyboardHandler`/`MouseHandler`.

## Teste obrigatório (por uma pessoa, `playtest-handoff`)
Teclas normais, modificadores (Shift/Ctrl/Alt), setas, Enter/Backspace, rato (clique/arrastar/scroll), colar texto — **no overlay e no mundo**. Tabela por tecla no `progresso.md`.
