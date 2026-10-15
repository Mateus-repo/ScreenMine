# Codecs e teclado — registo de investigação (branch `spike/codecs`)

**Âmbito deste ficheiro.** Registo da investigação feita na branch `spike/codecs`
(que nasce de `port/26.3`, por isso volta com fast-forward). Quando o trabalho
for absorvido, este ficheiro é apagado e o que interessa passa para o
`progresso.md`. Não é um plano paralelo — é o registo do que foi verificado e do
que falta.

---

## 1. Pergunta

Porque é que o vídeo toca no YouTube e não noutros sítios?

| Sítio | Estado |
|---|---|
| youtube.com | ✅ reproduz |
| medal.tv | ❌ `Playback failed because the media is not supported by this client` |
| tiktok.com | ❌ idem |
| `https://strefizstreamsrv.ddns.net/web/#/home` | ❌ idem |

A hipótese inicial foi "o Chromium do JCEF não traz codecs proprietários". **Esta
hipótese foi investigada e ficou mais fraca, não mais forte.** Ver §3.

---

## 2. Proveniência do binário (verificada, não adivinhada)

Cadeia de dependências real, lida dos artefactos no cache do Gradle:

```
net.dimaskama:mcef-modern:0.3.3+mc26.3.jcef146.0.10     (LGPL-2.1)
├── META-INF/jars/jcef-api-jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179.jar
└── META-INF/jars/jcefmaven-146.0.10.jar
```

`build_meta.json` dentro do `jcef-api`:

```json
{
  "jcef_url":     "https://bitbucket.org/chromiumembedded/java-cef/commits/d3de8278626c160d4631db3fda2df7b77cc1e5c4",
  "release_tag":  "jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179",
  "release_url":  "https://github.com/jcefmaven/jcefbuild/releases/tag/1.0.70",
  "platform":     "*"
}
```

E o que o `jcefmaven` descarrega de facto para `run/config/mcef-modern/jcef/`:

```
release_download_url: https://github.com/jcefmaven/jcefbuild/releases/download/1.0.70/windows-amd64.tar.gz
```

Ou seja: **MCEF Modern 0.3.3 → JCEF d3de827 → CEF 146.0.10 → Chromium 146.0.7680.179,
binários do `jcefmaven/jcefbuild` release 1.0.70.**

---

## 3. O que a investigação estática mostrou (e porque enfraquece a hipótese)

Percorri a cadeia de build inteira à procura de `ffmpeg_branding`:

| Ficheiro | `ffmpeg_branding` / `proprietary_codecs` |
|---|---|
| `jcefbuild/compile_windows.bat` | ausente |
| `jcefbuild/scripts/run_windows.bat` | ausente |
| `jcefbuild/.github/workflows/build-windows-amd64.yml` | ausente |
| `jcefbuild/DockerfileWindows` | ausente |
| `java-cef@d3de827/CMakeLists.txt` | ausente |

**O `jcefmaven` não passa nenhum argumento GN de codecs.** A build é um `cmake`
+ `ninja` normality sobre o `CMakeLists.txt` do JCEF, que por sua vez descarrega a
distribuição binária padrão do CEF.

O que isso significa, com cuidado:

- ❌ **Não** se pode concluir "o jcefmaven desligou os codecs". Ele não mexe nisso.
- ⚠️ **Também não** se pode concluir "logo há codecs". Depende inteiramente do
  que a distribuição binária padrão do CEF trae — e isso **não está no Java**.
- Aery no binário local (`libcef.dll`, 262 MB) não ajuda: Chromium traz as
  tabelas de codecs compiladas em ambos os casos e o que decide é
  `media::PROPRIETARY_CODECS`, uma constante *inline* em código nativo. Não há
  string para fazer `grep`.

**Conclusão: só a sondar a página no browser real se decide.** Daí a sonda do §4.

> Nota sobre o resto da pesquisa: registei que H.264 tem questões de patentes e
> que DRM (Netflix, Disney+) não vai funcionar por esta via. Isto fica anotado
> para uma eventual publicação; hoje é uso pessoal.

---

## 3b. Thread `cinemamod/mcef#28` — lida por inteiro

Link: <https://github.com/cinemamod/mcef/issues/28> ("Add binary patcher system to
include additional Chrome codecs (AVC/MPEG-4/Widevine)"). **Fechado.**

O que o issue propõe, e porquê:

> For licensing reasons I cannot host the binaries for a build of Chromium with
> additional codecs. There are royalties for H.264... However, it is possible I can
> host **binary diffs** between the libre version and the proprietary version. The
> end user is compiling the final product on their machine.

Mecanismo do autor (`ds58`), em 5 passos: build sem codecs → build com codecs →
`bsdiff` entre os dois → manifest com hashes por build → upload dos patches para
o bucket dele. O utilizador final descarrega o JCEF **sem** codecs e aplica os
patches em cima. Ele diz explicitamente:

> This is also a legally gray area for sure. I'm not a lawyer so don't take this
> as fact.

### "Irrelevante com MCEF 2.x" — o que significa **de facto**

O comentário que fecha o issue é do próprio autor, 2023-11-08:

> This issue is now irrelevant with MCEF 2.x. The binaries that are included
> contain the codecs

**Isto NÃO quer dizer que o JCEF/CEF passou a trazer codecs.** Quer dizer que os
binários que o **CinemaMod** distribui já vêm com codecs, porque o CinemaMod é
quem faz a dance dos patches e os embute. O distributable do `jcefmaven`, que é o
nosso, não passa por aí.

Ou seja: a rota continua a ser *"usa os binários do CinemaMod"* — e o
`jcef-natives` do `jcefmaven` nunca teve nem tem variante com codecs.

### Estado actual desse projecto

- `CinemaMod/CinemaMod-Fabric` → **404, já não existe.**
- `cinemamod/mcef` → vivo, último push **2025-08-11**, mas é o MCEF antigo
  (1.12/1.16). Não tem binários para CEF 146 / Windows.
- Outro comentário do issue (2023-10-25, `JonnygamingTv`): *".webm seem to work"* —
  consistente com o que vemos (YouTube, que serve VP9/Opus, funciona).

**Conclusão:** a rota dos patches bsdiff está **indisponível** para o nosso par
exacto (JCEF 146.0.10 / Windows amd64 / Minecraft 26.3).

---

## 3c. De onde o mcef-modern descarrega mesmo (verificado do bytecode)

De `me.friwi.jcefmaven.CefAppBuilder`, dois mirrors:

```
https://github.com/jcefmaven/jcefmaven/releases/download/{mvn_version}/jcef-natives-{platform}-{tag}.jar
https://repo.maven.apache.org/maven2/me/friwi/jcef-natives-{platform}/{tag}/jcef-natives-{platform}-{tag}.jar
```

### Existe variante com codecs na mesma origem? **Não.**

Maven Central, grupo `me.friwi`, total **14 artefactos**:

```
gluegen-rt, jcef-api, jcefmaven, jogl-all, tello4j
jcef-natives-linux-{amd64,arm,arm64,i386}
jcef-natives-macosx-{amd64,arm64}
jcef-natives-windows-{amd64,arm64,i386}
```

A família `jcef-natives-*` é **só por plataforma**. Não há `-chrome`, não há
`-proprietary`, não há nada. Confirmado também nos releases do GitHub: cada tag
publica exactamente um conjunto de natives por plataforma.

(Nota: o `jcefmaven` já vai no **CEF 152.0.6**. Não dá para aproveitá-lo: o
`jcef.dll` das natives é a ponte JNI e tem de bater certo com o `jcef-api` 146 que
o `mcef-modern` embute. Misturar 152 com api 146 parte.)

---

## 3d. O mcef-modern deixa apontar para outro binário? **Não.**

`MCEFApi` (javap) tem exactamente isto, e mais nada:

```java
public static MCEFApi$Initialization initialize();
public static CompletableFuture<MCEFApi> getInstanceFuture();
public static MCEFApi getInstance();
public abstract MCEFBrowser createBrowser(String, boolean);
```

Sem setter de caminho, sem config, sem override. A única superfície é o disco:
`run/config/mcef-modern/jcef/`.

### E se substituirmos os ficheiros no disco?

`CefInstallationChecker.checkInstallation(File)` verifica **só** isto:

1. `build_meta.json` existe
2. `install.lock` existe
3. `CefBuildInfo.getReleaseTag()` do disco == o do classpath
4. `getPlatform()` == plataforma actual

**Não há verificação de hash, nem de conteúdo, das DLLs.** Portanto uma
substituição manual sobrevive ao próximo arranque — o instalador não re-descarrega.

Isto explica também o aviso do thread do vvvv: se só trocares o `libcef.dll`, o
`jcef.dll` / `chrome_elf.dll` / `resources.pak` ficam de outra build e nada
verifica isso. Tem de ser a build **coerente e completa**, de uma só vez.

⚠️ Não é uma via suportada. E como não há nenhuma build com codecs publicada
para o nosso par (§3b), hoje não há o que trocar.

---

## 3e. Custo/risco das saídas, se a sonda confirmar que são codecs

Ordenadas por custo. **Nada disto é para fazer antes de ver o log da sonda.**

**(a) Transcodificar no servidor para VP9/AV1 + Opus (webm)** — *a mais barata*
- Custo: zero no mod. Só configuração no servidor.
- Risco: depende de o servidor ter VP9/AV1 como destino de transcodificação.
  A verificar no painel (o utilizador trata disto).
- Licença: nada a fazer. VP9/AV1/Opus são livres.
- **Recomendada se o servidor suportar.**

**(b) Build própria do JCEF 146 com `proprietary_codecs=true ffmpeg_branding=Chrome`**
- Custo: Docker no Windows + VS2022 Build Tools + a distribuição binária do CEF
  (~15 GB) + checkout do Chromium. Ordem de grandeza: **200–300 GB de disco e
  várias horas**, com múltiplas tentativas. Não é um fim-de-semana.
- Risco: a combinação de flags pode não ser estável; e é preciso uma build
  *coerente* completa (§3d).
- Licença: uso pessoal é zona cinzenta (o próprio autor do #28 diz isso);
  **redistribuir exige licenças**. Anotado para publicação futura.

**(c) CEF só com hardware decoding (issue upstream 3559)**
- Custo: também exige build própria, e é **mais invasiva** (remover o ffmpeg em
  vez de o ligar).
- Limite: sem software de reserva — uma máquina sem decodificação por hardware
  não toca nada.
- Só faz sentido se (b) for mesmo necessária.

**(d) Descodificar fora do Chromium (libvlc/ffmpeg → mesma textura)**
- Custo: grande. Dois pipelines de vídeo, sincronização, áudio, seek.
- Risco: alto, e é trabalho que não se aproveita no browser.
- Só se (a) e (b) falharem.

---

## 5b. **CAUSA RAIZ encontrada nos logs de 2026-10-15 12:41**

O log dessa corrida **nao tem nenhuma linha `ONLOADEND`**. E nenhuma
`AGENT injected by retry`. Ou seja: **o agente de pagina nunca foi injectado.**

Isto explica sozinho o bug B (tecla N) e grande parte do bug A:

| O que o agente faz | O que acontecia sem agente |
|---|---|
| `setInterval` que lê `window.__smVol` | `applyGain(0)` escrevia `window.__smVol=0` numa pagina que ninguem lia -> **N dizia "sound off" no chat e nada acontecia** |
| shim de edicao `__smEdit` | Backspace/Delete/setas nunca faziam nada no mundo |
| relatorio de playback no titulo | sem estado de reproducao |

E o teste do N que o utilizador fez foi exactamente no sitio onde falha: **ecra a
tocar no mundo, sem overlay aberto.**

### Porque o agente nunca era injectado — duas causas

**1. `onLoadEnd` nunca dispara.** `MCEFApiImpl.createBrowser` chama
`createImmediately()` (verificado de bytecode), ou seja, o carregamento começa
**dentro** de `createBrowser()`. O codigo registava os handlers *depois*:

```java
browser = MCEFApi.getInstance().createBrowser(currentUrl, false);   // ja comeca a carregar
browser.resize(...);
installHandlersOnce(...);      // tarde demais se a pagina for rapida
LIVE.put(...);
```

O comentario no codigo descrevia exactamente este bug e a correccao — *"Create on
about:blank, then navigate"* — mas o codigo nao a implementava: estava atras de
`if (false)`.

**2. `pumpAgent()` so era chamado pelo overlay.** `grep` confirma: o unico caller
e `BrowserFullScreen.tick()`. Com o overlay fechado, **nada** injectava o agente.

### Correcoes aplicadas

1. `createBrowser("about:blank")` -> `installHandlersOnce` -> `LIVE.put` ->
   `loadURL(alvo)`. Agora a navegacao real acontece com tudo registado.
2. `ScreenMineClient.pumpAgents()` corre no tick do cliente para **todos** os
   ecrãs, em vez de so o overlay.
3. O log das teclas passou a dizer `BACKSPACE` em vez de `271`
   (`InputConstants.getKey(event).getName()`), para o teste ser legivel.

**Isto ainda nao foi provado em jogo.** Preciso de uma corrida com `ONLOADEND`
a aparecer e com o `N` a calar a musica no mundo.

---

## 4. A sonda (`dev.screenmine.diag.CodecProbe`)

Desligada por omissão. Para ligar, em `run/config/screenmine.properties`:

```properties
debug.codecs=1
```

Depois carregar em qualquer página. A resposta aparece no `latest.log`, no logger
`ScreenMine/codecs`, uma linha por codec:

```
[Render thread/INFO] (ScreenMine/codecs) ---- codec probe: ... ----
[Render thread/INFO] (ScreenMine/codecs)   avc1.42E01E.canPlayType=probably
[Render thread/INFO] (ScreenMine/codecs)   avc1.42E01E.mse=yes
[Render thread/INFO] (ScreenMine/codecs)   avc1.64001F.canPlayType=...
[Render thread/INFO] (ScreenMine/codecs)   mp4a.40.2.canPlayType=...
[Render thread/INFO] (ScreenMine/codecs)   hev1.canPlayType=...
[Render thread/INFO] (ScreenMine/codecs)   vp09.00.10.08.canPlayType=...
[Render thread/INFO] (ScreenMine/codecs)   av01.0.05M.08.canPlayType=...
[Render thread/INFO] (ScreenMine/codecs)   opus.canPlayType=...
[Render thread/INFO] (ScreenMine/codecs)   vorbis.canPlayType=...
[Render thread/INFO] (ScreenMine/codecs)   MediaSource=yes
[Render thread/INFO] (ScreenMine/codecs)   ua=Mozilla/5.0 ...
[Render thread/INFO] (ScreenMine/codecs)   secureContext=yes
[Render thread/INFO] (ScreenMine/codecs) ---- end codec probe ----
```

Leitura: `canPlayType` dá `""` / `maybe` / `probably`. Se `avc1.*` vier `-` ou
`""` e `vp09.*` vier `probably`, a hipótese dos codecs confirma-se e a causa é o
build do Chromium. Se `avc1.*` vier `probably`, **os codecs não são a causa** e o
problema é outro (certificado, sessão/login, autoplay).

### Porquê o canal do `document.title`

`CefBrowser.executeJavaScript` **não tem overload com callback** (verificado com
`javap` no `jcef-api`):

```
public abstract void executeJavaScript(java.lang.String, java.lang.String, int);
```

Logo não há forma de ler o valor de retorno. A sonda、借 o canal que o agente de
página já usa para o estado de playback, com um prefixo novo `SMX|`. O agente
continua a reescrever o título a cada 250 ms, por isso a sonda corre **uma vez
por browser** (o suporte a codecs é do build do Chromium, não da página).

---

## 5. Teclado — o que ficou provado (e o que não)

Isto é do bug A (prioridade 1), mas a investigação do §4 passou por aqui e
descobriu-se factos que mudam o plano.

Cadeia **verificada com `javap`** (mcef-modern 0.3.3 + jcef-api):

```
MCEFBrowser.onKeyPressed(KeyEvent)      → java.awt.event.KeyEvent id 401  (KEY_PRESSED)
MCEFBrowser.onCharTyped(CharacterEvent) → java.awt.event.KeyEvent id 400  (KEY_TYPED, codepoint)
MCEFBrowser.onKeyReleased(KeyEvent)     → java.awt.event.KeyEvent id 402  (KEY_RELEASED)
                ↓
        CefBrowser_N.sendKeyEvent(java.awt.event.KeyEvent)   [protected final]
                ↓
        CefBrowser_N.N_SendKeyEvent(java.awt.event.KeyEvent) [native]
```

**Achado 1 — o passo final é nativo.** `sendKeyEvent` é
`protected final` e delega imediatamente para `N_SendKeyEvent`, nativo. Portanto
**é impossível dizer pelo `javap` se o JCEF sintetiza ou não um evento `CHAR`
para teclas não-imprimíveis.** A hipótese do utilizador ("falta o evento
CHAR") **não é confirmável nem refutável pelo lado Java**. Fica registada como
hipótese, não como facto.

**Achado 2 — o que o Minecraft emite está no `KeyboardHandler`:**

```
net.minecraft.client.KeyboardHandler
  public void keyPress(long, int, net.minecraft.client.input.KeyEvent);
  public void charTyped(long, net.minecraft.client.input.CharacterEvent);
```

Os dois existem. *Quem* chama `charTyped` e para que teclas está na camada
window/SDL3, e isso ainda não foi verificado.

**Achado 3 — as ferramentas para os atalhos `Ctrl` existem.** `CefFrame` tem:

```
public abstract void selectAll();
public abstract void copy();
public abstract void cut();
public abstract void paste();
public abstract void undo();
public abstract void redo();
```

E `CefBrowser` tem `canGoBack() / goBack() / canGoForward() / goForward()`, que
resolve `Alt+←/→`. Portanto **os atalhos do bug A2/A3 são implementáveis com API
pública, sem mixins e sem shim JS.** Falta só querer o clipboard do *sistema*
(é o `ClipboardManager` do `KeyboardHandler` do Minecraft, não o do browser).

**Achado 4 — o instrumento de teste já existe.** O `BrowserFullScreen` já faz log
de cada evento de entrada no logger `ScreenMine/input`:

```
IN mouseClicked btn=1 doubled=false raw=(..,..) px=(..,..) scale=2
IN scroll raw=(..,..) vertical=-1.0 px=(..,..)
IN keyPressed key=94 keycode=1073741918 mods=4096
IN charTyped cp=54 '6'
IN keyReleased key=94
```

---

## 6. O teste de 2 minutos que decide o bug A (sem escrever código)

Com o overlay aberto (`B`), numa caixa de texto (a de pesquisa do YouTube serve):

1. escreve `abcdef`
2.prime `Backspace`
3. prime `Enter`
4. prime `Ctrl+A`, depois `Ctrl+C`

Depois no `latest.log`:

```powershell
Select-String "IN keyPressed|IN charTyped" run\logs\latest.log | Select -Last 20
```

Como ler:

| O que aparecer | Conclusão |
|---|---|
| `IN keyPressed key=…` **e** `IN charTyped` | o Minecraft emite os dois → o problema está a jusante, no JCEF/CEF |
| `IN keyPressed key=…` **sem** `IN charTyped` | **hipótese confirmada**: o Minecraft não emite `CharacterEvent` para teclas não-imprimíveis |

Isto decide a direção do bug A com um commit de dados, não com um palpite.

---

## 6b. O que eu **nao** consigo fazer

O ponto 3 do pedido — *"corre a sonda `debug.codecs=1` numa página do YouTube e
noutra do servidor e mostra a tabela"* — **nao consigo executá-lo.** A sonda precisa
de: cliente do Minecraft a correr com interface gráfica, um ecrã criado no mundo,
e uma navegação real até à página. Não tenho como conduzir isso. **Só tu podes
correr a sonda**, e o resultado da tabela só existe depois de o fazeres.

O mesmo se aplica ao teste de 2 minutos das teclas (§6) e ao teste da tecla `N`.

Não vou escrever uma tabela de codecs aqui a partir de palpites.

---

## 7. Estado

| Item | Estado |
|---|---|
| Proveniência do binário | ✅ verificada (§2) |
| Pesquisa de flags de codec | ✅ feita — **não sustenta** a hipótese (§3) |
| Thread `cinemamod/mcef#28` | ✅ lida; "irrelevante com 2.x" = binários do CinemaMod, não o JCEF (§3b) |
| Origem dos binários / variante com codecs | ✅ **não existe** no `jcefmaven` (§3c) |
| mcef-modern aceita outro binário? | ✅ **não**, via API; só por disco, e sem verificação de hash (§3d) |
| Custo das saídas | ✅ estimado (§3e) |
| Sonda de codecs em código | ✅ feita, compilada, **por testar em jogo** (§4) |
| **Tabela de codecs real** | ❌ **não existe** — precisa de correr em jogo (§6b) |
| Bug A: cadeia de teclas | ✅ verificada (§5) |
| Bug A: hipótese do `CHAR` | ⏳ **aberta** — decide o teste de §6 |
| Bug B: tecla `N` (mutar) | ⏳ por investigar, falta saber o contexto do teste |

**Nada disto foi provado em jogo.** A sonda compila; falta o log.