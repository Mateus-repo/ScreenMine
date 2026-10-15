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

### Conflitos预期idos ao bringscodigo da spike
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
cd C:\Users\Strefiz\Documents\GitHub\ScreenMine
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
- [ ] Backspace apaga (continua por resolver: shim JS, agente so corre se o `pumpAgent` disparar)
- [ ] Ctrl+V cola
- [ ] **Control mode:** o rato move/clica no browser; **roda e teclas NAO chegam** (`TODO(port)` dos mixins)
- [ ] WASD continua a andar enquanto control mode esta ligado
- [ ] `Esc` sai de control mode e nao abre o menu do jogo

**Conteudo**
- [ ] YouTube real: `?v=` carrega, play/pause, seek, volume
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

## Registo de sessoes
- **2026-10-15 (tarde)** - **Port completo a compilar.** 434 -> 0 erros, 0 avisos.
  Ordem: browser da spike -> `ScreenMineClient` (172->0) -> `ScreenWorld` + `PlacedScreen` (12->0)
  -> `Hud` (19->0) -> `WorldScreenRenderer` (50->0). Render no mundo resolvido pelo
  caminho do spike (`BrowserTexture` + `TextureManager.register` + `submitCustomGeometry`),
  so com API publica. **Correcao de uma decisao anterior:** os `lang` precisavam mesmo da
  chave `key.category.screenmine.main`, porque 26.3 removeu o construtor de `KeyMapping`
  com `String` - sem isso a aba dos keybinds ficava sem rotulo.
- **2026-10-15 (noite)** - **Primeiro teste do port completo: crashou.** E a primeira
  vez que o port correu, e imediatamente provou que "build verde" nao era "funciona".
  - Confirmado em jogo: a aba de teclas mostra **"ScreenMine"** (a correcao dos `lang`
    funcionou) e o browser arranca (`CREATE url=https://www.youtube.com surface=1920x1080`).
  - Crash: `IllegalStateException: Missing elements in vertex` em
    `WorldScreenRenderer.lambda$drawScreen$0`. Causa: so passava posicao/UV/cor ao
    vertice; o formato do `RenderTypes.text` pede mais elementos.
  - **O spike tinha o mesmo bug.** Portanto nunca renderizou - so compilava. A
    afirmacao de que estava "provado em jogo" estava errada e foi corrigida acima.
    Licao: um spike verde nao prova que desenha nada.
  - Correcao sem adivinhar o formato (e lazy, o `javap` nao o diz): no bytecode,
    `BufferBuilder.setX` chama `beginElement`, que devolve `-1L` para um elemento
    fora do formato, e o setter salta a escrita. Logo pode-se preencher sempre todos
    os atributos. `vertex()` faz cor + UV + overlay + light + normal.
  - Isso fechou os dois `TODO(port)` do `WorldScreenRenderer`: o formato do
    `debugQuads` deixou de ser uma aposta, e o lightmap full-bright deixa de
    escurecer a imagem a noite. `TODO(port)` no ficheiro: 2 -> 0.
  - **Resta um `TODO(port)` em todo o mod:** o roubo de input no mundo.
