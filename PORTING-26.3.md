# Plano de Migração — CoScreen 1.21.4 → Minecraft 26.3

> Estado: plano escrito em 2026-10-14. Nada foi alterado no código ainda.
> Superfície: 21 ficheiros Java, 4024 linhas, cliente-only, Fabric.

---

## 1. Veredicto em uma frase

Isto **não é uma subida de versão**: é uma reescrita das camadas de *render* e
*input* mais um renome mecânico de todo o resto. A lógica de negócio
(rede, sincronização de playback, estado, i18n) sobrevive quase intacta.

Três quebras justificam tudo o resto:

| # | Mudança em 26.x | Impacto no CoScreen |
|---|---|---|
| 1 | **Yarn foi descontinuado.** 26.1+ é a primeira versão *não ofuscada*; o jogo usa os nomes oficiais da Mojang em runtime | Todo o código usa nomes Yarn → renomear tudo |
| 2 | **GLFW foi removido, substituído por SDL3** | `CoScreenClient` faz *chaining* de callbacks GLFW → reescrever |
| 3 | **RenderSystem perdeu o estado de render** (blend/depth/cull/shader/textura) para `RenderPipeline` + `com.mojang.renderpearl` | `WorldScreenRenderer` é reescrito de raiz |

E há um bloqueador externo: **o MCEF oficial morreu em 1.21.4**.

---

## 2. Dependências — valores alvo

| | Atual (1.21.4) | Alvo (26.3) | Nota |
|---|---|---|---|
| Minecraft | 1.21.4 | **26.3** | "Wilderness Bound", 15/Set/2026 |
| Mappings | `net.fabricmc:yarn:1.21.4+build.8:v2` | **nenhum** | Loom já não remapeia |
| Loom | `fabric-loom` 1.9.2 | **`net.fabricmc.fabric-loom` 1.18-SNAPSHOT** | O plugin novo, que *não* remapeia |
| Gradle | ? | **9.7.1** | via wrapper |
| Loader | 0.16.9 | **0.19.5** | |
| Fabric API | 0.119.4+1.21.4 | **0.163.0+26.3** | mais recente para 26.3 |
| Java | 21 | **25** | obrigatório; `options.release = 25` |
| Browser | `com.cinemamod:mcef:2.1.6-1.21.4` | **`net.dimaskama:mcef-modern:0.3.3+mc26.3.jcef146.0.10`** | ver §3 |

Repos Maven a trocar: `mcef-download.cinemamod.com` → `maven.dimaskama.net/releases`.

Consequências no `build.gradle`:

- `mappings "net.fabricmc:yarn:..."` → **apagar a linha**
- `modImplementation "net.fabricmc:fabric-loader"` → `implementation`
- `modImplementation "net.fabricmc.fabric-api:fabric-api"` → `implementation`
- `modCompileOnly/modRuntimeOnly` → `compileOnly` / `runtimeOnly`
- `jar` (o `remapJar` já não é usado; não há mais remapping)

---

## 3. O browser: MCEF morreu → `mcef-modern`

**Confirmado:** o MCEF do CinemaMod não tem build para 1.21.5+ nem 26.x.

- `maven-metadata.xml` do upstream termina em `2.1.6-1.21.4`, `lastUpdated 2025-01-16`
- Sem branches `26.1/26.2/26.3`; o maintainer `mathhulk` respondeu ao issue #142
  ("I want to take over this project", 20/Jul/2026): *"feel free to maintain and
  release forks... some community members have already done this"*
- Irrelevantemente, `MCEFBrowser.java` faz ~37 referências a `org.lwjgl.glfw`,
  que **não existe** na árvore de dependências do 26.3. Seria um break de compilação.

**Escolha: `mcef-modern` (DimasKama).** Verificado no Maven:
`net.dimaskama:mcef-modern:0.3.3+mc26.3.jcef146.0.10`, 24/Set/2026, Fabric,
client-only, LGPL-2.1. Mantém o formato "biblioteca + mod".

I inspecionei o sources jar. A boa notícia: a API do `mcef-modern` está
**nativa do 26.3**, o que nos poupa trabalho:

```java
// API do mcef-modern para 26.3 (verificado no sources jar)
GpuTexture      getTexture();        // em vez de getTextureID() -> int
GpuTextureView  getTextureView();    // pronto para o pipeline novo
void onMouseClicked (MouseButtonEvent e, boolean doubled);  // record vanilla
void onMouseReleased(MouseButtonEvent e);
void onMouseScrolled(int x, int y, double amount);
void onMouseMoved  (int x, int y);
void onKeyPressed  (KeyEvent e);      // record vanilla
void onKeyReleased (KeyEvent e);
void onCharTyped   (CharacterEvent e);
```

Isto confirma o `KeyEvent` / `MouseButtonEvent` / `CharacterEvent` como o
vocabulário de input do 26.3 — a fase de input vai consumir exatamente estes
records.

Internamente o `mcef-modern` já faz o trabalho certo:
`RenderSystem.getDevice().createTexture(...)` + `createTextureView(...)` +
`createCommandEncoder().writeToTexture(...)`. Ou seja, a textura do browser é
uma `GpuTexture` nativa, e o upload é GPU-side.

---

## 4. A espiada (spike) — quase toda resolvida ✅

**Actualização 2026-10-14: fui buscar o repositório do `mcef-modern` e o
blocker principal está resolvido.**

O problema original era: em 26.3 o `RenderSetup` só aceita texturas de
resource pack.

```java
RenderSetup.RenderSetupBuilder.withTexture(String name, Identifier texture)
```

Isto é um `Identifier` (ficheiro do resource pack), **não** um `GpuTextureView`.
A textura do Chromium é gerada em runtime e não tem `Identifier`. Portanto a via
fácil `RenderTypes.text(id)` não serviria.

**A resposta existe.** Do próprio testmod do `mcef-modern`
(`src/testmod/.../MCEFTestModScreen.java`), verbatim:

```java
guiGraphics.guiRenderState.addGuiElement(new BlitRenderState(
        RenderPipelines.GUI_TEXTURED,
        TextureSetup.singleTexture(gpuTextureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
        new Matrix3x2f(guiGraphics.pose()),
        0, 0, width, height,
        0.0F, 1.0F, 0.0F, 1.0F,
        0xFFFFFFFF,
        guiGraphics.scissorStack.peek()));
```

Três factos decisivos:
1. `TextureSetup.singleTexture(GpuTextureView, GpuSampler)` **existe** — aceita a
   textura runtime do Chromium directamente. O blocker morreu.
2. Os samplers vêm de `RenderSystem.getSamplerCache()` (GLFW era o único filtro
   em 1.21.4; agora é o cache do device).
3. `GpuTextureView` + `RenderPipelines` + `guiRenderState` é a combinação correcta.

### O que falta (agora é uma pergunta estreita, não um mistério)
O testmod só demonstra o caminho **2D / GUI**. O CoScreen precisa de quads
**3D no mundo**. A única incógnita que resta:

> Existe um `RenderSetup` / estado de render **no espaço do mundo** que aceite
> um `TextureSetup` (e não um `Identifier`)?

15 minutos de `javap` sobre o jar do 26.3 respondem a isto. Provavelmente
`RenderSetup` tem um setter de `TextureSetup`, ou há um `…renderer.state.*`
equivalente ao `BlitRenderState` para world space. Se não: `FeatureRenderer`
próprio via `FeatureRendererRegistry` + `SubmitRenderPhases`.

### Saída esperada da espiada
Um quad texturizado **no mundo**, com a página real do browser, com depth test e
sem cull.

### API confirmada de brinde (26.3)
Do mesmo ficheiro, e que o research anterior não tinha apanhado:

```java
// Screens
void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick)
minecraft.gui.setScreen(parent);          // ⚠️ NÃO é Minecraft.setScreen() — mudou!
g.requestCursor(CursorType);              // substitui o setCursorChangeListener
g.scissorStack.peek();  g.pose();        // Matrix3x2f

// Input — os records vanilla, dentro do Screen
boolean mouseClicked(MouseButtonEvent e, boolean doubled)
boolean mouseReleased(MouseButtonEvent e)
boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert)
boolean keyPressed(KeyEvent e) / keyReleased(KeyEvent e) / charTyped(CharacterEvent e)
void     onClose() / removed()            // ⚠️ o nosso código usa close()
```

⚠️ `Minecraft.setScreen()` → `Minecraft.gui.setScreen()`. O CoScreen chama
`mc.setScreen(...)` em 5 sítios. Esta rename não estava na lista original.

### Dependências — coordenadas confirmadas

`gradle.properties` e `build.gradle` do `mcef-modern` (master = 26.3):

```properties
minecraft_version=26.3
loader_version=0.19.5
loom_version=1.18-SNAPSHOT
fabric_version=0.161.0+26.3
jcef_api_version=jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179
jcef_version=146.0.10
org.gradle.configuration-cache=false      # loom issue #1349
```

Factos que simplificam a nossa `build.gradle`:
- **O JCEF vem embutido.** O `mcef-modern` faz
  `include(implementation("me.friwi:jcef-api:..."))` e
  `include(implementation("me.friwi:jcefmaven:..."))`. Ou seja, basta depender
  do `mcef-modern` — não há `jcef` para gerir à mão.
- Mod id = **`mcef-modern`** (não `mcef`). O `fabric.mod.json` do `CoScreen`
  tem de mudar `"mcef": "*"` → `"mcef-modern": "*"`.
- `mcef-modern` só declara `depends: minecraft >=26.3`, `fabric-api >=0.161.0+26.3`.
- Sem `mappings`, `implementation` (não `modImplementation`), `options.release = 25`.
- `sourceCompatibility`/`targetCompatibility` = `VERSION_25`.

Nota de versão: o `mcef-modern` fixa `fabric_version=0.161.0+26.3`, e a
`0.162.0+26.3` é o que o `fabric-example-mod` de 26.3 usa. Como o `mcef-modern`
declara `>=`, estamos livres de subir para `0.163.0+26.3`.

### Saída esperada da espiada
Um `render` de teste que mostra **um quad texturizado com a página real do
browser**, com depth test e sem cull. Se isto funcionar, o resto da fase 4 é
mecânico.

---

## 5. Plano por fases

Cada fase termina com `gradlew build` a verde.

### Fase 0 — Espiada (0.5–1 dia)
Montar o esqueleto 26.3 e fazer UMA coisa: desenhar a textura do browser no
mundo. Valida as Fases 3 e 4 num único experimento. Sai daqui uma decisão
(documentada) sobre pipeline próprio vs. accessor.

### Fase 1 — Build (0.5 dia)
`gradle.properties`, `build.gradle`, `settings.gradle`, wrapper, `fabric.mod.json`.
- Java 21 → 25
- `yarn_mappings` eliminada
- Loom novo, `implementation` em vez de `modImplementation`
- `fabric.mod.json`: `java: ">=25"`, `minecraft: "~26.3"`,
  `mcef` → `mcef-modern` (confirmar o mod id real)

### Fase 2 — Renome mecânico (1 dia)
Renomeações globais. Não há decisões de desenho, só o mechanical:

| Yarn (agora) | 26.3 |
|---|---|
| `MinecraftClient` | `net.minecraft.client.Minecraft` |
| `Vec3d` | `net.minecraft.world.phys.Vec3` |
| `MatrixStack` | `com.mojang.blaze3d.vertex.PoseStack` |
| `RotationAxis.POSITIVE_Y.rotationDegrees(d)` | `Axis.YP` + `pose.rotateDegrees(...)` |
| `Text.literal` | `Component.literal` |
| `SoundCategory` | `net.minecraft.sounds.SoundSource` |
| `InputUtil` | `com.mojang.blaze3d.platform.InputConstants` |
| `InputUtil.Type.KEYSYM` | `InputConstants.Type.KEYBOARD` |
| `KeyBinding` | `KeyMapping` |
| `KeyBindingHelper` | `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper` |
| `KeyBinding.getBoundKeyLocalizedText()` | `getTranslatedKeyMessage()` |
| `ButtonWidget` / `TextFieldWidget` | `Button` / `EditBox` |
| `ServerInfo` | `net.minecraft.client.multiplayer.ServerInfo` |
| `Entity.getEyePos()` | `getEyePosition()` |
| `Entity.getRotationVector()` | ⚠️ **devolve `Vec2`, não uma direção** → usar `getLookAngle()` |
| `World.getRegistryKey()` | `Level.dimension()` |
| `Options.getSoundVolume` | `getSoundSourceVolume` |
| `MinecraftClient.inGameHud` | `Minecraft.gui.hud` |
| `DrawContext` | `GuiGraphicsExtractor` |
| `DrawContext.drawTextWithShadow` | `gfx.text(font, …, color[, dropShadow])` |
| `DrawContext.drawCenteredTextWithShadow` | `gfx.centeredText(font, …, color)` (já tem sombra) |
| `ctx.getScaledWindowWidth/Height` | `graphics.guiWidth()` / `guiHeight()` |
| `Screen.render(...)` | `Screen.extractRenderState(GuiGraphicsExtractor, …)` |
| `WorldRenderEvents.LAST` | `LevelRenderEvents.END_MAIN` |
| `MinecraftClient.setScreen(s)` | **`Minecraft.gui.setScreen(s)`** |
| `Screen.close()` | `Screen.onClose()` |
| `GLFW.GLFW_KEY_M` etc. | `InputConstants.KEY_M` etc. |

Detalhes que apanham people:
- `DrawContext` passou a `GuiGraphicsExtractor` — já não desenha, **extrai**
  estado para um `GuiRenderState`. Os 9 ecrãs mudam de forma de vez.
- `centeredText` não tem overload sem sombra. Para texto centrado com sombra
  (o que o HUD quer) está correcto; para sem sombra, calcular à mão.
- A categoria das keybindings passou a `KeyMapping.Category` (um `record`
  com `Identifier`), não `String`. Registar com `KeyMapping.Category.register(...)`.
- Constantes de rato mudaram: `MOUSE_BUTTON_LEFT` = 1 (era 0), `RIGHT` = 3 (era 1).

### Fase 3 — Browser (1 dia)
`BrowserInstance` (435 linhas) passa a `mcef-modern`.
- `MCEF.isInitialized()` → `MCEFApi.getInstanceFuture()` (não bloquear a render thread!)
- `createBrowser(url, false, w, h)` → `createBrowser(url, false)` + `resize(w, h)`
- `getRenderer().getTextureID()` → `getTextureView()`
- Os handlers de `CefClient` continuam válidos (`org.cef.handler.*`)
- `setCursorChangeListener` deixa de ser necessário: o `mcef-modern` devolve
  `getCursorType()`, que é a API certa em 26.3

Cuidado: a criação do browser hoje é preguiçosa na render thread.
`MCEFApi.getInstance()` **bloqueia** — usar sempre a variante `Future`.

### Fase 4 — Render (1–2 dias) ⚠️ a mais pesada
`WorldScreenRenderer.java` (154 linhas) reescrito por completo.

O caminho imediato (`Tessellator` + `BufferRenderer.drawWithGlobalProgram`)
**não existe** em 26.3 — confirmado por pesquisa: zero ocorrências de
`Tessellator`/`Tesselator` e de `MultiBufferSource` nas 7301 classes do jar.

Novo esqueleto, segundo o padrão dos testmods da própria Fabric API 26.3:

```java
LevelRenderEvents.END_MAIN.register(ctx -> {
    PoseStack pose = ctx.poseStack();
    pose.pushPose();
    pose.rotateDegrees(Axis.YP, -s.yaw);
    // coords relativas à câmara — a PoseStack do ctx é a pose do mundo
    ctx.submitNodeCollector().submitCustomGeometry(
        pose, meuRenderType, (p, buf) -> quad(buf, p, ...));
    pose.popPose();
});
```

Notas:
- `ctx.camera()` **não existe** em `LevelRenderContext`. Usar
  `ctx.levelState().cameraRenderState.pos`, ou passar coords já relativas à câmara.
- `END_MAIN` documenta-se como "antes das nuvens e do tempo". Em 1.21.4 o
  `LAST` corria *depois* das nuvens. **Não existe evento em 26.3 que corra
  depois das nuvens.** É uma regressão de comportamento aceite (as nuvens
  passam por cima da imagem). Documentar no CHANGELOG.
- O `drawBacking`/`drawPlaceholder` (cores sólidas) são o caso fácil:
  `RenderTypes.debugFilledBox()` ou `debugQuads()` já servem.
- Se a espiada provar que precisamos de batching, o caminho mais escalável é um
  `FeatureRenderer` próprio via `FeatureRendererRegistry` +
  `SubmitRenderPhases` — melhor para muitos ecrãs, mais trabalho.

### Fase 5 — Input (1–2 dias) ⚠️ a segunda mais pesada
Hoje o mod encadeia callbacks GLFW globais (`installInputHooks`, ~60 linhas) para
roubar scroll/teclas/caracteres. **GLFW não existe em 26.3** e o SDL3 não
expõe um mecanismo de chaining para mods.

Duas opções avaliadas:

**Opção A — `Screen` transparente (o que o IMBlocker faz).** Usa
`ScreenKeyboardEvents` / `ScreenMouseEvents` (`allowKeyPress` → devolver
`false` consome o evento). API suportada e limpa.
❌ **Rejeitada**: o requisito central do mod é *"o teclado só vai para a página
quando há um campo de texto com foco, para o WASD continuar a andar enquanto se
vê".* Abrir um `Screen` mata o input de movimento. Errado para este mod.

**Opção B — mixins em `KeyboardHandler` / `MouseHandler`.** ✅ Escolhida.
Reproduz fielmente a semântica actual ("encadear" = cancelar no HEAD):

| hoje (GLFW) | 26.3 (mixin) |
|---|---|
| `glfwSetKeyCallback` | mixin HEAD `KeyboardHandler#keyPress` |
| `glfwSetCharCallback` | mixin HEAD `KeyboardHandler#charTyped` |
| `glfwSetScrollCallback` | mixin HEAD `MouseHandler#onScroll` |
| `glfwGetMouseButton` | `MouseHandler.isLeftPressed()` / `isRightPressed()` (público — sem mixin) |
| `Minecraft#handleGlobalKeyPress(Key, boolean)` | hook público e sancionado, para as keybindings |

Isto **acrescenta infraestrutura nova ao projeto**: um `coscreen.mixins.json` e
`loom` a configurar mixins. Hoje o projeto não tem nenhum.

Ponto de atenção do blog do Fabric: para escrever numa view web com um `Screen`
aberto é obrigatório gerir o `TextInputManager`, senão o input de caracteres
quebra por completo. (O exemplo do blog tem um bug — `onTextInputFocusChange`
tem 2 argumentos e é método de instância. O correcto é
`Minecraft.getInstance().onTextInputFocusChange(element, focused)`.)

### Fase 6 — GUI + HUD (1 dia)
9 ecrãs + `Hud`. Todos `render(DrawContext, …)` → `extractRenderState(GuiGraphicsExtractor, …)`.
Renomeações de widgets (`ButtonWidget`→`Button`, `TextFieldWidget`→`EditBox`).
`Hud` fica mais simples se `HudRenderCallback` tiver mudado — confirmar.

### Fase 7 — Polish (0.5 dia)
`CHANGELOG.md` (nova secção, com as regressões honestas: ordem de render vs.
nuvens), `README.md`, e `assets/coscreen/lang/*.json` (chaves de keybinding
podem ter mudado de categoria em 26.3).

---

## 6. Impacto por ficheiro

| Ficheiro | Linhas | Peso | O que muda |
|---|---|---|---|
| `CoScreenClient.java` | 1052 | 🔴 pesado | Fases 2+3+5. Input hooks, keybinds, chat |
| `BrowserInstance.java` | 435 | 🔴 pesado | Fase 3 — migração mcef-modern |
| `WorldScreenRenderer.java` | 154 | 🔴 reescrita | Fase 4 — de raiz |
| `Lang.java` | 243 | 🟡 médio | Fases 2+6 (chaves de lang) |
| `BrowserFullScreen.java` | 215 | 🟡 médio | Fases 2+3+6 |
| `MenuFriends/ScreenCtl/Main/Streams/Invites/JoinChoice/UrlEntry` | ~820 | 🟡 médio | Fases 2+6 |
| `NetClient.java` | 202 | 🟢 leve | Renome + `MinecraftClient` |
| `ScreenWorld.java` | 149 | 🟢 leve | `getRegistryKey()`→`dimension()`, `getRotationVector()` |
| `Packets.java` | 120 | 🟢 leve | `Text.literal`→`Component.literal` |
| `PlacedScreen.java` | 115 | 🟢 leve | `Vec3d`→`Vec3` |
| `Cfg.java` | 104 | ⚪ nenhum | Constantes de compilação |
| `Settings.java` | 101 | ⚪ nenhum | Ficheiros/properties |
| `PlaybackSync.java` | 99 | ⚪ nenhum | Puro |
| `Hud.java` | 82 | 🟡 médio | API de texto do 26.3 |
| `AppState.java` | 83 | ⚪ nenhum | Puro |
| `StreamInfo.java` | 49 | ⚪ nenhum | Puro |

---

## 7. Riscos registados

1. ~~`TextureSetup` a partir de `GpuTextureView`~~ — **RESOLVIDO.**
   `TextureSetup.singleTexture(GpuTextureView, GpuSampler)` existe, confirmado
   no testmod do `mcef-modern`. O que resta e so a variante *world-space* (ver §4).
2. **Sem evento "depois das nuvens"** — regressão funcional real, não
   recuperável com a API atual. Registar como limitação conhecida.
3. **Estado da reformulação de `LevelRenderEvents`** — os docs do
   `docs.fabricmc.net` ainda estavam a ser migrados quando o 26.3 saiu.
4. **Só o caminho 2D do `mcef-modern` está demonstrado.** O testmod é um
   `Screen`; nós queremos world space. A primeira espiada tem de cobrir isso.
5. **Sem evento de scroll no mundo** via Fabric API — a Opção B depende de
   mixins, que precisam de ser testados contra outros mods.
6. **MCEF vs mcef-modern é LGPL** — o CoScreen tem `LICENSE` própria. A
   licença permite escolher outra desde que não se modifique/inclua o código
   do MCEF. `modCompileOnly` mantém isso. **Confirmar com quem decide a licença.**
7. **Versões ainda a mexer** — a Fabric API tem `0.163.0+26.3` e o maven já
   tem `+26.4`; o Loom está em `1.18-SNAPSHOT` / `1.19.0-alpha`. Fixar versões,
   não usar `+` dinâmico.
8. **`configuration-cache` desligado** — o proprio `mcef-modern` desliga por
   causa do [loom#1349](https://github.com/FabricMC/fabric-loom/issues/1349).
   O nosso `build.gradle` tambem tem de o desligar.

---

## 8. Estimativa

| Fase | Tempo | Risco |
|---|---|---|
| 0 — Espiada | 0.5–1 d | 🔴 decides o resto |
| 1 — Build | 0.5 d | 🟢 |
| 2 — Renome | 1 d | 🟢 mecânico |
| 3 — Browser | 1 d | 🟡 |
| 4 — Render | 1–2 d | 🔴 principal |
| 5 — Input | 1–2 d | 🟠 |
| 6 — GUI/HUD | 1 d | 🟡 |
| 7 — Polish | 0.5 d | 🟢 |
| **Total** | **6.5–9 dias** | |

A maioria é trabalho mecânico e verificável pelo compilador. O trabalho de
verdade está nas Fases 0, 4 e 5 — e a Fase 0 existe precisamente para não
descobrir o problema da Fase 4 no fim.

---

## 9. Ordem de ataque recomendada

1. **Fase 0 primeiro.** Não escrever o resto antes de o quad com a textura do
   browser aparecer no mundo. É a única forma de não descobrir um bloqueador
   depois de dois dias de renomeações mecânicas.
2. Decidir, com a espiada na mão, entre `FeatureRenderer` próprio e
   `submitCustomGeometry` por ecrã.
3. Só depois as fases 1→7 em fila, com build verde entre cada.
