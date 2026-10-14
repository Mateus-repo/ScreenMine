# progresso.md — ScreenMine · 1.21.4 → 26.3

_Atualiza no fim de cada passo. Mantém curto e factual._

> Este é o **único** documento de estado do port. O antigo `PORTING-26.3.md`
> foi absorvido aqui e apagado, porque um segundo documento de plano passa a ser
> lido como fonte de verdade e tinha nomes ainda por confirmar.

## Estado atual
- **Fase:** 3 — Spikes ✅ **a incógnita do render world-space está resolvida**
- **Compila?** Sim, mas só o spike; o resto do código está estacionado em
  `%TEMP%/opencode/parked/screenmine` e tem de ser reposto
- **Corre no jogo?** Parcial — browser confirmado em runtime; o quad no mundo
  ainda não foi visto (falta entrar num mundo)
- **Último commit:** ver `git log`

## Fases
- [x] 0. Reconhecimento
- [x] 1. Build a compilar contra MC 26.3 real
- [x] 2. Dependências confirmadas (mcef-modern substitui MCEF)
- [ ] 3. **Spike de render world-space** ← próximo passo
- [ ] 4. Migração GUI (9 ecrãs, ~120 dos 200 erros)
- [ ] 5. Input (SDL3 / mixins)
- [ ] 6. Render completo
- [ ] 7. Teste no cliente
- [ ] 8. Polimento e release

## Estado do código
22 ficheiros Java, ~4024 linhas (antes do rename).
`BrowserInstance.java` compila limpo. Restam 4 áreas com erros:

| Ficheiro | Erros | Área |
|---|---|---|
| `BrowserFullScreen` + `MenuFriends` | ~120 | GUI: `render` → `extractRenderState`; input por records |
| `ScreenMineClient` | 38 | Input; 8 são `org.lwjgl.glfw` (não existe) |
| `ScreenWorld`, `PlacedScreen`, `WorldScreenRenderer`, `Hud` | 24 | Render: reescrita |

## Riscos conhecidos
| Risco | Estado | Notas |
|---|---|---|
| Desenhar quad com textura própria no mundo | 🟡 **arquitectura resolvida, execução por confirmar** | API compila e o browser corre; falta entrar num mundo para ver o quad. |
| Scroll/teclas no mundo em SDL3 | por resolver | Não há evento Fabric. Planeado: mixins em `MouseHandler`/`KeyboardHandler`. |
| Ordem de desenho vs. nuvens | aceite | `WorldRenderEvents.LAST` não existe. `LevelRenderEvents.END_MAIN` corre **antes** das nuvens. Regressão a registar. |
| `mcef-modern` bloqueia a render thread | ✅ ok | Browser criado no tick 10 s após arrancar, sem travar. |
| Chromium download no 1.º arranque | ✅ ok | Chromium carregou e subiu uma frame para a `GpuTextureView`. |
| Licença | ✅ **resolvida** | CoScreen é AGPL-3.0 e o ScreenMine é obra derivada → AGPL-3.0, com NOTICE. |
| Nomes de API ainda em fluxo | menor | `LevelRenderEvents` foi renomeado nos 26.x e os docs ainda migravam no lançamento. |

## Decisões tomadas
- (2026-10-14) **Renomear CoScreen → ScreenMine por inteiro** — mod id, pacote
  `dev.screenmine`, namespace de assets, config, agente JS. O repo chama-se
  ScreenMine e a licença é MIT (c) Mateus.
- (2026-10-14) **ScreenMine é só 26.3.** O CoScreen 1.21.4 fica intacto como
  referência. Suportar as duas versões no mesmo código é inviável: a 26.3 removeu
  Yarn, GLFW e o `RenderSystem` antigo.
- (2026-10-14) **MCEF do CinemaMod está morto** → `mcef-modern` (DimasKama).
  O upstream acaba em `2.1.6-1.21.4` e usa `org.lwjgl.glfw`, que não existe na 26.3.
  O maintainer autorizou forks.
- (2026-10-14) **Render world-space via `AbstractTexture` registado no
  `TextureManager`.** Confirmado com `javap` que `RenderSetupBuilder` só aceita
  `Identifier` e que não há ponte pública de `TextureSetup` para world space.
  A solução é subclassear `AbstractTexture` a devolver a `GpuTextureView` do
  browser e registá-la com `TextureManager.register(Identifier, AbstractTexture)`;
  a partir daí `RenderTypes.text(id)` funciona como em vanilla. **Só API pública —
  sem mixin, sem access widener, sem `RenderPipeline` custom.**
- (2026-10-14) **`BrowserTexture.close()` tem de ser no-op.** O default de
  `AbstractTexture` fecha a `GpuTexture`, que é do browser — fechá-la aqui
  libertaria a textura debaixo das feetas dele (double free).
- (2026-10-14) **Spike de render antes da GUI.** A GUI são ~120 erros mecânicos;
  se o render world-space não for possível, esse trabalho era perdido. Render
  primeiro, mesmo custando mais.
- (2026-10-14) **Input via mixins, não `Screen` transparente.** `ScreenKeyboardEvents`
  é a API suportada, mas abrir um `Screen` mata o input de movimento — e o
  requisito é "o WASD continua a andar enquanto se vê". Exige infraestrutura nova
  (mixin config; o projeto não tinha nenhum).

## Descobertas (APIs que mudaram) — todas confirmadas com `javap` no jar 26.3
Jar usado: `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-7e9a32a5b8/26.3/minecraft-merged-7e9a32a5b8-26.3.jar`

### Toolchain
| Antes | Agora |
|---|---|
| Loom `fabric-loom` 1.9.2 (remapeava) | `net.fabricmc.fabric-loom` 1.18-SNAPSHOT (não remapeia) |
| Yarn `1.21.4+build.8:v2` | **nenhum** — o jar já vem com os nomes Mojang |
| Gradle 8.14.2 | 9.7.1 |
| Java 21 | **25** |
| `modImplementation` | `implementation` |
| `remapJar` | `jar` |

### Classes
| Antes (Yarn) | Agora (26.3) |
|---|---|
| `MinecraftClient` | `net.minecraft.client.Minecraft` |
| `Vec3d` | `net.minecraft.world.phys.Vec3` |
| `MatrixStack` | `com.mojang.blaze3d.vertex.PoseStack` |
| `RotationAxis` | `com.mojang.math.Axis` (`Axis.YP`, etc.) |
| `Text` | `net.minecraft.network.chat.Component` |
| `Formatting` | `net.minecraft.ChatFormatting` |
| `SoundCategory` | `net.minecraft.sounds.SoundSource` |
| `InputUtil` | `com.mojang.blaze3d.platform.InputConstants` |
| `InputUtil.Type.KEYSYM` | `InputConstants.Type.KEYBOARD` |
| `KeyBinding` | `net.minecraft.client.KeyMapping` |
| `KeyBindingHelper` | `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper` |
| `DrawContext` | `net.minecraft.client.gui.GuiGraphicsExtractor` |
| `ButtonWidget` / `TextFieldWidget` | `gui.components.Button` / `gui.components.EditBox` |
| `Screen` | `net.minecraft.client.gui.screens.Screen` |
| `ServerInfo` | `net.minecraft.server.ServerInfo` (**saiu de `client.*`**) |
| `Entity` | `net.minecraft.world.entity.Entity` |
| `ChatHud` | `gui.components.ChatComponent` |
| `Tessellator` / `BufferRenderer` | **não existem** (0 ocorrências no jar) |
| `ShaderProgramKeys` | `net.minecraft.client.renderer.RenderPipelines` |

### Métodos
| Antes | Agora |
|---|---|
| `Entity.getEyePos()` | `getEyePosition()` |
| `Entity.getRotationVector()` | ⚠️ **devolve `Vec2` (pitch/yaw), NÃO uma direção.** Usar `getLookAngle()`. Não renomear em bloco. |
| `World.getRegistryKey()` | `Level.dimension()` |
| `Options.getSoundVolume(S)` | `getFinalSoundSourceVolume(S)` |
| `KeyBinding.wasPressed()` | `KeyMapping.consumeClick()` |
| `KeyBinding.getBoundKeyLocalizedText()` | `KeyMapping.getTranslatedKeyMessage()` |
| `Minecraft.setScreen(s)` | ⚠️ **não existe.** É `Minecraft.gui.setScreen(s)`; `Minecraft.gui.hud` é um campo |
| `Screen.render(DrawContext,…)` | `Screen.extractRenderState(GuiGraphicsExtractor,…)` |
| `Screen.close()` | `Screen.onClose()` |
| `Screen` input (params soltos) | records: `KeyEvent`, `MouseButtonEvent`, `CharacterEvent` |

### Render (a reconstruir)
- `RenderSystem.setShader` / `setShaderTexture` / `disableBlend` / `depthMask` /
  `disableCull` — **todos removidos**. O estado passou para `RenderPipeline` /
  `RenderSetup` (`com.mojang.renderpearl`).
- `WorldRenderEvents.LAST` → `LevelRenderEvents.END_MAIN`.
  `LevelRenderContext` perdeu `camera()`; a câmara está em
  `levelState().cameraRenderState.pos`. O `PoseStack` é `poseStack()`.
- Caminho de submissão: `SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType, renderer)`.
- **`TextureSetup.singleTexture(GpuTextureView, GpuSampler)` existe** — confirmado
  no testmod do `mcef-modern`. É o que permite ligar a textura do Chromium.
  Só está demonstrado em caminho **2D/GUI** (`BlitRenderState` + `RenderPipelines.GUI_TEXTURED`).
  A variante **world-space** é a incógnita do spike.

### Browser — mcef-modern
- `MCEFApi.getInstance()` **bloqueia**; `initialize().isDone()` / `getInstanceFuture()` não.
- `createBrowser(url, transparent)` — sem dimensões; depois `resize(w, h)`.
- Input: `onMouseClicked(MouseButtonEvent, boolean)`, `onMouseScrolled(int,int,double)`,
  `onKeyPressed(KeyEvent)`, `onCharTyped(CharacterEvent)`, …
- Textura: `getTextureView()` → `GpuTextureView` (não há mais id GL inteiro).
- **A wrapper não expõe** `executeJavaScript`, `loadURL`, `reload`, `goBack`, `getURL`.
  Tudo isso via `browser.getCefBrowser()` (verificado: o `CefBrowser` tem todos).
- Mod id do dependente é **`mcef-modern`**, não `mcef`.
- Embute `jcef-api` + `jcefmaven` com `include()`, logo não os gerimos à mão.

## Perguntas em aberto
1. ~~Existe um estado world-space que aceite `TextureSetup`?~~ **Respondida: não,
   e não é preciso** — ver a decisão do `AbstractTexture` acima.
2. ~~O `mcef-modern` bloqueia a render thread?~~ **Respondida: não.**
3. O input via SDL3 chega ao CEF com o comportamento certo (mesmo nome, outra
   semântica é o risco clássico aqui). **Por testar em jogo.**
4. `RenderTypes.text()` tem lightmap. O CoScreen desenhava *unlit* (o ecrã
   mantinha-se legível à noite). Provavelmente há de usar outro `RenderType` ou
   um sem lightmap. **Por testar.**
5. Ordem de desenho: `END_MAIN` corre antes das nuvens; o CoScreen 1.21.4
   desenhava depois. **Por decidir** se há alternativa.

## Resultado do spike (2026-10-14)
Compila contra MC 26.3 e corre. Cadeia toda confirmada:
`LevelRenderEvents.END_MAIN` → `ctx.poseStack()` /
`ctx.submitNodeCollector().submitCustomGeometry(...)` / `ctx.levelState().cameraRenderState.pos`
→ `RenderTypes.text(Identifier)` → `VertexConsumer.addVertex(Pose, …).setUv().setColor()`
→ `TextureManager.register(Identifier, AbstractTexture)`.

Em runtime (`runClient`):
```
spike registered on LevelRenderEvents.END_MAIN
BROWSER CREATED, texture registered as screenmine:spike/browser
TEXTURE VIEW PRESENT — mcef-modern uploaded a frame
```
Sem erros do lado do Minecraft. O ruído `cast_crl.cc / CRL - Verification failed`
é do próprio Chromium, não nosso.

**O que o spike NÃO provou:** o draw do quad. O cliente ficou no menu principal,
onde não há mundo, por isso `SPIKE DRAW OK` nunca apareceu no log. Falta um humano
entrar num mundo e confirmar que o ecrã se vê — e aí validar orientação, culling,
espelhamento e luz.

### Tentativas de entrar num mundo sem humano (ambas falharam)
1. Copiei um mundo 26.3 de `AFK-BOT--NO-MODS-\servidores\paper-26.3\mundo-afk`
   para `run/saves/spiketest` e arranquei com `--quickPlaySingleplayer`.
   **Falhou:** `Missing data pack paper` → `Failed to load level data or datapacks`.
   É um mundo de servidor Paper; o cliente não tem esse datapack. Não é recuperável.
2. `--demo` para usar um mundo pré-gerado. **Não arrancou mundo** (o build falhou
   antes, por causa do código principal ter voltado a `src/`).

Não há mais mundos 26.3 na máquina (`Server26_3_Fabric` também está vazio). Criar um
mundo válido exige o cliente a correr, que é o que não se pode observar sem um
humano. **Este passo fica para teste manual.** Decisão: a arquitectura do render já
está provada (API pública, sem mixins), o que falta é escolher o `RenderType`
correcto — um parâmetro, não um risco de arquitectura. Por isso **não bloqueia a
migração da GUI**.

### Bug encontrado no rename em massa (já corrigido)
O regex de `MinecraftClient` → `Minecraft` tinha lookbehind `(?<!\.)` para não
pegar em membros, mas isso também **impedia o match em `net.minecraft.client.MinecraftClient`**.
Resultado: o import ficou por renomear em 5 ficheiros (45 linhas com restos de Yarn).
Corrigido. Lição: imports precisam de ser tratados como caminho, com lookbehind
ou teste separado do rename de identificadores.

### Spike agora testável sem keybinds
Ao entrar num mundo, o spike larga **um quad fixo 3 blocos à frente do jogador**,
à altura dos olhos, virado para ele, e não o segue. Loga as coordenadas
(`QUAD PLACED at …`).

Para testar num mundo plano criativo:
1. aparece a página?
2. está espelhada ou invertida?
3. contornar o ecrã — vê-se a parte de trás? (diz se o RenderType faz cull)
4. `/time set midnight` — ainda se lê? (diz se há lightmap)

Usamos `RenderTypes.text(id)`, que tem lightmap. Se ficar escuro à noite, o
candidato é `entityTranslucentEmissive` (unlit + sem cull). A lista **verificada**
de `RenderTypes` que aceitam `Identifier` e que serve para isto:

```
text / textGrayscale / textSeeThrough / textPolygonOffset      (lightmapped)
entityTranslucent        entityTranslucent(,boolean)           (sem "Cull" = sem culling)
entityTranslucentEmissive                                         (unlit + sem cull)  <-- candidato
entityTranslucentCull    entitySolid     entityCutout           (com "Cull" = culling)
itemTranslucent  itemCutout  armorCutoutNoCull  armorTrim
beaconBeam(id,boolean)  endCrystalBeam  energySwirl(id,f,f)    (aditivos, depth off)
blockScreenEffect  fireScreenEffect  eyes  bannerPattern  outline
```

> Nota sobre nomes: os que **terminam em `Cull` fazem culling**, os outros não.
> Não inferir de nomes de outro version; esta lista saiu de `javap` no jar 26.3.

### Ciclo de vida da textura (requisito para o código final)
- `BrowserTexture.getTextureView()` **tem de pedir a view ao browser a cada draw**,
  sem cache. O MCEF Modern recria a `GpuTexture` quando o browser muda de tamanho,
  por isso uma view guardada fica obsoleta. Já implementado assim no spike.
- `BrowserTexture.close()` é **no-op**: a textura é do browser. O default de
  `AbstractTexture` fechá-la-ia — double free.
- Ao fechar um ecrã, `ScreenWorld.detach()` tem de fazer
  `TextureManager.release(id)` **antes** de `browser.close()`, senão o
  `AbstractTexture` registado fica a apontar para memória libertada.
  **Ainda de implementar** no código real.

Armadilha apanhada pelo caminho: `PoseStack.mulPose(Quaternionf)` **deixou de
existir** na 26.3. É `pose.rotateDegrees(Axis.YP, graus)`. Confirma a regra de
que nomes que ficam iguais mudam de assinatura.

## Licença (decidido 2026-10-14)
- CoScreen é **AGPL-3.0** (verificado no `LICENSE`, incluindo a cláusula §13 de
  interacção de rede remota).
- O ScreenMine copiou código dele **verbatim** (`BrowserInstance`, `PlaybackSync`,
  `AppState`, `StreamInfo`, `Packets`, `NetClient`, `Cfg`, `Settings`, `Lang`,
  `ScreenWorld`, `PlacedScreen`). É obra derivada.
- AGPL-3.0 §5(a) obriga a obra derivada inteira à mesma licença. **O MIT estava errado.**
- `LICENSE` = texto AGPL-3.0 verbatim. `NOTICE` = atribuição a Ma3stro567
  (tirado do `fabric.mod.json` do CoScreen, porque o `LICENSE` dele só tem o
  boilerplate do FSF e não identifica titular), ficheiros transportados, e a
  posição LGPL-2.1 do `mcef-modern`.
- `mcef-modern` é LGPL-2.1, mod separado, `compileOnly`. Nenhum código LGPL entra
  no nosso jar. Compatível com AGPL.

## Workflow de branches (decidido 2026-10-14)
NÃO voltar a apagar código da working tree para "estacionar".

| Branch | Conteúdo | Uso |
|---|---|---|
| `main` | port completo (25 ficheiros, não compila ainda) | trabalho normal |
| `spike/render` | só o spike (3 ficheiros, compila) | teste visual do quad em jogo |

Trocar: `git checkout spike/render` / `git checkout main`. O `git checkout`
repõe os ficheiros automaticamente — não há código em `%TEMP%`.

Para cirugias delicadas, correr com `OPENCODE_AUTOCOMMIT=0`.

## Registo de sessões
- **2026-10-14** — Repo novo `ScreenMine` criado (estava vazio, MIT © Mateus).
  Copiado o código do CoScreen-main (referência intocada). Identidade renomeada
  por inteiro. Build migrado para 26.3 (Loom 1.18, Gradle 9.7.1, Java 25, sem
  mappings). Rename mecânico em 13/22 ficheiros, com mapa de nomes confirmado por
  `javap` contra o jar real — `getRotationVector()` ficou de fora de propósito.
  `BrowserInstance` migrado para `mcef-modern` e a compilar. **Falta:** spike de
  render, GUI, input, render completo. Nada foi testado no jogo ainda.