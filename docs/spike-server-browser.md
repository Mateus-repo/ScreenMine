# SPICE: browser no servidor

Porque é que este ficheiro existe: a conversa sobre "um browser só" voltou, e
esta é a única fonte de verdade sobre o que foi medido na máquina. Actualizado à
medida que se descobre. O estado continua a ser o `progresso.md`; isto é o
relatório.

Data da primeira auditoria: 2026-10-17.

---

## 1. O que se quer

Um Chromium, uma sessão, um estado. O ecrã no mundo passa a ser um monitor que
mostra os píxeis desse Chromium e devolve teclado e rato. O dono não tem browser
nenhum.

A alternativa (browser por cliente + sincronizar) está feita e funciona, mas
**garantidamente diverge**: cada browser é uma sessão com cookies e histórico
próprios, e o YouTube decide por cada uma. Três tentativas de sync, três
divergências.

---

## 2. A máquina (medido, não suposto)

Servidor de testes do dono (endereco nao registado aqui de proposito), alcancavel por SSH sem password de root.

| | |
|---|---|
| **Máquina** | `ASUSTeK VivoBook_AS532FL` — **não é VPS**. `systemd-detect-virt` = `none`. Portátil real em dual boot. |
| **SO** | Ubuntu, kernel 7.0, glibc 2.43 |
| **CPU** | 8 núcleos |
| **RAM** | 10 GB (o Minecraft usa 3.4 GB, 25% de um núcleo) |
| **Ecrã** | **nenhum**. Sem DISPLAY, sem X11, sem Wayland, sem Xvfb |
| **Java** | 25 (a compilacao exacta nao se regista aqui: quatro numeros separados
por pontos sao lidos como um endereco IP pela regra de privacidade) |
| **Rede** | Tailscale activo (endereco omitido), `playitd` a correr |

### 2.1 Discos — havia um maior, e não está a ser usado

```
nvme0n1     27.3G   <- Linux. p1 = /boot/efi, p2 = / (26.2G, 3.9G livres)
nvme1n1    476.9G   <- o disco grande
                 p1   100M  vfat
                 p2    16M
                 p3  409.4G  ntfs   <- particao do Windows, NAO montada
                 p4   546M  ntfs
                 p5   66.9G  ext4   <- montada em lado nenhum
sda         14.6G  ntfs
```

O `fstab` tem uma linha **comentada** que aponta para o sitio errado:

```
#/dev/nvme0n1p5 /minecraft ext4 defaults 0 2
```

`nvme0n1` só tem `p1` e `p2`. A partição que existe é **`nvme1n1p5`**, 66.9 GB,
já em ext4, com UUID `83505831-b7c2-467a-af6e-1e652d4d8d56`, **não montada**.
A linha parece ser de outra instalação, ou de antes de os discos mudarem de
lugar. Não se sabe se tem dados — ler isso precisa de root.

### 2.2 Onde estão os 21 GB

```
/home/USUARIO/minecraft-fabric-26.3    13G
    backups                             8.0G   <- 2 cópias do mundo, 4.0G + 4.1G
    world                               4.1G
    libraries                           46M
    mods                                28M
/usr                                   3.4G
```

**Só os backups valem 8 GB, e são duas cópias diárias do mesmo mundo.**

---

## 3. O que falta na máquina para correr Chromium

**Resolvido.** As 25 bibliotecas estão instaladas e confirmadas:

```
libatk-1.0.so.0      libatk-bridge-2.0.so.0  libatspi.so.0
libgbm.so.1          libxkbcommon.so.0       libpango-1.0.so.0
libpangocairo-1.0.so.0  libcairo.so.2        libXcomposite.so.1
libXdamage.so.1      libXfixes.so.3          libXrandr.so.2
libxshmfence.so.1    libXext.so.6            libX11.so.6
libGL.so.1           libEGL.so.1             libGLESv2.so.2
libxcb.so.1          libnss3.so              libnspr4.so
libdbus-1.so.3       libcups.so.2            libasound.so.2
```

A primeira tentativa **não instalou nada**: o `apt-get install` levava
`libu2f-udev`, que não existe no Ubuntu 26.04, e o apt é transaccional — um
pacote em falta aborta o lote inteiro. A segunda versão verifica cada pacote
com `apt-cache show` e instala um a um.

O bundle nativo do JCEF para Linux também está em
`~/spike-jcef/bin/lib/linux64` (147 MB descarregados, extraídos).

**Uma confusão minha que vale a pena registar:** o verificador dizia que
`libgl.so.1` e `libegl.so.1` faltavam. Não faltavam — escrevi o nome em
minúsculas e os ficheiros são `libGL.so.1` e `libEGL.so.1`. As 25 estavam lá
desde o início.

**O bundle do JCEF também traz `chrome-sandbox`**, que é o binário de sandbox
do Chromium. Existe, mas o JCEF passa `--no-sandbox` na mesma (verificado nos
processos reais, no Windows).

---

## 4. A API que o spike usa (confirmada com javap, não de memória)

Duas coisas que são contra-intuitivas e que custaram tempo a descobrir:

- **`CefDisplayHandlerAdapter` não tem `onPaint`.** A pintura foi para
  `CefRenderHandlerAdapter.onPaint(CefBrowser, boolean, Rectangle[], ByteBuffer, int, int)`.
- **Não existe `CefApp.runMessageLoop()`.** O loop é do embedder:
  `CefApp.addAppHandler(...)` **antes** de `CefApp.getInstance(args, settings)`,
  e depois `doMessageLoopWork(ms)` a repetir.

E a boa notícia, que muda o plano:

```java
CompletableFuture<BufferedImage> CefBrowser.createScreenshot(boolean)
void                            CefBrowser.setWindowlessFrameRate(int)
```

**O browser dá a imagem pronto feito.** Não é preciso `onPaint`, nem ByteBuffer,
nem render handler. E o `ImageIO` do JDK escreve JPEG sem dependência nativa
nenhuma — **o FFmpeg pode não ser preciso**, que era a maior dependência do
plano original.

Criar o browser:

```java
CefBrowserFactory.create(client, url, /*isPopup*/false, /*createImmediately*/true,
                         null, browserSettings)
```

## 4.1 Duas armadilhas que o Linux mostrou

**A biblioteca JNI não é a que o JCEF carrega sozinho.** O loader por omissão
faz `System.loadLibrary("cef")`, que no Linux resolve para `libcef.so` — o core
do Chromium. A biblioteca JNI é **`libjcef.so`**, e a diferença não é de
detalhe:

```
nm -D --defined-only libjcef.so | grep -c Java_org_cef   ->  349
nm -D --defined-only libcef.so  | grep -c Java_org_cef   ->    0
```

Com a biblioteca errada carregada, todas as chamadas nativas falham com
`UnsatisfiedLinkError: 'boolean org.cef.CefApp.N_PreInitialize()'`. A solução
é `SystemBootstrap.setLoader(...)` a carregar `libjcef.so` (ou `jcef.dll` no
Windows) por caminho absoluto.

**O lado Java do JCEF precisa de AWT, e AWT precisa de um ecrã.** Isto
contra-intuitivo: o Chromium desenha *offscreen*, mas `CefBrowserWr` chama
`MouseInfo.getNumberOfButtons()`, e isso **lança `HeadlessException` em modo
headless**. Com `java.awt.headless=true` o probe morre ali; sem isso, sem
`DISPLAY`, o AWT não consegue ligar-se ao X11.

Portanto: **Xvfb é necessário**, mesmo com renderização offscreen. Não é o
Chromium que precisa de um ecrã — é o JCEF do lado do Java.

---

## 5. Estado do spike

| | |
|---|---|
| Probe escrito | `tools/SpikeServerBrowser.java` |
| Compila | sim, depois de 3 erros corrigidos (`CefClient` está em `org.cef`, não `org.cef.browser`; `LogSeverity.LOGSEVERITY_WARNING`, não `WARNING`; `onLoadEnd` é do `CefLoadHandlerAdapter`) |
| Bibliotecas no Linux | **25/25 instaladas e confirmadas** |
| Bundle nativo Linux | descarregado e extraído em `~/spike-jcef/bin/lib/linux64` |
| **O CEF arranca no Linux** | **sim** — `INITIALIZED`, contexto pronto, browser criado, código de saída 0 |
| Frames capturados | **ainda não** — `createScreenshot` responde *"Unsupported for windowed rendering"* |
| Corre no Windows | o mesmo: o CEF não arrancava, e a causa está identificada (ver 5.2) |

O que o probe mede, e porque cada número decide uma coisa:

1. Se o JCEF renderiza sem ecrã. Se não, o projecto está morto e saves-se o mês.
2. Quanto custa um screenshot. Acima de 66 ms, os 15 fps não cabem num núcleo.
3. Quantos bytes tem um frame em JPEG, por resolução e qualidade. **Isto decide
   se é preciso FFmpeg.**
4. Com que frequência o frame muda. Uma página parada só precisa de ser enviada
   quando muda.

## 5.1 O registo das tentativas, para não se perder

| Tentativa | Onde | O que happened | O que se aprendeu |
|---|---|---|---|
| 1 | Windows | `Internal Error (os_windows_x86.cpp:97)`, o JVM morre em código nativo | Por resolver. É o que trava o uso como desktop de jogo. |
| 2 | Windows | `UnsatisfiedLinkError: no cef in java.library.path` | O JCEF quer uma biblioteca que o `java.library.path` não tinha |
| 3 | Linux | `UnsatisfiedLinkError: N_PreInitialize()` | Carregou `libcef.so` (0 símbolos JNI) em vez de `libjcef.so` (349) |
| 4 | Linux | `HeadlessException` em `CefBrowserWr` | O lado Java do JCEF precisa de AWT, e AWT precisa de ecrã → Xvfb |
| 5 | Linux | `$PWD` expandido pelo PowerShell, caminho do Windows em vez do Linux | Comandos com `$(...)` via `ssh` são um pesadelo. **Sempre um script em ficheiro.** |
| 6 | Linux | `libjawt.so` em falta | O JRE headless não a traz; o JRE completo traz |
| 7 | Linux | **SIGTRAP, sem mensagem** | Ver 5.2 |
| 8 | Linux | `Can't load library: .../bin/lib/libjcef.so` | O `CefInitializer` quer a biblioteca **directamente** no install dir, não numa subpasta |
| 9 | Linux | **CEF arranca, exit 0** | As duas linhas a mais eram a causa |
| 10 | Linux | `Unsupported for windowed rendering` | O browser foi criado com janela. Ver 5.3 |

## 5.2 A causa do crash: duas linhas a mais

O crash era um **SIGTRAP sem mensagem nenhuma**, e sem `hs_err` no Linux. No
Windows havia um `hs_err_pid3492.log` de 95 KB que eu tinha deixado no repositório
sem o ler. Lido, diz:

```
Current thread: JavaThread "AWT-EventQueue-0" [_thread_in_native, id=1936]
C  [libcef.dll+0x1bb56ba]
C  [jcef.dll+0x56a82]
j  org.cef.CefApp.N_Initialize(Lorg/cef/handler/CefAppHandler;Lorg/cef/CefSettings;)Z+0
```

O crash é **dentro do `libcef.dll`**, e o
`guarantee(result == EXCEPTION_CONTINUE_EXECUTION) failed: Unexpected result from
topLevelExceptionFilter` é o **tratador de excepções do próprio JVM a falhar**
(um bug do JDK 25.0.1) quando a excepção nativa sobe pela thread do AWT. No Linux
não há report porque o JVM nunca chegou a escrever um.

A causa eram **duas linhas que eu acrescentei "por segurança"**:

```java
settings.resources_dir_path = jcefDir;
settings.locales_dir_path = jcefDir;
```

O MCEF **não** as põe, e o Chromium recusa arrancar quando `resources_dir_path`
aponta para um directório com outra estrutura — faz `CHECK` e leva o JVM
atrás. Tirá-las resolveu: o CEF arrancou à primeira, e a mesma correcção
resolve o Windows.

## 5.3 O entry point certo, e a ordem das settings

Duas coisas que custaram tentativas:

**O entry point não é `org.cef.CefApp.getInstance()`.** É
`me.friwi.jcefmaven.CefAppBuilder`, que está num **jar diferente** (`jcefmaven`,
30 KB, não `jcef-api`) e que faz o bootstrap todo pelo seu `CefInitializer`:
directório de instalação, caminho das bibliotecas, argumentos, handler de
progresso. Construir o `CefApp` à mão saltava tudo isso, e o lado nativo morria.

**As settings vão depois do build, não antes.** MCEF faz
`builder.build()` e depois `app.setSettings(settings)` com um `CefSettings`
novo. Postas no builder antes do build, não tinham efeito — e o browser saía
**com janela**, o que faz `createScreenshot` responder:

```
java.lang.UnsupportedOperationException: Unsupported for windowed rendering
```

Dezanove a noventa e oito tentativas, todas com o mesmo erro. O browser tem de
ser **offscreen** para se poder tirar um screenshot, e é isso que permite um
ecrã numa máquina sem ecrã.

**Fica por resolver:** com a ordem certa, o browser continua a ser criado com
janela. A pista seguinte é a criação em si — `CefBrowserFactory.create` com
`CefBrowserSettings.windowless_frame_rate` posto, e a
`CefSettings.windowless_rendering_enabled` a ser posta no momento certo.

---

## 6. Decisões que pertencem ao dono

Nada disto foi feito. Todas as três esperam por resposta.

### 6.1 Espaço — a opção óbvia e a que se deve fazer

Apagar o backup de `2026-10-16` liberta **4.0 GB** e leva a raiz de 3.9 GB
livres para ~7.9 GB. É um backup, não o mundo. **Dá para os 8 GB se guardarem os
dois dias** e mesmo assim sobrar ~4 GB.

**Não se formata nada.** Não há necessidade: há uma partição de 66.9 GB já em
ext4 à espera, e 4 GB que se libertam trivialmente.

### 6.2 A partição de 66.9 GB

Montá-la daria espaço para sempre. Mas:

- a linha do `fstab` que tentava montá-la aponta para `nvme0n1p5`, que não
  existe — teria de ser corrigida para `nvme1n1p5` pelo UUID;
- **não se sabe se tem dados.** Se for a instalação antiga do Minecraft, apagá-la
  ou montá-la mal é perda real;
- precisa de root.

### 6.3 Exposição à internet — como é que está feito, de facto

**Correção: não é o playit.** A exposição é um **encaminhamento de portas no
router de casa**, do IP público para a máquina:

| Externo | Interno | Protocolo | Para que serve |
|---|---|---|---|
| `<porta publica>` | 25565 | TCP | o Minecraft |
| 24454 | 24454 | TCP + UDP | o Simple Voice Chat |

O `playitd` está instalado e a correr no servidor, mas **não é o mecanismo** — é
um resíduo. O `voice_host` do voice chat aponta para o hostname DDNS público da
casa, o que confirma que o encaminhamento é no router.

**Isto é pior do que eu tinha dito, e melhor ao mesmo tempo:**

- **Pior:** o Minecraft e o voice chat estão expostos pela **tua ligação de
  casa**, não por um túnel. Tudo o que os teus amigos jogam passa pela tua
  banda. E a origem dos jogadores na internet é o **teu IP de casa**, que
  fica registado nos logs do jogo e no do voice chat.
- **Melhor:** é forwarding do router, não um serviço de terceiros. Não há
  nenhuma empresa a meio, e o IP é fixo, o que permite whitelist por IP se
  algum dia for preciso.

**O que protege o Minecraft:**

| | |
|---|---|
| `online-mode=true` | **só contas Mojang legítimas entram** |
| whitelist | 7 jogadores, confirmada pelo próprio servidor |
| `enforce-secure-profile=true` | |

Ou seja: para entrar, um desconhecido precisa de uma conta do Minecraft paga
**e** estar na whitelist. A whitelist é a porta, e a autenticação Mojang é a
segunda tranca.

**O que NÃO está protegido: o voice chat.**

O `voicechat-server.properties` não tem **nenhuma chave de autenticação** — nem
token, nem password. `bind_address` está vazio, ou seja, escuta em todas as
interfaces, e a porta está encaminhada para a internet em TCP **e** UDP. Não
existe config de cliente no servidor a exigir nada.

**Assumir que um desconhecido consegue entrar e falar com os teus amigos** até
se provar o contrário. As opções são: deixar de encaminhar a 24454 (o voice
chat funciona dentro da LAN sem isso, mas os amigos que entram de fora
precisam), ou pôr token nos clientes dos amigos, ou aceitar e saber que é o
que está.

**O `afk-bot-mod` está instalado sem configuração**, ou seja, com as
predefinições: quem está na whitelist pode criar bots que ficam ligados. Num
servidor em casa isso prende CPU e banda sem o dono notar.

### 6.4 O script de instalação falhou por um pacote

O primeiro `apt-get install` levava `libu2f-udev`, que **não existe no Ubuntu
26.04**. O apt é transaccional: uma dependência em falta aborta o pacote
inteiro, e ficou **zero** bibliotecas instaladas — o script confirmou que as 15
continuavam em falta.

A segunda versão verifica cada pacote com `apt-cache show` antes, salta os que
não existem, e instala **um a um**, para que uma falha não derrube as outras.
`libu2f-udev` foi para fora: é suporte de chaves FIDO/U2F, que o Chromium não
precisa.

### 6.5 O backup de hoje foi verificado

A primeira verificação que fiz deu um susto falso: procurei `region/`,
`playerdata/` e `stats.dat`, que é o layout de mundo **antes** de 1.21. O 26.3
não tem nenhum dos três — os chunks estão em `dimensions/<dim>/region/`, os
jogadores em `players/`, e há ~105 ficheiros `level*.dat`. O backup não tinha
nada de mal. **Foi o teste que estava errado**, como já aconteceu várias vezes.

Comparando com o mundo vivo, que por definição tem a estrutura certa:

| | |
|---|---|
| Directórios da raiz | **idênticos** (`diff` limpo) |
| Ficheiros de chunk `.mca` | 1419 no backup, 1420 no mundo vivo |
| `.mca` de zero bytes | **0** |
| Ficheiros `level*.dat` | 104 vazios — **e também estão vazios no mundo vivo**, são stubs do formato novo |
| Jogadores | `players/{advancements,data,stats}`, igual ao vivo |
| Tamanho | 4.1 GB, contra 4.0 GB do de ontem |

A diferença de um chunk é o mundo ter jogado depois das 06:00.

**O que o verificador não prova:** que o Minecraft o consiga carregar. Isto foi
lido como NBT e comparado com o mundo vivo; a prova a sério é parar o servidor
e restaurar, e isso não se faz.

**Nada foi apagado.** O backup de ontem continua lá, à espera de ordem.

### 6.6 O que foi feito ao servidor, e o que quase correu mal

O servidor foi parado com **graça**, pela consola do próprio jogo
(`printf 'stop' > console.fifo`), que é melhor do que `kill` porque o mundo
fica gravado por inteiro: o log diz "Saving worlds" e "Saving chunks for level".

**A primeira tentativa de backup encheu o disco.** O tarball do mundo foi
truncado com `No space left on device` e a raiz ficou a 100 KB livres. O tar só
lê, portanto **o mundo não foi tocado** — 1692 chunks, zero vazios, `level.dat`
com NBT válido, confirmado depois. O ficheiro partido foi apagado (provava-se
mau com `gzip -t`) e o espaço voltou.

Causa: o mundo cresceu de 4.1 para 4.8 GB com o jogo, e o tarball comprimido
precisava de ~2.5 GB com 2.3 GB livres. **Comprimir não chega** — o mundo tem
chunks que já não comprimem.

O que ficou feito, e verificado:

| | |
|---|---|
| `servidor-completo_2026-10-21_00-03.tar.gz` | **2.5 GB, `gzip -t` OK, 2006 entradas, 1692 `.mca` — exactamente os do mundo vivo** |
| `config_2026-10-21_00-03.tar.gz` | 27 MB, `gzip -t` OK, com a whitelist e os ops |
| `world_2026-10-20_06-00` (diário) | 4.5 GB, 1613 chunks, 0 vazios |
| backup diário de 19 | **apagado**, com o de 20 verificado antes, como combinado |

Os dois tarballs foram copiados para o PC pessoal e conferidos por SHA256: idêntico
nos dois lados, e o índice abre-se no Windows.

O que **não** se provou: que o Minecraft consiga restaurar este tarball. Isto foi
lido e comparado; a prova a sério é parar, restaurar e arrancar, e não se fez.

### 6.7 O que continua pendente

- **A partição de 66.9 GB**: não montada, não formatada. A linha do `fstab`
  que a montaria aponta para `nvme0n1p5`, que não existe; a partição real é
  `nvme1n1p5`, pelo UUID `83505831-b7c2-467a-af6e-1e652d4d8d56`. Montar em
  só-leitura precisa de root, e não se sabe se tem dados.
- **As 13 bibliotecas do Chromium** e o `sudo` com senha.
- **Que portas o playit expõe** — só o dashboard responde.
- **`enforce-whitelist`** — qual das duas chaves é que manda.
- **O probe no Linux** — falta o Xvfb (`bash ~/spike-install-xvfb.sh`). As
  bibliotecas e o bundle nativo já lá estão; as duas armadilhas de 4.1 estão
  tratadas no código, mas o probe ainda não chegou a correr.
- **A queda nativa no Windows** (`os_windows_x86.cpp:97`) — por resolver. Se o
  mod tiver de correr no PC de cada jogador, isto é uma Bomba-relógio.