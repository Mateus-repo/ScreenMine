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

## 7. Estado

| Item | Estado |
|---|---|
| Proveniência do binário | ✅ verificada (§2) |
| Pesquisa de flags de codec | ✅ feita — **não sustenta** a hipótese (§3) |
| Sonda de codecs em código | ✅ feita, compilada, **por testar em jogo** (§4) |
| Bug A: cadeia de teclas | ✅ verificada (§5) |
| Bug A: hipótese do `CHAR` | ⏳ **aberta** — decide o teste de §6 |
| Bug B: tecla `N` (mutar) | ⏳ por investigar, falta saber o contexto do teste |
| Codecs proprietários / alternativas | ⏳ só depois do log da sonda |

**Nada disto foi provado em jogo.** A sonda compila; falta o log.