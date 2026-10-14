---
name: input-and-lwjgl
description: Migrar teclado, rato, scroll, atalhos e janela quando o backend de input mudou (GLFW, SDL ou eventos novos). Usar em keybindings e envio de input para componentes embebidos.
---
# Input e janela

## Passos
1. Lista tudo o que usa input: `KeyBinding`, callbacks de rato/teclado, `GLFW*`/`SDL*`, `InputUtil`, clipboard.
2. Confirma que backend a versão destino usa e que classes de key/botão existem (ver `api-verification`).
3. Prefere **APIs do próprio Minecraft/Fabric** a chamar o backend nativo diretamente.
4. Keybindings: confirma como se registam e a categoria; atualiza as chaves de tradução.

## Cuidados
- **Códigos de tecla/modificadores** podem ter valores diferentes entre backends. Se enviares input a algo externo (ex.: um browser embebido), mapeia explicitamente e testa.
- Rato: coordenadas em píxeis da janela vs escalonadas (GUI scale). Confirma qual usas.
- Scroll: sinal e escala podem mudar.
- Foco: o jogo pode estar a capturar o rato; garante que libertas/capturas corretamente.

## Teste obrigatório
Em `runClient`: teclas normais, modificadores (Shift/Ctrl/Alt), setas, Enter/Backspace, rato (clique/arrastar/scroll), colar texto. Regista o que funciona em `progresso.md`.
