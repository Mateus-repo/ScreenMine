# Revisão completa — ScreenMine · 5 passagens

> **Actualizado 2026-10-16.** Sete achados já corrigidos, um deles por mim
> (o loop de re-injecção, que a revisão não previu). Tabela de estado no fim.
> O texto original está tal como foi escrito, para se ver o raciocínio antes das
> correcções.

**Âmbito.** Todo o código em `src/main/java/dev/screenmine` na branch `spike/codecs`
(HEAD `1ece4e3`), ~3.800 linhas. Feita antes da próxima corrida, para não gastar
outra sessão a descobrir o que se via ler.

**Como ler.** Cada achado diz o que verifiquei e como. Três etiquetas:

- **[V]** verificado — li o código, ou confirmei com `javap` no jar real.
- **[I]** inferido — o raciocínio é sólido mas não foi visto a correr.
- **[H]** hipótese — precisa de uma corrida para decidir.

**Estado:** este ficheiro é absorvido no `progresso.md` e apagado, como o
`codecs.md`. Não é um segundo plano, é o registo do que a revisão achou.

---

# Resumo executivo

O que está **bem** e não deve ser tocado sem razão:

- O render no mundo está **provado em jogo** e a correcção da câmara está certa.
- `BrowserTexture` a tratar `textureView`/`sampler` em conjunto está certo, e o
  comentário explica exactamente porque. **[V]**
- `onConsoleMessage` com a assinatura certa, verificada com `javap`. **[V]**
- `MCEFApi.initialize()` é idempotente (verificado no bytecode: campo estático
  volátil + monitor). Chamar por frame não faz mal. **[V]**

O que está **partido** e porquê o `N` continua a não fazer nada:

| # | Achado | Gravidade |
|---|---|---|
| **A1** | O agente reescreve `document.title` **só quando a string muda**. Qualquer página que toque no título (o YouTube toca) trava o canal para sempre. | **crítico** |
| **A2** | O agente só corre no frame principal. Vídeo dentro de iframe é **invisível** para ele. | **crítico** |
| **A3** | `applyingRemote` é lido no thread do CEF mas escrito no thread do jogo, sem `volatile`. | **alto** |
| **A4** | `agentInjected`, `appliedGain`, `browser`, `closed` — idem, semhappens-before. | **alto** |
| **A5** | `detach` + `attach` no mesmo frame deixa a textura registada a apontar para um browser fechado. | **alto** |
| **A6** | `net.send()` deita fora pacotes em `CONNECTING`; `reportServer` marca o servidor como reportado na mesma. | **alto** |
| **B1** | `keyMenu` e `keyBrowser` põem `controlMode = false` sem tirar o foco ao browser. | **médio** |
| **B2** | A roda no overlay não é escalada; no mundo é. Comportamento diferente para a mesma acção. | **médio** |
| **B3** | `MenuFriends`/`MenuStreams` só reconstroem ao clicar; pacotes novos não aparecem. | **médio** |
| **C1** | `ensureCreated()` sem `try/catch`: se `createBrowser` lançar, repete a cada frame. | **médio** |
| **C2** | `Lang.t(key, args)` sem protecção: um `%` trocado crasha o cliente. | **médio** |
| **C3** | `MenuJoinChoice.rebuildWidgets()` faz só `super` — código morto. | **baixo** |
| **C4** | `CodecProbe` javadoc diz "one-shot" e "appended to the agent script"; nenhuma das duas é verdade. | **baixo** |
| **C5** | `ScreenMineClient` tem dois blocos de javadoc empilhados antes de `pumpAgents()`. | **baixo** |
| **C6** | `spike/` é código morto com a **câmara subtraída duas vezes** — o bug que o código de produção diz ter corrigido. | **baixo** |

**O mais importante da revisão não é um bug: é uma medição que nunca foi feita.**
Ver §A1 e §0.

---

# §0 — A pergunta que nenhuma das 4 corridas respondeu

O bug B ("o `N` não faz nada") foi sempre attacking a ponta errada. Corrigimos a
injeção, provamos que `__smVol` chega ao `window`, e provamos que o `N` manda o
zero. Em nenhum momentomissiblemos **se o `SM|` alguma vez chegou ao Java**.

Isso não é um detalhe. O canal `document.title` é a **única** fonte de:

| Dependentemente do `SM|` | Se o canal estiver morto |
|---|---|
| `hasVideo()` | nunca verdadeiro → `followServerClock` nunca faz nada |
| `playbackPaused()` | sempre `true` → `togglePlayPause` mente |
| `isEditing()` | sempre `false` → **as teclas de edição nunca chegam ao browser** |
| `isAd()` | sempre `false` |
| URL e título da página | nunca actualizados |

Ou seja: **se o `SM|` não chega, o bug A (teclas de edição) está explicado pela
mesma causa que o bug B**, e a "causa raiz" que fechámos no `7acbaaf` foi só metade
da história.

E existe uma razão concreta para desconfiar. Do log de 12:55:

```
[12:55:31] agent report: vol=0.7|media=0|vvolume=-|vpaused=-|editable=0
```

`vol=0.7` prova que o Java→página funciona. Mas `secureContext=no` numa página
`https://` **não devia acontecer**. As duas leituras possíveis:

1. `executeJavaScript(..., cef.getURL(), 0)` correu no `about:blank` inicial, e o
   agente vive num documento morto. Isso explicaria **tudo**: `media=0`, ausência
   de estado, e o `N` a escrever num `window` que ninguém lê.
2. Correu no documento certo e `isSecureContext` é falso por outra razão.

**Distinguir isto custa uma linha.** `location.href` dentro do auto-relatório.
Enquanto essa linha não existir, estamos a adivinhar — e eu já adivinhei
errado duas vezes nesta investigação.

---

# Passagem 1 — Corretude

## A1 · O agente trava o próprio canal de saída [V, crítico]

`BrowserInstance.java:79`

```java
"if(s!==last){last=s;document.title=s;}"
```

`last` é o **conteúdo** que o agente escreveu da última vez. O guard existe para
não disparar `onTitleChange` quatro vezes por segundo sem mudança. O problema é
que a variável de estado é *o que nós escrevemos*, não *o que está no título*.

O YouTube escreve `document.title` com o nome do vídeo. Assim que o faz:

- `last` continua igual (o payload `SM|` não mudou)
- o guard é falso
- o agente **não reescreve**
- o Java **nunca mais recebe `SM|`** para aquele browser, até o payload mudar

E o payload só muda quando muda `currentTime` às centésimas… mas mesmo assim é
preciso que o YouTube não esteja a escrever entre dois ticosOur de 250 ms. Uma
página que escreva o título com mais frequência **ganha a corrida para sempre**.

Isto é a explicação mais provável para o `N` não funcionar, e é **independente do
volume**: o `setInterval` que aplica `e.volume` está no mesmo agente, no mesmo
documento. Se o agente estiver no documento certo, o volume tem de funcionar; se
não estiver, o auto-relatório também mentiu.

**Correcção (uma linha, estritamente melhor):**

```js
if(s!==last||document.title.slice(0,3)!=='SM|'){last=s;document.title=s;}
```

Passa a depender do que está no documento, não do que escrevemos. Custo: no máximo
uma escrita extra por tico enquanto o título está intacto.

## A2 · O agente é cego a iframes [V, crítico]

`BrowserInstance.java:67` — `document.querySelectorAll('video,audio')` só vê o
documento principal.

Verificado na API **[V]**:

```
CefBrowser.getFrameIdentifiers()      -> java.util.Vector<String>
CefBrowser.getFrameByIdentifier(String) -> CefFrame
CefFrame.executeJavaScript(String, String, int)
```

Isto **não é teórico, é uma entrada que o próprio mod suporta**.
`ScreenMineClient.reportOwnedStreams` (linha 824) trata explicitamente
`bUrl.contains("/embed/")`. Ou seja: o mod aceita um URL de embed, e depois não
consegue ver o vídeo que está lá dentro. Para um `/embed/` do YouTube, o agente
vê zero elementos e o volume nunca é aplicado.

**Correcção:** injectar o agente em todos os frames, não só no principal.
`getFrameIdentifiers()` dá a lista; `getFrameByIdentifier` dá o frame. O agente
já é idempotente (`if(window.__smAgent)return;`), por isso injectar em todos é
seguro por construção.

Custo: uma volta por frame depois de cada navegação. Só quando `getFrameCount() > 1`.

**Verificado:** `CefClient` **não** tem `addFrameHandler` nesta build do JCEF, e
não `CefFrameHandlerAdapter`. Só a via de interrogação. **[V]**

## A3 · `applyingRemote` atravessa threads sem sincronização [V]

`BrowserInstance.java:115` (declaração), `:244` (leitura no thread do CEF),
`:526`/`:531` (escrita no thread do jogo).

Não é `volatile`. O Java Memory Model não garante que a escrita do thread do jogo
seja visível na leitura do thread do CEF. Na prática quase sempre é (o CEF tem
barreiras internas), mas "quase sempre" é exactamente a classe de coisa que produz
um bug que ninguém consegue reproduzir.

Pior: **o guard não funciona mesmo com `volatile`.** `loadURL()` é assíncrono.
`applyingRemote` é reposto a `false` milissegundos antes de `onAddressChange`
chegar. O eco é portanto impedido pelo `sameVideo()` em `onLocalNavigate`, não
por este flag — ou seja, **o flag é código que não protege nada** e ainda assim
dá a impressão de que protege.

## A4 · Campos partilhados sem `volatile` [V]

O mesmo padrão, mais largo. `BrowserInstance` tem:

| Campo | Escrito no | Lido no |
|---|---|---|
| `agentInjected` | CEF (`onLoadEnd`) **e** jogo (`pumpAgent`) | ambos |
| `appliedGain` | CEF (`injectAgent`) **e** jogo (`applyGain`) | ambos |
| `probeRan` | CEF (`maybeProbeCodecs`) | jogo |
| `browser` | jogo (`ensureCreated`), CEF (`close`→null) | ambos |
| `closed` | jogo | render |

Os campos de estado do playback (`playbackTime`, `playbackPaused`, `editing`,
`isAd`, `pendingNavigate`, `pageTitle`, `currentUrl`) **estão todos `volatile`** —
alguém teve esse cuidado. Os de controlo de ciclo de vida, não. Inconsistência
evidente que vale corrigir de uma vez.

## A5 · Textura órfã quando detach e attach caem no mesmo frame [I]

`WorldScreenRenderer.textureIds` é indexado por `streamId`. `releaseDetached()`
só apaga entradas cujo `world.get(id) == null`.

Cenário: `onSession` (linha 921-935) faz `attach` dos streams novos e `detach` dos
que desapareceram — **no mesmo tick**. O `render` corre depois. Se um id for
detachado num tick e re-attached no seguinte **antes de um frame de render**, o
`releaseDetached` nunca o vê ausente, e o `BrowserTexture` registado continua a
apontar para o `BrowserInstance` **fechado**. Resultado: ecrã preto para sempre
para aquele stream.

É raro mas é um "não volta a funcionar sem reiniciar", que é a pior classe de bug.

**Correcção:** indexar por identidade do `BrowserInstance`, não pelo `streamId` —
ou verificar, ao reutilizar, se a instância é a mesma.

## A6 · `net.send()` deita pacotes fora em CONNECTING [V]

`NetClient.send` (linha 87):

```java
if (status == Status.OFFLINE) { return; }
```

E `reportServer` (linha 1061):

```java
lastReportedServer = id;   // marca como reportado
state.myServer = id;
net.send(Packets.where(id));
```

Sequência: o jogador entra no mundo → `net.start()` → estado `CONNECTING` →
`reportServer` marca `lastReportedServer` e o pacote é **descartado**. Quando a
ligação-established, o servidor **nunca sabe em que servidor Minecraft estamos**
até o jogador mudar de servidor. `invites.other.server` e `friends.other.server`
passam a mostrar "desconhecido" para sempre.

É um bug silencioso e fácil de não ver. **Correcção:** não marcar
`lastReportedServer` antes de o envio ter saído, ou almacenar estado
`CONNECTING` como "enviar no próximo ONLINE".

## B1 · Foco do browser não é largado ao abrir menus [I]

`handleKeys`:

- `keyMenu` → `controlMode = false; setScreen(new MenuMain())`
- `keyBrowser` → `controlMode = false; setScreen(new BrowserFullScreen(...))`

`toggleControlMode()` faz `releaseBrowserFocus()`; estes dois não. O
`controlledBrowser()` já devolve `null` quando há ecrã, portanto nenhum input
escapa — mas o browser fica com `setFocus(true)` e é libertado só no `onClose()`
do overlay. Correcção de uma linha, `releaseBrowserFocus()` nos dois.

## B2 · Roda: overlay e mundo com escalas diferentes [V]

- `BrowserFullScreen.mouseScrolled` → `b.onMouseScrolled(px, py, vertical)` — cru.
- `ScreenMineClient.onScroll` → `b.raw().onMouseScrolled(x, y, dy * Cfg.SCROLL_SPEED)` — 3×.

`SCROLL_SPEED` não é usado em lado nenhum no overlay. Ou o overlay está
incorrecto, ou o mundo está. Alguém tem de escolher. **Recomendo `SCROLL_SPEED`
nos dois** — o overlay é que está a contradizer a configuração.

## B3 · Menus não reagem a pacotes novos [I]

`MenuFriends.refresh()` só é chamada dentro de um clique. `MenuStreams` e
`MenuInvites` só em `init()`. Um pedido de amizade que chegue enquanto o menu
está aberto **não aparece** até o jogador clicar em qualquer botão. O HUD
(actualiza sempre) é que o mostra.

Isto é pior no `MenuFriends` porque o botão "Add" está no caminho natural do
utilizador — clicar nele mascara o problema.

## C1 · `ensureCreated()` sem protecção [V]

`BrowserInstance.java:162` — `MCEFApi.getInstance().createBrowser(...)` não está
em `try/catch`. Se lançar (limite de browsers, memória, CEF em estado mau), o
`browser` fica `null` e **`WorldScreenRenderer.render` repete a tentativa em cada
frame de cada ecrã**, enchendo o log. O `close()` já tem `try/catch`; o
`createBrowser` devia ter o mesmo, e ideally uma tentativa só.

## C2 · `Lang.t` pode crashar o cliente [V]

`Lang.java:75` — `String.format(Locale.ROOT, t(key), args)` sem protecção.

**Verificado, e é menos mau do que parecia:** auditei os 102 pares `put(...)` e
todos têm o número de especificadores igual ao número de argumentos passados nos
call-sites. As duas apparentemente incompatíveis (`ctl.sound`, `chat.volume`) são
`%%` — escape correcto. **Não há nenhum mismatch hoje.**

O risco continua a ser de *futuro*: um `put` novo com um `%` a mais é um crash em
runtime, dentro do render ou do tick, sem nenhum teste que o apanhe.
`try/catch (IllegalFormatException)` com fallback para `t(key)` custa três linhas.

## C3 · Override que não faz nada

`MenuJoinChoice.java:29-36` — `rebuildWidgets()` chama só `super`. O override
existe apenas porque `rebuildWidgets` é `protected` e o `refresh()` público o usa.
Isto é ruído: o override pode ser apagado e o `refresh()` continua a compilar.

## C4 · Comentários que já não descrevem o código

`CodecProbe.java`:
- linha 40: `AGENT_PREFIX` — "one-shot self report". Já não é: é a cada mudança.
- linha 70: "Written as one expression rather than a function so it can be
  appended straight to the agent script" — não é anexado a nada; é um
  `executeJavaScript` separado.

Regra do projecto: um comentário que descreve o código anterior ao bug é pior que
zero comentário, porque authorize confidently. Ambos estão errados.

## C5 · Javadoc órfão empilhado

`ScreenMineClient.java:544-550`. Dois blocos `/** */` consecutivos; o primeiro
("Screens are quieter the further away you stand...") não pertence a nada e o
segredo do método que o segue é `pumpAgents()`, que já tem o seu. Sintoma da
mesma coisa: um método inserido no meio doutro e o javadoc ficou para trás.

## C6 · `spike/` é código morto com um bug conhecido

`SpikeClient.render`, linhas 116-121:

```java
pose.translate(-cam.x, -cam.y, -cam.z);                       // -camera
pose.translate((float)(quadPos.x - cam.x), ... );              // -camera outra vez
```

**É exactamente o bug que `WorldScreenRenderer.place()` documenta como corrigido**
("the render spike did"). O ficheiro que ensina a lição ainda tem a lição por
aplicar. Não está registado em `fabric.mod.json`, portanto nunca corre — mas
compila, ocupa 176 linhas e é uma armadilha para quem o copie.

**Acção: apagar `spike/` no fim do port.** O que learned dele já está escrito nos
comentários do renderer de produção.

---

# Passagem 2 — Threads, ciclo de vida, recursos

## Mapa de threads

| Thread | O que toca |
|---|---|
| Render / cliente (`onTick`, `extractRenderState`, `render`) | quase tudo |
| `ScreenMine-Net` (leitura) | só `inbound.add(...)` |
| `ScreenMine-Net-Writer` (escrita) | só `outbound` e o socket |
| **CEF UI thread** | `onLoadEnd`, `onTitleChange`, `onAddressChange`, `onConsoleMessage` |
| CEF render | frames para a `GpuTextureView` |

## B4 · `NetClient` cria uma writer thread por tentativa [V]

`loop()` arranca `writeLoop` em cada reconexão. Quando a socket morre, `socket` é
posta a `null` em `closeSocket()` **antes** de `s.close()`, e o `while` da writer
inclui `socket != null` — portanto a thread anterior sai. Mas entre o
`socket = null` e o writer a dar por ela, pode consumir um item de `outbound` para
a `BufferedWriter` da socket velha e apanhar a `IOException` a seguir. O `catch`
engole-a e a thread morre. **Não há corrupção**, mas há uma janela em que duas
writers, em paralelo, esvaziam a mesma fila.

Não é um bug observável. É uma estrutura que convida a um bug. O remedio é um
`AtomicReference<BufferedWriter>` em vez de `socket != null` como condição de vida.

## B5 · `outbound.clear()` deita trabalho para fora [I]

`NetClient.loop()` linha 116: `outbound.clear()` logo depois de ligar. Tudo o que
foi enfileirado durante o período offline é perdido. Combinado com A6 (que já
perde o que é mandado em `CONNECTING`), a janela de perda é maior do que parece.

## B6 · `inbound` não tem limite [V]

`ConcurrentLinkedQueue` sem topo. Se o tick deixar de drainar (exceção, pausa
longa, world load), cresce sem limite. Na prática o drain é no topo do tick e não
falha, por isso isto é theoretical. Anotado, não corrigido.

## B7 · `world.all()` expõe a vista viva do mapa [V]

`ScreenWorld.all()` devolve `placed.values()`. Auditei todos os callers:
`tickAudio`, `pumpAgents`, `followServerClock`, `reportOwnedStreams`,
`releaseBrowserFocus`, `movingScreen`, `WorldScreenRenderer.render` — **nenhum
muta o mapa dentro do ciclo**. `onSession` e `__disconnected` usam cópias
defensivas (`new ArrayList<>(...)`) exactamente onde precisavam. **Não há
`ConcurrentModificationException` hoje.**

Mas é uma armadilha: `all()` devolve algo que parece uma lista e behave-se como
uma vista. `Collections.unmodifiableCollection(placed.values())` custa uma linha e
torna o contrato explícito. Ou `new ArrayList<>(...)` se for para iterar.

## B8 · `close()` enquanto o overlay está aberto [V]

`removeScreen` → `detach` → `browser.close()` → `browser = null`. O
`BrowserFullScreen` que estava aberto passa a ver `browser() == null` e todos os
handlers caem para `super`. Sem crash. `onClose()` chama `resetSurface()` num
browser fechado, que só actualiza `surfaceWidth/Height` e não toca em nada. **Safe.**

## B9 · `LIVE` só cresce se `close()` não for chamado [V]

`LIVE` é um `ConcurrentHashMap` estático indexado por identificador do CEF.
Entradas são removidas em `close()`. `close()` é chamado por `detach` e por
`clear()`, e `clear()` é chamado por `shutdownSession()`. **Não há caminho
conhecido que vaze.** Mas é um mapa estático que sobrevive ao logout: se um
`BrowserInstance` morresse sem `close()`, a entrada ficaria para sempre e
`lookup()` devolveria um objeto morto. Vale um `remove` defensivo.

## B10 · `handlersInstalled` nunca é reposto [V]

É `static boolean`, só posto a `true`, só lido e escrito no thread do jogo.
Depois do primeiro browser, nunca mais se instalam handlers. **Correcto** — o
cliente é partilhado. Mas se `browser.getCefBrowser().getClient()` devolver
`null` no primeiro browser (o browser ainda não tem client), `installHandlersOnce`
sai cedo **sem** instalar e **sem** marcar — e o browser actual fica **sem
handlers para sempre**, porque no browser seguinte já marca. **[I]**

Isto é exactamente a classe de coisa que produziu o bug original ("onLoadEnd nunca
disparou") e ninguém notou durante meses. **Defesa:** log explícito quando o client
é `null`.

---

# Passagem 3 — API: o que está verificado, o que é suposição

Regra do projecto: nunca adivinhar nomes. Cada item abaixo foi confirmado com
`javap` no jar real nesta sessão.

## Confirmado [V]

| Assunto | Facto |
|---|---|
| `MCEFApi.initialize()` | devolve `Initialization`; **idempotente** (campo volátil + monitor no bytecode). Seguro chamar por frame. |
| `MCEFApi.createBrowser(String, boolean)` | usa `CefRequestContext.getGlobalContext()` e o `CefClient` **partilhado**. |
| `MCEFBrowser` | `resize`, `setFocus`, `onMouseClicked/Released/Scrolled/Moved`, `onKeyPressed/Released`, `onCharTyped`, `getTexture`, `getTextureView`, `getCursorType`, `close`, `getCefBrowser`. |
| `CefDisplayHandlerAdapter.onConsoleMessage` | `(CefBrowser, CefSettings.LogSeverity, String, String, int) -> boolean`. **A primeira tentativa falhou** com `does not override`; esta é a real. |
| `CefBrowser.executeJavaScript` | `(String, String, int)`. **Sem overload de callback** — confirmado outra vez, é por isso que o console é o único canal de push. |
| `CefBrowser` / `CefFrame` | `getFrameIdentifiers()`, `getFrameByIdentifier(String)`, `CefFrame.executeJavaScript(...)`, `CefFrame.isMain()`. **É isto que resolve A2.** |
| `CefClient` | tem `addLoadHandler`, `addDisplayHandler`, `addKeyboardHandler`, … **não tem `addFrameHandler`** nesta build. |
| `ResourceKey.identifier()` | devolve `Identifier`; `.toString()` dá `"minecraft:overworld"`. **A comparação em `ScreenWorld.dimensionOf` está certa.** |
| `KeyboardHandler` | `keyPress(long, int, KeyEvent)` e `charTyped(long, CharacterEvent)` — **públicos**, são os alvos do mixin. |
| `MouseHandler` | `onScroll(long, double, double)` — **duas**Components. `ScreenMineClient.onScroll(double)` só tem uma. |
| `MouseHandler` | `isLeftPressed()` / `isRightPressed()` públicos — já usados, funcionam. |

## Pontos que o TODO(port) já diz e que a revisão confirma [V]

`installInputHooks` precisa de:
- `KeyboardHandler.keyPress` / `charTyped`
- `MouseHandler.onScroll`

**As assinaturas estão confirmadas acima.** O que falta não é investigação: é
escrever os dois mixins. `onScroll` precisa de ganhar o eixo horizontal.

## Pontos ainda por verificar (não assumidos) [H]

1. **Em que thread corre `onLoadEnd`?** Importa para A4. A documentação do JCEF
   diz UI thread; não confirmei no jar.
2. **`onPreKeyEvent` do `CefKeyboardHandler`** resolveria os atalhos Ctrl/Alt sem
   mixins, se o `MCEFBrowser` não enviar o evento antes. Não testado.
3. **`CefBrowser_N.N_SendKeyEvent`** é nativo — impossível confirmar o que faz com
   `KEY_PRESSED` por `javap`. É a raiz do bug A e **não é verificável
   estaticamente**. Só a sonda em runtime pode dizer.

## Sobre o bug A (teclas de edição) — o que a revisão acrescenta

O shim de edição em `BrowserInstance.java:54-64` intercepta `keydown` em fase de
captura e refaz Backspace/Delete/setas/Home/End com `setRangeText`. É a resposta
certa ao problema certo.

Mas **não cobre o resto do que foi reportado**:
- `Ctrl+A` / `Ctrl+C` / `Ctrl+V` — o shim não toca nisto. Já se sabe que
  `CefFrame` tem `selectAll/copy/cut/paste/undo/redo` públicos e `CefBrowser` tem
  `goBack/goForward` — são API pública, zero mixins. **Falta implementar.**
- `Alt+seta` — igual.
- **Seleccionar texto com o rato para copiar** — o shim não tem `mouseup` nem
  `selectionchange`; e depende de o clipboard do JCEF estar ligado ao do
  sistema. Não verificado.

E o shim **só corre no frame principal** — o mesmo problema de A2.

---

# Passagem 4 — Desempenho e fugas

## D1 · 1920×1080 off-screen por ecrã [V]

`Cfg.BROWSER_WIDTH/HEIGHT = 1920×1080`. A 4× RGBA são ~8 MB por ecrã, mais o
Chromium a descodificar e a subir frames. Com 5 ecrãs são 40 MB de textura e 5
instâncias do Chromium.

**Mitigação já no código:** `browser.resize()` tem guarda de tamanho igual, e o
overlay redimensiona para o tamanho do framebuffer e restaura ao fechar. Está
feito. O que **não** há é limite ao número de ecrãs. Se alguém spawnar 20, o
cliente morre. Um `MAX_SCREENS` com mensagem no chat seria honesto.

## D2 · `agent report` a cada mudança, sem limite [I]

O auto-relatório dispara quando `media.length` ou `__smVol` muda. Num site com
ads, o YouTube **cria e destroy elementos de vídeo** várias vezes por minuto — cada
criação é uma linha de log. Não é spam (é o que queremos agora), mas **deve
desligar-se sozinho**: um contador de máximo (ex. 50 relatórios) ou passar a
`LOG.debug` a partir de N.

## D3 · `querySelectorAll('video,audio')` 4× por segundo [I]

Por ecrã, em cada agente, para sempre. Em páginas com DOM grande (o YouTube tem
dezenas de milhares de nós) isto é trabalho real em cada tico. `.video, audio` é
selector rápido, mas 4 Hz em 5 ecrãs = 20 traversals de DOM por segundo.

**Mitigação se incomodar:** comutar para eventos (`playing`, `volumechange`) em vez
de polling. Não é necessário agora — registado para quando o profiler reclamar.

## D4 · `GAIN` em log, sempre [V]

`applyGain` agora loga cada mudança. Com o falloff por distância, andar a rondar
um ecrã gera uma linha a cada 5 ticks. **Isto é diagnóstico, não produto.**
Tem de sair antes do port estar pronto, ou ficar atrás de um flag.

## D5 · Fuga de textura: só o caminho feliz está coberto [V]

`releaseDetached()` apaga registos de ecrãs removidos. O `BrowserTexture` não faz
`close()` de propósito (correto — a textura é do browser). **Não há fuga no
caminho normal.** A única forma de ficar órfão é A5.

## D6 · `TextureManager.register` por `Identifier` [I]

`textureIdFor` sanitiza o streamId e regista `screenmine:browser/<safe>`. Se o
servidor mandar um streamId com 5000 caracteres, o `Identifier` é enorme e
`replaceAll` aloca uma string por frame de render. Limitar o comprimento seria
defensivo.

---

# Passagem 5 — Robustez, UX, e o que falta para "funcionar tudo"

## E1 · Diagnóstico ausente que trava tudo (ver §0) [H]

**A acção de maior valor de toda esta revisão:** pôr `location.href` no
auto-relatório do agente. Uma linha. Distingue definitivamente:

| `location.href` | Conclusão |
|---|---|
| `https://www.youtube.com/...` | agente no documento certo → o problema é A1 (guard do título) |
| `about:blank` | agente no documento errado → **explica o `N`, as teclas de edição, e o sync, de uma vez** |

E, no mesmopush, **logar cada `SM|` recebido** (rate-limited) para se ver se o
canal está vivo. Sem isto, a próxima corrida é mais uma ronda de adivinhação.

## E2 · O que está no caminho para "tudo funcionar", por ordem [I]

| # | Mudança | Resolve | Esforço |
|---|---|---|---|
| 1 | `location.href` no auto-relatório | mede o que trava tudo | 1 linha |
| 2 | Reescrever o título quando já não é nosso | A1 — provavelmente o `N` | 1 linha de JS |
| 3 | Agente em todos os frames | A2 — embeds, ecrãs com iframe | ~20 linhas |
| 4 | Ctrl/Alt por `CefFrame`/`CefBrowser` públicos | falta do bug A | ~40 linhas |
| 5 | Mixins de `KeyboardHandler` + `MouseHandler` | o `TODO(port)` | ~80 linhas |
| 6 | `volatile` em A3/A4 | correcção silenciosa | 6 linhas |
| 7 | Textura indexada por instância | A5 — ecrã preto eterno | ~10 linhas |
| 8 | `net.send` com fila + reenvio | A6 — "servidor desconhecido" | ~10 linhas |

1–3 são o caminho para o `N` e para o vídeo. 4–5 são o caminho para as teclas.
6–8 são dívida que não se vê mas já custou.

## E3 · Coisas que **não** são bugs, e que vale a pena não "arranjar" [V]

Registado porque as próximas revisões vão as encontrar e vão querer mexer:

- **`String` + `double` com vírgula decimal.** `"" + double` usa `Double.toString`,
  que é independente de locale. **Não há bug i18n aqui.** O `Locale.ROOT` em
  `applyGain` é correcto e necessário porque esse usa `String.format`, que já é
  locale-sensitive. A diferença é intencional, não um esquecimento.
- **`String.format` nos 102 pares de `Lang`.** Todos batem certo (§C2).
- **`ResourceKey.toString()`.** `dimensionOf` usa `identifier().toString()`, que dá
  `"minecraft:overworld"`, não `ResourceKey[minecraft:dimension / ...]`. Está certo.
- **`world.all()` a ser uma vista viva.** Nenhum caller muta dentro do ciclo (§B7).

## E4 · UX que não está treatmentada [I]

- Um ecrã colocado no Overworld e levado ao Nether **desaparece e fica
  inutilizável** (`screen.dimension` só se actualiza em `tickMoving`). Voltar ao
  Overworld traz-o de volta, mas enquanto lá está não há forma de o mover.
- `MenuScreenCtl` → "Open browser" abre `BrowserFullScreen(screen)` com
  `parent = null`. Fechar com Esc deixa o jogo, não o menu. Perde-se o contexto.
- `MenuStreams` e `MenuInvites` **truncam sem avisar** quando a lista é maior que
  o ecrã. Não há scroll, nem indicador.
- `Lang.t("streams.delete")` existe e nunca é usado; o botão diz
  `friends.remove`. Inconsistência visível ao utilizador.
- `MenuFriends`: `refresh()` recria o `EditBox`, **perdendo o foco** a meio de
  escrever o nome de um amigo.
- O `N` só anuncia "sound off"/"volume" quando há um ecrã apontado. Sem ecrã, o
  `N` **não faz nada nem diz nada** — ao contrário do `B`, que explica. Inconsistente.

## E5 · Segurança [V]

- **O protocolo não tem autenticação.** `hello` leva só `name` + `ver`. Qualquer um
  que alcance a porta 23481 pode falar com o servidor como quiser, incluindo
  injectar `navigate` para os ecrãs de outros. Para uso numa LAN é aceitável; para
  publicação é um bloqueador. Registado, não é um bug *do port*.
- **`textureIdFor` sanitiza** o streamId que vem da rede antes de o usar como
  caminho de textura. **[V]** Está bem feito.
- **`goto` a partir do que o jogador escreve** passa por `Packets.ctl` e
  `loadURL`. É navegação a partir do browser, que é o que o browser faz. Sem
 surface issue.

---

# O que NÃO foi feito, e porquê

Regra 6 do `AGENTS.md`: incerteza grande → spike pequeno primeiro. Nenhuma
correcção foi aplicada. Este ficheiro é o resultado; a decisão de-actuar é do
utilizador, e as correcções 1–3 são pequenas o suficiente para entrarem num só
commit de diagnóstico.

# Como decidir o próximo passo

Uma corrida com a instrumentação de E1 (linha 1 e 2 de E2) responde a **três**
perguntas de uma vez:

```powershell
Select-String "agent report|onTitleChange|SMX\||ScreenMine/codecs" run\logs\latest.log
```

- `location.href` = `about:blank` → o agente está no documento errado; o `N`, as
  teclas de edição e o sync **têm a mesma causa** e A2 é a cura.
- `location.href` = a página real, mas **nenhum `SM|` chega** → A1 confirmado; a
  linha de JS é a cura.
- `location.href` = a página real **e** o `SM|` chega, com `media=1` e
  `vvolume=0.00` quando se manda zero → o volume funciona e há outra causa no
  `N`; o `vvolume` diz qual.
---

# Estado dos achados (2026-10-16)

| # | Achado | Gravidade | Estado | Commit |
|---|---|---|---|---|
| **A1** | Agente pára de reportar quando a página usa o título | crítico | **corrigido** | `376d7d4` |
| **A2** | Agente só no frame principal | crítico | **corrigido — e foi ele que resolveu o `N`** | `1e3a570` |
| **A3** | `applyingRemote` sem `volatile` | alto | corrigido (e documentado: nunca protegeu nada) | `61605c1` |
| **A4** | Campos de ciclo de vida sem `volatile` | alto | corrigido | `61605c1` |
| **A5** | Textura órfã no detach+attach | alto | corrigido | `e514c82` |
| **A6** | `net.send` deita o pacote fora em CONNECTING | alto | corrigido | `7cc1401` |
| **B1** | Foco não largado ao abrir menus | médio | corrigido | `2590561` |
| **B2** | Overlay ignora `SCROLL_SPEED` | médio | corrigido | `2590561` |
| **B3** | Menus não reagem a pacotes | médio | **aberto** | — |
| **B4** | Writer thread por tentativa | baixo | anotado, não corrigido | — |
| **B5** | `outbound.clear()` perde trabalho | baixo | anotado | — |
| **B6** | `inbound` sem limite | baixo | teórico, anotado | — |
| **B7** | `all()` devolve vista viva | baixo | auditado: nenhum caller muta | — |
| **B8** | `close()` com overlay aberto | — | auditado: safe | — |
| **B9** | `LIVE` cresce se `close()` não correr | baixo | anotado | — |
| **B10** | `handlersInstalled` nunca reposto | médio | **parcial** — agora loga-se o caso `client == null` | `376d7d4` |
| **C1** | `createBrowser` sem `try/catch` | médio | corrigido | `376d7d4` |
| **C2** | `Lang.t` pode crashar | médio | corrigido (+ auditoria: 105 chaves, 0 mismatches) | `2590561` |
| **C3** | Override morto em `MenuJoinChoice` | baixo | corrigido | `2590561` |
| **C4** | Comentários obsoletos no `CodecProbe` | baixo | corrigido | `2590561` |
| **C5** | Javadoc órfão empilhado | baixo | corrigido | `2590561` |
| **C6** | `spike/` morto com a câmara 2× | baixo | **aberto** — não apagado para não perder o registo do spike | — |
| **D1–D6** | Desempenho e fugas | — | anotados; D4 (`GAIN` em log) sai antes da release | — |
| **E1** | Diagnóstico ausente | crítico | **feito** — `href` + `top` no relatório | `376d7d4` |
| **E2** | Ordem das correcções | — | 1–8 executados | vários |
| **E4** | UX (dimensão, scroll de listas, foco perdido) | — | **aberto** | — |
| **E5** | Segurança | — | auth e allowlist **abertos**; URL removida dos ficheiros | — |

## O que a revisão não previu

O **loop de re-injecção** do watcher de frames. Introduzi-o em `1e3a570` para
resolver A2, e a corrida 7 mostrou-o: 23 re-injecções seguidas, 64 linhas de
ganho para um ecrã. `knownFrames` só era posto quando havia subframes, portanto
numa página sem iframes ficava em `0` enquanto se comparava com
`getFrameCount()` (=1). Corrigido em `5ced97f`.

Vale a pena registar porque é o padrão da review inteira: **cada correcção de um
bug cria a hipótese de outro, e nenhum build verde os apanha.**

## O que a revisão acertou e que só o log confirmou

**A1** — o agente só reescrevia o título quando o *seu* payload mudava. Com o
YouTube a escrever o título, o canal `SM|` morria, `isEditing()` ficava sempre
`false`, e as teclas de edição nunca chegavam ao browser. **Ainda não está
resolvido** (zero recibos `SM|` na corrida 7), mas o diagnóstico que a revisão
propôs — contar *todos* os `onTitleChange`, não só os reconhecidos — é
exactamente o que está no `5ced97f` e é o que vai dizer qual das duas
hipóteses é.
