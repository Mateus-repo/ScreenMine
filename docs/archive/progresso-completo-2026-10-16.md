# progresso.md — ScreenMine · 1.21.4 → 26.3

_Atualiza no fim de cada passo. Mantém curto e factual._

> Este é o **único** documento de estado do port. O antigo `PORTING-26.3.md`
> foi absorvido aqui e apagado, porque um segundo documento de plano passa a ser
> lido como fonte de verdade e tinha nomes ainda por confirmar.

## Estado atual
- **Fase:** 7 — Teste no cliente. **O port completo compila.**
- **Compila?** Sim. "BUILD SUCCESSFUL", 0 erros, 0 avisos.
- **Branch de trabalho:** `port/26.3`. A `main` e so o spike de render.
- **Corre no jogo?** O browser, o overlay e o input foram validados em jogo em
  2026-10-15. **Falta testar o port completo**, que nunca foi corrido.
- **Falta uma funcionalidade:** o roubo de input no mundo (roda/teclas em control
  mode) precisa de mixins. Ver "Funcionalidade pendente".
- **Ultimo commit:** ver git log

## Fases
- [x] 0. Reconhecimento
- [x] 1. Build a compilar contra MC 26.3 real
- [x] 2. Dependências confirmadas (mcef-modern substitui MCEF)
- [x] 3. Spike de render world-space - **A API compilava, mas nunca renderizou.** Ver "Render no mundo"
- [x] 4. Migracao GUI (7 dos 8 ecrans; o 8.o e o overlay do browser)
- [~] 5. Input - overlay validado; falta o roubo no mundo (mixins)
- [x] 6. Render completo (submit-node collector + BrowserTexture)
- [ ] 7. Teste no cliente  <- proximo passo
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
  ScreenMine e a licença é **AGPL-3.0-only** (c) Mateus — obra derivada do
  CoScreen, ver `NOTICE`. Uma linha mais abaixo (Licença, 2026-10-14) regista
  que o MIT aqui foi um erro e porque é que a AGPL-5(a) obriga a obra derivada
  inteira à mesma licença.
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

## Workflow de branches (decidido 2026-10-14, corrigido 2026-10-15)
NAO voltar a apagar codigo da working tree para "estacionar".

| Branch | Conteudo | Uso |
|---|---|---|
| `port/26.3` | **branch de trabalho**: port completo (25 ficheiros) | migrar ate compilar |
| `spike/browser-input` | browser migrado + harness de teste | bringr ficheiro a ficheiro |
| `main` | so o spike de render (3 ficheiros, compila) | nada a bringr |

**Ler a secao "Topologia das branches" abaixo ANTES de qualquer merge.**

Para cirugias delicadas, correr com `OPENCODE_AUTOCOMMIT=0`.

## Topologia das branches (verificada 2026-10-15) - IMPORTANTE

`git merge` entre estas branches **apaga o mod**. Nao usar.

```
9f25088  (merge-base: ultimo estado com o mod completo)
|
+-- dce000f -> f880b00 -> ... -> 4104f22            port/26.3        (7 commits)
|
+-- b44d94c "chore: keep main in a buildable state"  <-- AQUI o mod foi apagado
    +-- f11696b -> ... -> a920133                   spike/browser-input (20 commits)
    +-- fa7f977                                     main
```

- `b44d94c` removeu os 22 ficheiros do mod, deixando so o spike de render
  (3 ficheiros `.java`). `main` e `spike/browser-input` descendem dele.
- `port/26.3` ramificou de `9f25088`, **antes** dessa limpeza, e por isso
  conserva os 22 ficheiros (25 `.java` contando o pacote `spike/`).
- Consequencia: `git merge main` na `port/26.3` removeria 22 ficheiros
  (3137 linhas). `git merge spike/browser-input` faria o mesmo.
- **Como bring codigo da spike para a port: copiar ficheiro a ficheiro**
  (`git checkout spike/browser-input -- <ficheiros>`), nunca `git merge`.
- Tags de segurança: `backup/main-pre` (fa7f977), `backup/port-pre` (4104f22),
  `backup/spike-input-pre` (a920133).

### Conflitos esperados ao trazer o codigo da spike
- **Nao** trazer `lang/*.json` da spike: ela mudou para
  `key.category.screenmine.main` (uma `KeyMapping.Category` object), mas o
  `ScreenMineClient` por migrar ainda regista keybinds com categoria por
  string (`key.categories.screenmine`). Trouxe a chave antiga e a tab ficaria
  sem rotulo.
- **Nao** trazer `logo/*` nem `icon.png`: a `port/26.3` ja os tem.
- `BrowserFullScreen` vinha com uma referencia a
  `dev.screenmine.spike.SpikeBrowserClient.browserReady()`. Substituida por
  `screen.browser.ensureCreated()`, que e idempotente e distingue os mesmos
  dois estados (Chromium a arrancar vs browser a carregar) sem depender do spike.


## Contagem de erros (medida com `-Xmaxerrs 1000`)

**BUILD SUCCESSFUL. 0 erros, 0 avisos.**

| Momento | Total |
|---|---|
| antes de bringr o browser | 434 |
| depois de bringr o browser | 348 |
| depois de `ScreenMineClient` | 81 |
| depois de `ScreenWorld` + `PlacedScreen` | 69 |
| depois de `Hud` | 50 |
| depois de `WorldScreenRenderer` | **0** |

Por ficheiro, ao longo do caminho:
`ScreenMineClient` 172 -> 0 · `WorldScreenRenderer` 50 -> 0 · `Hud` 19 -> 0 ·
`ScreenWorld` 8 -> 0 · `PlacedScreen` 4 -> 0

## API 26.3 confirmada com `javap` (todas verificadas, nenhuma adivinhada)

- `Minecraft`: `getSession().getUsername()` -> `getUser().getName()`;
  `getCurrentServerEntry()` -> `getCurrentServer()` (`multiplayer.ServerData`,
  `.address` -> `.ip`); `currentScreen` -> `gui.screen()`; `setScreen` ->
  `gui.setScreen`; `inGameHud` -> `gui.hud` + `getChat().addClientSystemMessage`;
  `world` -> `level`; `font`; `mouseHandler`/`keyboardHandler` (public final).
- `Options`: `attackKey`/`useKey` -> `keyAttack`/`keyUse`;
  `getFinalSoundSourceVolume` inalterado; **`hudHidden` desapareceu**, sem
  substituto.
- `KeyMapping`: construtor com `KeyMapping.Category` (o de `String` foi
  removido); `setPressed` -> `setDown`; `Category.register(Identifier)`.
  `Category.label()` deriva `key.category.<ns>.<path>`.
- Fabric: `KeyMappingHelper.registerKeyBinding` -> **`registerKeyMapping`**.
- `InputConstants`: `GLFW.GLFW_KEY_*` -> `KEY_*`; `PRESS`/`RELEASE`;
  `Key.getCode()` -> `getValue()`.
- `Vec3`: `dotProduct` -> `dot`; `multiply(double)` -> `scale`.
- `Entity`: `getYaw` -> `getYRot`; `getEyePosition`, `getLookAngle` inalterados.
- `ResourceKey.identifier()` (nao `location()`), para a dimensao.
- GUI: `GuiGraphicsExtractor.text(font, ...)` e `centeredText(font, ...)`
  (esta ultima ja faz sombra sempre); `guiWidth()`/`guiHeight()`.
- `Component.formatted` -> `withStyle`.
- Render: `WorldRenderEvents` e `HudRenderCallback` **nao existem** em 26.3.
  Substitutos: `LevelRenderEvents.END_MAIN` e `HudElementRegistry.addLast(id, el)`.
- `Window.getHandle()` -> `handle()`; `MouseHandler.isLeftPressed()/isRightPressed()`.

## Render no mundo (resolvido, o unico bloco grande)

`Tessellator`, `BufferBuilder` e `BufferRenderer` foram **eliminados** em 26.3, e
o estado de render saiu do `RenderSystem`. Nao dava para portar linha a linha.

A via que funciona, toda ela API publica, sem mixins nem access widener:
1. `BrowserTexture extends AbstractTexture` devolve o `GpuTextureView` vivo do
   browser. Registado com `TextureManager.register(Identifier, ...)`, porque os
   fabricos de `RenderTypes` so aceitam um id.
2. A geometria vai pelo `ctx.submitNodeCollector().submitCustomGeometry(...)` do
   `LevelRenderContext`.
3. `close()` do `BrowserTexture` fica vazio de proposito: o default fecharia um
   `GpuTexture` que pertence ao browser.

Isto veio do spike de render. **CORRIGIDO 2026-10-15:** o spike nao renderizou nunca. Usava a mesma chamada parcial de vertice que crashou no primeiro teste do port, por isso so estava provado que compilava, nao que desenhasse. O caminho da API public era o certo; a chamada dos vertices nao.

## Funcionalidade pendente (TODOs no codigo)

| Onde | O que falta | Porquê |
|---|---|---|
| `ScreenMineClient` `installInputHooks` | **Roubo de input no mundo** (roda e teclas) | 1.21.4 encadeava as callbacks de janela do GLFW. Em 26.3 nao ha GLFW (SDL3) e nao existe API publica para interceptar input antes do Minecraft. Precisa de mixins em `KeyboardHandler.keyPress`/`charTyped` e `MouseHandler.onScroll`. Os handlers (`onScroll`/`onKey`/`onChar`) ja estao escritos contra a API do `MCEFBrowser`. **E a unica funcionalidade em falta.** |
| `WorldScreenRenderer:87` | Picture escurece a noite | `RenderTypes.text` e lightmapped. O spike lista `entityTranslucentEmissive` como alternativa unlit. Decisao visual. |
| `WorldScreenRenderer:120` | Formato de vertices de `RenderTypes.debugQuads` | Construido por supplier lazy, por isso o `javap` nao confirma. E o que desenha a placa de fundo e a moldura. Se a moldura sair invisivel ou crashar a render thread, e esta a linha. |
| `ScreenMineClient:108` (aceito) | Desenhar depois das nuvens | `WorldRenderEvents.LAST` nao existe em 26.3. `END_MAIN` e o mais proximo e desenha antes de nuvens e meteoro. Regressao aceite, verificada contra a lista de eventos. |

## Como testar

```
cd <repo>          # a raiz do ScreenMine
.\gradlew.bat runClient
```

Checklist completa em baixo.

## Checklist de teste (consolidada)

**Ecrã no mundo**
- [ ] O quad aparece no mundo, virado para o jogador
- [ ] A imagem nao esta espelhada nem invertida
- [ ] A placa de fundo e a moldura aparecem
- [ ] De costas: o quad desaparece ou nao (diz se o cull esta activo)
- [ ] `/time set midnight`: a imagem ainda se le? (ver `TODO(port)` das 87)
- [ ] Neve/chuva na tela: a pagina fica coberta (regressao aceite)
- [ ] Clicar num link dentro do mundo abre o overlay

**Input**
- [ ] `M` abre o menu, `B` abre o overlay, `C` entra em control mode
- [ ] No overlay: escrever texto, clicar, scroll, Shift
- [ ] Backspace apaga (Bug A1 - continua por resolver)
- [ ] Ctrl+V cola (Bug A2)
- [ ] **Bug A3:** `Alt+seta` esquerda/direita: voltar/avancar
- [ ] **Bug A4:** arrastar com o rato **seleciona texto** (confirmar com `Ctrl+C`)
- [ ] **Control mode:** o rato move/clica no browser; **roda e teclas NAO chegam** (`TODO(port)` dos mixins)
- [ ] WASD continua a andar enquanto control mode esta ligado
- [ ] `Esc` sai de control mode e nao abre o menu do jogo

**Bug B - tecla N (mutar)**
- [ ] `N` muta o audio (dizer **onde** foi testado: overlay / frente ao ecra no
      mundo / com video a tocar)

**Conteudo**
- [ ] YouTube real: `?v=` carrega, play/pause, seek, volume
- [ ] **Bug C:** `<my-server>` reproduz video
- [ ] **Bug C:** TikTok / MedalTV reproduzem video
- [ ] **Bug C:** a sonda de codecs faz log em `latest.log`
      (`debug.codecs=1`, logger `ScreenMine/codecs`)
- [ ] Mudas de browser: a URL e partilhada com a sessao

**Colocacao**
- [ ] `G` pega no ecra, 4 cliques WBC numa parede, move-se com a mira
- [ ] `=`/`-` redimensiona

**Audio**
- [ ] Volume segue a distancia e o master do Minecraft

**Menus (9 telas)**
- [ ] Menu principal, amigos, convites, controlos, streams, escolha de join, entrada de URL

**Rede** (precisa de 2 jogadores)
- [ ] Sincroniza play/pause e a posicao entre os dois
- [ ] Um ve o ecra do outro

**Baixo nivel**
- [ ] Build green: `./gradlew.bat build` -> `BUILD SUCCESSFUL`, 0 erros, 0 avisos
- [ ] `push` da `port/26.3` sem timeout

## Estado dos testes em jogo (running log)

Cada corrida em jogo é um teste. Registo aqui o que cada uma provou, porque
ate ao momento o build verde escondia tres bugs de render.

| Corrida | Resultado | Ensina |
|---|---|---|
| spike/render | compilou, **nunca renderizou** | um spike verde nao prova que desenha |
| 1o teste do port (11:28) | crash `Missing elements in vertex` | faltavam atributos no vertice |
| 2o teste do port (11:39) | crash `textureView and sampler must both or neither be null` | o sampler tinha de seguir a vista |
| 3o teste do port (11:38-12:11) | **`BUILD SUCCESSFUL`, ecra no mundo desenhado** | o render esta provado; falta o input |
| 4o teste (12:41-12:43) | sem `ONLOADEND` no log | **agente nunca injectado** -> causa raiz do N e do shim de edicao (`codecs.md` §5b) |
| 5o teste (12:47-12:50) | `AGENT injected by retry` — o agente **ja corre no mundo**; a sonda nao reportou | corrigiu o bug A; expoe que o meu canal (`document.title`) perdia a corrida |
| 6o teste (12:55-12:56) | **tabela de codecs obtida**; `GAIN ... __smVol=0.000` confirmado | bug C **resolvido**; bug B instrumentado |
| **7o teste — servidor multiplayer (23:40-23:49)** | **YouTube toca e o `N` MUTA** | bug B **resolvido, com prova no log**; bug A **continua aberto** |

### Corrida 7 (23:40-23:49, servidor multiplayer, Modrinth) — a mais informativa

Perfil `Fabric 26.2 (1)` do Modrinth App, ~120 mods (iris, bluemap, c2me,
voicechat, journeymap, litematica, jei, malilib…), `mcef-modern
0.3.3+mc26.3.jcef146.0.10` e `screenmine 1.0.0` instalados à mão.

**Confirmado em jogo, com prova no log e não por impressão:**

| O que | Prova |
|---|---|
| YouTube reproduz no mundo, num servidor | `agent report: media=2 … href=https://www.youtube.com/watch?v=j-RpvIuazmc` |
| **O `N` muta mesmo** | `23:48:30 agent report: vol=0, media=2, vvolume=0.00` — a Java mandou 0 e o `<video>` real ficou a `0.00` |
| Os mixins instalam sem partir o arranque | o cliente arrancou com `SpongePowered MIXIN Subsystem Version=0.8.7` e sem erro de mixin |
| O agente entra em **iframes** | `agent report: … top=iframe href=https://accounts.google.com/ServiceLogin…` — só é possível depois de `1e3a570` |

Isto **encerra o bug B** (tecla `N`) e mostra que o A2 (agente em todos os
frames) foi o que resolveu.

**Continua aberto, e o log di-lo sem ambiguidade:**

```
SM| channel alive  →  NENHUM
```

**Zero recibos `SM|`**, mesmo depois da correcção do título (`376d7d4`). Logo
`isEditing()` continua sempre `false`, e portanto **os mixins do mundo não
encaminham as teclas** — por muito bem instalados que estejam. O bug A (teclas
de edição) **não** está resolvido.

**Bug meu, encontrado por esta corrida:** o watcher de frames re-injectou **23
vezes seguidas** (`INJECT 26`, `GAIN 64` para um ecrã). `knownFrames` só era
posto quando `extra > 0`, portanto sem subframes ficava em `0` enquanto se
comparava com `getFrameCount()` (=1) — mudança, a cada 2 s, para sempre. Cada
re-injecção fazia reset de `appliedGain`, daí as 64 linhas de ganho. Corrigido
em `5ced97f`.

**Ainda por testar em jogo:** o menu novo (`108c275`, lista de ecrãs), escrever
num campo de texto, `Ctrl+*`, `Alt+seta`, a roda no mundo, e a queda de volume
por distância.

### Corrida 2 (11:39) - detalhes
- **Confirmado em jogo:** a aba de teclas mostra **"ScreenMine"** (a correcao dos
  `lang` funcionou e nunca mais precisa de se mexer); o browser arranca
  (`CREATE url=https://www.youtube.com surface=1920x1080`).
- **A correcao dos vertices funcionou:** o crash anterior desapareceu.
- Novo crash, no `executeClassicTransparency`. Causa: `BrowserTexture.getSampler()`
  devolvia sempre o sampler, mas `getTextureView()` e legitimamente `null` antes do
  primeiro frame, depois de um resize e depois de `close()`. Como a geometria e
  submetida com o browser vivo e desenhada instantes depois, fechar o browser no
  meio (o `detach()` faz `browser.close()`; e o mesmo acontece ao sair do mundo)
  deixava vista `null` com sampler posto.
- Sintomas que explicava de uma vez: o ecra "desaparecer" e o crash ao remover
  pelo menu.
- Correcao: `getSampler()` devolve `null` sempre que a vista e `null`. E
  `TextureManager.release(id)` quando um ecra e detached.

### Corrida 3 (11:38-12:11) - **O RENDER ESTA PROVADO**

Screenshot `run/screenshots/2026-10-15_12.10.33.png`: um ecra no mundo 3D com uma
pagina real (MedalTV) a ser desenhada.

Verificado **em jogo** nesta corrida:
- O quad aparece no mundo, **nao esta espelhado** (o texto le-se ao contrario
  seria `MedalTV` invertido; le-se normal).
- Esta **iluminado** (o lightmap full-bright fez o efeito).
- **Nao desapareceu** e **nao se multiplicou** depois das correcoes de camera.
- `B` funciona (abre/fecha o overlay), `N` continua a nao fazer nada.
- YouTube reproduz.
- `BUILD SUCCESSFUL`, sem crash.

Isto fecha o bloco de maior risco do port. As duas correcoes que oarziam
funcionaram: `d600b7d` (camera subtraida uma vez so) e `9664974` (sampler segue
a vista).

## Bugs conhecidos (reportados pelo utilizador em 2026-10-15)

Estado: **funciona** -> tecla B, ecra no mundo, YouTube, spawn, volume.

### A) INPUT DE EDICAO - PRIORIDADE 1

Nao funciona **no overlay (B) e no control mode do mundo**:
`Ctrl+A`, `Ctrl+C`, `Ctrl+V`, `Backspace`, `Enter`, seleccionar texto com o rato
para copiar, `Alt+seta` esquerda/direita (voltar/avancar).

**Hipotese a PROVAR (do utilizador, nao confirmada):** o Minecraft so dispara
`charTyped` para caracteres imprimiveis. `Enter` (13), `Backspace` (8) e `Tab` (9)
nunca geram o evento `CHAR` que o CEF offscreen espera
(`RAWKEYDOWN` -> `CHAR` -> `KEYUP`). Isso explica "a tecla chega mas nao faz
nada": o `keyPressed` passa, o `CHAR` falta.

Caveat do registo: **isto e hipotese**, e do raciocinio sobre o Minecraft, nao
sobre o codigo do `mcef-modern`.

**Ja verificado com `javap` (ver `codecs.md` §5):**
1. `MCEFBrowser.onKeyPressed` envia AWT `KEY_PRESSED` (401), `onCharTyped` envia
   `KEY_TYPED` (400), `onKeyReleased` `KEY_RELEASED` (402). Todos para
   `CefBrowser_N.sendKeyEvent`, que e `protected final` e delega imediato para
   `N_SendKeyEvent`, **nativo**.
   => **o passo final e nativo: nao e possivel dizer pelo `javap` se o JCEF
   sintetiza o `CHAR`. A hipotese fica aberta, nem provada nem refutada.**
2. `KeyboardHandler` tem `keyPress(long,int,KeyEvent)` e
   `charTyped(long,CharacterEvent)`. Quem chama `charTyped`, e para que teclas,
   esta na camada window/SDL3 e **ainda nao foi verificado**.
3. **`CefFrame` tem `selectAll/copy/cut/paste/undo/redo`** e `CefBrowser` tem
   `canGoBack/goBack/canGoForward/goForward`. Portanto **A2 e A3 sao
   implementaveis com API publica, sem mixins e sem shim JS.**
4. O `BrowserFullScreen` **ja faz log** de cada evento (`ScreenMine/input`:
   `IN keyPressed`, `IN charTyped`, ...). O teste de §"2 minutos" em `codecs.md`
   §6 precisa de zero codigo novo.

Atalhos `Ctrl` a implementar explicitamente (o CEF offscreen nao os executa
sozinho): `Ctrl+A` selectAll, `Ctrl+C` copy, `Ctrl+X` cut, `Ctrl+V` paste,
`Ctrl+Z`/`Ctrl+Y` undo/redo, `Alt+esquerda/direita` goBack/goForward (com
`canGoBack`/`canGoForward`). `Ctrl+C`/`Ctrl+V` tem de usar o clipboard do
**sistema**, nao um interno do browser.

Seleccao de texto: arrastar com botao esquerdo tem de enviar movimento **com o
modificador premido** (`EVENTFLAG_LEFT_MOUSE_BUTTON` ou equivalente), senao o CEF
nao faz selecao. Verificar `mouseDragged`, duplo clique e triplo clique.

Teste: pagina local com `createBrowser` + `data:` URL, a registar
`keydown`/`keypress`/`input` (com `key`, `code`, `keyCode`), um `textarea` longo,
`window.getSelection()` e o valor do campo apos cada accao. **Tabela por tecla.**

**PRIMEIRO, o teste que nao precisa de codigo** (`codecs.md` §6): com o overlay
aberto, numa caixa de texto, prima `Backspace` e `Enter`, e veja o `latest.log`:

```powershell
Select-String "IN keyPressed|IN charTyped" run\logs\latest.log | Select -Last 20
```

`IN keyPressed` **sem** `IN charTyped` => hipotese confirmada. Os dois juntos =>
o problema esta a jusante, no JCEF/CEF.

### B) TECLA N (MUTAR) - **RESOLVIDO E PROVADO EM JOGO** 2026-10-16

> **RESOLVIDO.** Na corrida 7 (servidor multiplayer) o `N` muta mesmo, e o log
> prova-o: `23:48:30  agent report: vol=0, media=2, vvolume=0.00`. A Java mandou
> zero e o `<video>` real ficou a `0.00`.
>
> **O que o resolveu foi o A2** (`1e3a570`, agente em todos os frames), não o
> A1. O mesmo log mostra o agente dentro de `top=iframe` (Google accounts), o
> que antes era impossível.

**O que a corrida das 12:55 provou sobre este bug:**

```
[12:55:31] GAIN local-... -> __smVol=0.700
[12:55:32] agent report: vol=0.7|media=0|vvolume=-|...|edit=1
[12:55:43] Screen: sound off
[12:55:43] GAIN local-... -> __smVol=0.000
```

Duas coisas deixam de ser hipotese:
1. `edit=1` e `vol=0.7` provam que **o agente esta dentro da pagina** e que o valor
   da Java **chega** (`window.__smVol`). A correccao do bug A resultou.
2. `GAIN ... __smVol=0.000` prova que o `N` **manda mesmo o zero** para fora da
   JVM. O `applyGain` nunca esteve errado.

Logo o que falta e o ultimo troco: o `setInterval` do agente a escrever
`e.volume = 0`. A leitura unica de `media=0` chegou **1,5 s depois da injecao**,
antes de o YouTube ter criado o player — por isso `vvolume=-` e nao diz nada.

**Instrumentacao nova:** o auto-relatorio passou a sair **a cada mudanca** do numero
de elementos ou de `__smVol` (em vez de uma vez), para a transicao interesante
`0 -> 1` nao poder escapar. Passa a mostrar `vvolume` com o video existente.
Foi esta instrumentacao que resolveu o caso na corrida 7.

### C) VIDEO EM OUTROS SITES - PRIORIDADE 2 - **CAUSA CONFIRMADA**

> **RESOLVIDO a 2026-10-15 12:55.** A sonda correu dentro da pagina e deu:
>
> ```
> avc1.42E01E.canPlayType=-            H.264       ✗
> avc1.64001F.canPlayType=-            H.264 high  ✗
> mp4a.40.2.canPlayType=-              AAC         ✗
> hev1.canPlayType=-                   HEVC        ✗
> vp09.00.10.08.canPlayType=probably    VP9         ✓
> av01.0.05M.08.canPlayType=probably   AV1         ✓
> opus.canPlayType=probably            Opus        ✓
> vorbis.canPlayType=probably          Vorbis      ✓
> MediaSource=yes
> ```
>
> `canPlayType` devolve `""` (impresso como `-`) quando o codec nao existe. Logo:
> **o Chromium do `mcef-modern` nao tem H.264, nem AAC, nem HEVC.** Nao e
> hipotese, e o log. Detalhe em `codecs.md` §1b.
>
> A investigacao estatica anterior (proveniencia do binario, ausencia de
> `proprietary_codecs`) **nao prova nada sobre isto** — provou que ninguem liga os
> codecs, nao que eles faltam. Faltava o runtime, e o runtime correu.

Explicacao do padrao, agora com base:
- youtube.com ✅ — serve **VP9 + Opus**, ambos livres.
- medal.tv / tiktok.com / o servidor do utilizador ❌ — servem **H.264 + AAC**.

`MediaSource=yes` e `mse=yes` para VP9/AV1 provam que a pipeline existe; falta-lhe
o demultiplexer de MP4 com H.264.

**Nenhum caminho dentro do mod resolve isto.** Custos reais em `codecs.md` §3e:
(a) transcodificacao no servidor para VP9/AV1 + Opus — zero trabalho no mod,
zero problema legal; (b) build propria do JCEF — 200-300 GB, horas, area cinzenta
de patentes; (c) descodificacao so por hardware — precisa da mesma build; (d)
libvlc/ffmpeg para a mesma textura — grande.

**Proximo passo consoante a opcao:** se for (a), so mexer no servidor. O mod nao
precisa de nada.

Do mesmo log, duas leituras secundarias a guardar para mais tarde:
- `ua=... MCEF-Modern/0 Safari/537.36` — o browser **denuncia-se**. Sitios com
  deteccao de bot podem servir outra coisa. Guardado para quando o codec nao for a
  explicacao.
- `secureContext=no` — a sonda foi injectada em `about:blank`, nao na pagina final.
  Explica `media=0` nesse instante (o YouTube ainda nao tinha criado o player).

Investigacao completa e proveniencia do binario: `codecs.md` §2, §3, §3b-3e.

Como foi provado, e o que ficou registado para nao se repetir o trabalho:
- A sonda (`dev.screenmine.diag.CodecProbe`, ligada com `debug.codecs=1`) mede
  `canPlayType` e `MediaSource.isTypeSupported` para os 8 codecs de origem, mais
  `navigator.userAgent` e `isSecureContext`.
- Ela **nao** passa pelo `document.title`, como estava escrito antes. O agente de
  pagina reescreve o titulo a cada 250 ms e atropelava o relatorio — a corrida das
  12:47.logou "codec probe ON" e nada mais. Vai agora pelo `console`, que o
  `CefDisplayHandler.onConsoleMessage` recebe. Assinatura verificada com `javap`:
  `(CefBrowser, CefSettings.LogSeverity, message, source, int) -> boolean`.
- (a) de onde vem o binario do JCEF do `mcef-modern` - **JA VERIFICADO**:
  `jcefmaven/jcefbuild` release `1.0.70`, sem nenhum argumento
  `ffmpeg_branding`/`proprietary_codecs` em `compile_windows.bat`,
  `run_windows.bat`, no workflow, no Dockerfile nem no `CMakeLists.txt` do JCEF.
  Ver `codecs.md` §3.
  - **JA VERIFICADO: nao existe variante com codecs nessa origem.** Maven Central
    `me.friwi` tem 14 artefactos e a familia `jcef-natives-*` e so por plataforma
    (sem `-chrome`, sem `-proprietary`). O `mcef-modern` tambem **nao tem API**
    para apontar para outro binario (`MCEFApi` so tem `initialize/getInstance/
    createBrowser`). So por disco, e `checkInstallation` **nao verifica hashes**.
  - O thread `cinemamod/mcef#28` foi lido: "irrelevante com MCEF 2.x" quer dizer
    que os binarios do **CinemaMod** ja vem com codecs, nao que o JCEF passou a
    trazer. O `CinemaMod-Fabric` esta em 404. Ver `codecs.md` §3b-3d.
- (b) custo realista das alternativas (build propria do CEF, outro fork do MCEF,
  ou descodificacao nativa tipo libvlc/ffmpeg para a mesma textura), com custo,
  risco e licenciamento — **JA ESTIMADO**, ver `codecs.md` §3e.
  **H.264 tem questoes de patentes** - hoje e uso pessoal, mas fica registado para
  uma eventual publicacao.

Causas que **nao** são codecs continuam na lista, para quando a transcodificacao
resolver o video e aparecer outro sintoma: (ii) certificado/HTTPS (certificado
invalido, ddns), (iii) cookies / sessao / login persistente entre sessoes do jogo,
(iv) autoplay, (v) controlo por teclado/rato.

### C-bis) O TEU SERVIDOR (Jellyfin) — há caminho, e é mais barato do que eu disse

**O que estava errado no que eu assumi.** Escrevi que "o teu servidor serve
H.264/AAC". Os teus dados mostram que **o ficheiro de teste é AV1 + AAC**, não
H.264. O Chromium do mod **toca AV1** (`av01.0.05M.08 = probably`); o que falha
é **só o AAC**. Neste ficheiro **não é preciso transcodificar o vídeo** — só o
áudio. Isso muda o custo de "pesado" para "barato".

**O teu servidor:**

| | |
|---|---|
| Versão | Jellyfin **10.11.3** (server / web / build) |
| Sistema | Windows, `C:\Program Files\Jellyfin\Server` |
| Aceleração | **None** (só software) |
| Codificação AV1 | **desligada** |
| Codificação HEVC | **desligada** (não faz falta — o mod não toca HEVC) |
| `jellyfin-ffmpeg` | tem **`libsvtav1`**, **`libopus`**, **`libvpx-vp9`** |
| Encoders de hardware | listados, mas só servem com a GPU certa |

**O que fazer, uma coisa de cada vez:**

1. **Dashboard → Reprodução → Transcodificação → ativa "Allow encoding in AV1
   format".** É para os ficheiros H.264. Deixa o HEVC desligado.
2. **No Jellyfin dentro do mod → Definições → Reprodução → Vídeo avançado →
   "Preferred transcode video codec"**: põe **AV1**, não *Auto*. O *Auto*
   pode estar a escolher AAC.
3. **Áudio**: escolhe **Opus** se aparecer na lista.
4. Testa **primeiro com o ficheiro MedalTV** — o vídeo já é AV1, por isso só o
   áudio é transcodificado.

**Limite:** AV1 por software em tempo real é pesado. Encoders AV1 por hardware
existem em NVIDIA RTX 40+, Intel Arc e AMD RX 7000; anteriores não têm.

**Ainda por confirmar:** o log do servidor **não tem nenhuma linha de
transcodificação** — avaliou o pedido (`EnableVideoPlaybackTranscoding: True`,
ligação remota, limite 50 Mbps) e a reprodução parou aos 0 ms. Parece que não
encontrou caminho compatível com o que o cliente declarou, mas isso é inferência
a partir de um excerto curto. Para fechar: log em **Debug** e repetir.

**TikTok, Twitch e MedalTV: continua sem solução.** Não há servidor que
controles, logo não há transcodificação possível. Só uma build própria do JCEF.

**Alerta de segurança — o token.** O log do Jellyfin que partilhaste tem o teu
IP público, nome de utilizador e um **token de sessão**. Não o publiques. **Faz
logout e login de novo** para o invalidar. Viste também um `PublicUser`: se for
uma conta partilhada, confirma que não tem permissões de administrador.

**DRM (Netflix, Disney+) nao vai funcionar por esta via.**

---

## Segurança e privacidade (revisitado 2026-10-16)

### O teu servidor estava no repo público

**Verificado, não suposto:**

```
codecs.md:20      https://<my-server>   (já removido)
progresso.md:421  https://<my-server>   (já removido)
```

Estava em **três commits** (`13af2e5`, `1e74d36`, `1ece4e3`) num repositório
público. Um hostname `ddns.net` publicado resolve-se para o IP de casa. **Já
está exposto** — removê-lo dos ficheiros actuais não o desexpoe.

- **Feito:** substituído por `<my-server>` em `codecs.md` e `progresso.md`.
- **Por decidir (precisa da tua autorização):** reescrever o histórico com
  `git filter-repo` para o tirar dos commits. É destrutivo e reescreve hashes;
  não o faço sem o teu OK explícito.

### `run/` — verificado limpo

```
$ git check-ignore -v run/logs/latest.log
.gitignore:4:run/   run/logs/latest.log
$ git ls-files | grep '^run/'      → vazio
```

`run/` já está no `.gitignore` e não há um único ficheiro de `run/` versionado.
Sem logs, sem cookies do browser, sem sessões. **Esta preocupação não se
confirmou.**

### Dívida de segurança que ainda existe

| Risco | Gravidade | Nota |
|---|---|---|
| **Protocolo sem autenticação** | **alta** para publicação | `hello` leva só `name` + `ver`. Qualquer um que alcance a porta 23481 pode injectar `navigate` nos ecrãs de outros. Aceitável numa LAN; bloqueador para publicar. |
| **Browser sem allowlist** | média | Um browser embebido abre o que lhe derem. Considerar lista de URLs permitidos nas definições. |
| **`file://` não bloqueado** | média | Se o stream vier de outro jogador, o URL é remoto; ainda assim, bloquear `file:` é uma linha. |
| **Cookies partilhados** | média (feature, mas também risco) | `createBrowser` usa `CefRequestContext.getGlobalContext()` — todos os ecrãs partilham cookies. Entrar no YouTube num ecrã entra em todos. É útil para "ver juntos" e é uma fuga de privacidade. |

### Token do Jellyfin

O log que partilhaste tem **token de sessão**. Faz logout/login para o
invalidar. Não o publiques.

---

## O que falta para "funcionar tudo", por ordem

| # | Item | Estado | Esforço |
|---|---|---|---|
| 1 | **Canal `SM|` vivo** | ❌ zero recibos, mesmo depois de `376d7d4` | diagnóstico primeiro: `5ced97f` conta todos os `onTitleChange` |
| 2 | **Teclas de escrita no mundo** | ⛔ bloqueadas pelo #1 (`isEditing()` sempre `false`) | depende do #1 |
| 3 | Menu de vários ecrãs | ✅ feito (`108c275`), **por testar** | — |
| 4 | `Ctrl+*`, `Alt+seta` | ✅ feito (`9918753`), **por testar** | — |
| 5 | Roda no mundo | ✅ mixins feitos (`8c11586`), **por testar** | — |
| 6 | **Jellyfin** | caminho identificado, falta activar AV1 do lado do servidor | 4 passos |
| 7 | TikTok / Twitch / MedalTV | ⛔ sem solução sem build própria do JCEF | dias + 200 GB |
| 8 | Sonda alargada (áudio, contentores, `error.code`) | ❌ por fazer | pequeno |
| 9 | README, CI, CHANGELOG, release alpha | ❌ por fazer | médio |
| 10 | Auth do protocolo, allowlist, `file://` | ❌ por fazer | médio |

**O item 8 é o próximo passo natural e é pequeno:** alargar a sonda com
`audio/mpeg`, `audio/flac`, `video/mp4; codecs="av01.0.05M.08,mp4a.40.2"`,
`video/webm; codecs="vp9,opus"` e `video/webm; codecs="av01.0.05M.08,opus"`, e
**capturar `video.error.code`** quando um vídeo falha. O par AV1+AAC e o par
VP9+Opus dizem exactamente se o alvo de transcodificação do Jellyfin vai
resultar, e o `error.code` diz porque é que um vídeo não toca.

---

## Registo de sessoes
