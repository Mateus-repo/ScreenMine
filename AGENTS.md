# AGENTS.md — Port de mod Minecraft (Fabric)

## Projeto
- **Mod:** ScreenMine (antes CoScreen)
- **Port:** Minecraft 1.21.4 → 26.3 (Fabric, cliente-only)
- **Referência (só leitura, NUNCA alterar):** `C:\Users\Strefiz\Documents\GitHub\CoScreen-main`
- **Repo de trabalho:** https://github.com/Mateus-repo/ScreenMine
- **Objetivo:** browser Chromium embebido (MCEF Modern) desenhado como um ecrã
  plano no mundo — criar, mover, redimensionar, navegar e ver YouTube dentro do
  jogo. Tem de funcionar: (1) ecrã no mundo com a página real do browser,
  (2) control mode com rato/teclas/scroll sem partir o WASD, (3) os 9 ecrãs de
  menu, (4) áudio com falloff por distância, (5) rede/sincronização de playback
  igual à 1.21.4.

### Contexto específico deste port
- A 26.3 é **não ofuscada**: Yarn foi descontinuado, passou-se aos nomes Mojang.
- A 26.3 trocou **GLFW por SDL3**. Não existe `org.lwjgl.glfw`.
- O **render state saiu do `RenderSystem`** para `RenderPipeline` /
  `com.mojang.renderpearl`. `Tessellator` / `BufferRenderer` não existem.
- **MCEF do CinemaMod morreu em 1.21.4.** Usamos `mcef-modern` (DimasKama).
  Não voltar a tentar o `mcef` original.
- O `README.md` da raiz é o README do kit de skills, não do mod. Reescrever no
  fim do port.
- Ordem de trabalho decidida: **spike de render primeiro**, depois GUI, depois
  input, depois render completo. O spike decide se o mod é viável.

## Comunicação
- Responde em **português europeu (PT-PT)**, direto e sem enrolar.
- Código, identificadores e mensagens de commit em **inglês** (Conventional Commits).
- Se não tens certeza de algo, diz. Não inventes.

## Regras de ouro
1. **Início de sessão:** lê `progresso.md`. **Fim de cada passo:** atualiza-o.
2. **Nunca adivinhes nomes de API.** Confirma sempre contra o jar/sources reais
   (ver skill `api-verification`). Pesquisas e memória podem estar erradas.
3. **Sem rename em massa às cegas.** Lista primeiro o impacto. Nomes que mantêm
   o mesmo nome mas mudaram de significado/assinatura tratam-se à mão.
4. **Compila depois de cada ficheiro ou grupo pequeno** (`./gradlew build`).
5. **Compilar ≠ funcionar.** Testa no cliente (`./gradlew runClient`) e regista o resultado.
6. **Incerteza grande → spike pequeno primeiro.** Prova a parte arriscada
   antes de gastares horas no resto.
7. **Um commit = uma mudança lógica.** Ver skill `auto-commit`.
8. **Não mexas na referência**, nem em ficheiros fora do repo de trabalho.
9. **Versões de dependências:** confirma no Maven/Modrinth. Não inventes números.
10. **Nunca commits segredos** (tokens, `.env`, chaves).
11. **Um único documento de estado:** tudo vive no `progresso.md`. Se criares
    outro ficheiro de plano, ele passa a ser lido como fonte de verdade — ou
    então elimina-o quando o plano for absorvido.

## Fluxo de trabalho
1. `mc-port-workflow` — fases do port do início ao fim.
2. `fabric-loom-setup` — build, loom, `fabric.mod.json`.
3. `dependency-check` — libs existem para a versão destino?
4. `api-verification` + `mixin-migration` + `render-gui-migration` + `input-and-lwjgl` — migração do código.
5. `build-and-test` + `crash-debugging` — validar.
6. `code-review` antes de cada commit relevante.
7. `release-checklist` no fim.

## Skills disponíveis (`.opencode/skills/`)
| Skill | Para quê |
|---|---|
| `auto-commit` | Quando e como fazer commit (e push) |
| `git-github` | Branches, remote, tags, releases |
| `progress-tracking` | Como manter o `progresso.md` |
| `mc-port-workflow` | Roteiro completo do port |
| `fabric-loom-setup` | Gradle, Loom, versões, `fabric.mod.json` |
| `dependency-check` | Verificar libs/mods dependentes |
| `api-verification` | Confirmar nomes reais de classes/métodos |
| `mixin-migration` | Migrar mixins e accessors |
| `render-gui-migration` | Render e GUI entre versões |
| `input-and-lwjgl` | Teclado/rato/janela (GLFW/SDL) |
| `build-and-test` | Build, runClient, testes |
| `crash-debugging` | Ler crash reports e logs |
| `code-review` | Auto-revisão antes de commit |
| `release-checklist` | Publicar o jar |

## Definition of done (por tarefa)
- [ ] Compila sem erros nem avisos novos
- [ ] Testado no `runClient` (ou justificado porque não dá)
- [ ] `progresso.md` atualizado
- [ ] Commit feito com mensagem clara