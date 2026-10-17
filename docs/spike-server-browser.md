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

13 bibliotecas que o Chromium usa e que não lá estão:

```
libatk-1.0.so.0        libpango-1.0.so.0     libXcomposite.so.1
libatk-bridge-2.0.so.0 libpangocairo-1.0.so.0 libXdamage.so.1
libatspi2.0.so.0       libcairo.so.2         libXfixes.so.3
libgbm.so.1            libxkbcommon.so.0     libXrandr.so.2
libxshmfence.so.1      libgl.so.1            libegl.so.1
```

Todas existem em pacotes Ubuntu normais. O script está em
`~/spike-install-libs.sh` no servidor, à espera de ser corrido com `sudo`
(ao utilizador do servidor falta-lhe a senha de sudo, e sem `-n`).

**Não é preciso ecrã virtual.** O JCEF faz *offscreen rendering*
(`CefSettings.windowless_rendering_enabled`), que é exactamente para isto:
desenha sem janela. Xvfb não é necessário.

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

---

## 5. Estado do spike

| | |
|---|---|
| Probe escrito | `tools/SpikeServerBrowser.java` |
| Compila | sim, depois de 3 erros corrigidos (`CefClient` está em `org.cef`, não `org.cef.browser`; `LogSeverity.LOGSEVERITY_WARNING`, não `WARNING`; `onLoadEnd` é do `CefLoadHandlerAdapter`) |
| Corre no Windows | **não** — o CEF arrancou e o JVM morreu com um erro interno de código nativo. Ainda por perceber. |
| Corre no Linux | não tentado: falta o `sudo` para as bibliotecas |

O que o probe mede, e porque cada número decide uma coisa:

1. Se o JCEF renderiza sem ecrã. Se não, o projecto está morto e saves-se o mês.
2. Quanto custa um screenshot. Acima de 66 ms, os 15 fps não cabem num núcleo.
3. Quantos bytes tem um frame em JPEG, por resolução e qualidade. **Isto decide
   se é preciso FFmpeg.**
4. Com que frequência o frame muda. Uma página parada só precisa de ser enviada
   quando muda.

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

### 6.3 Segurança — isto mudou o que se disse antes

Duas coisas nesta máquina que não estavam no radar:

- **`playitd` está a correr.** É um túnel que expõe portas locais à internet.
- **Tailscale está activo.**

O Minecraft escuta em `*:25565`, ou seja, em todas as interfaces. Com o playit
a correr, **esta máquina pode estar alcançável pela internet**, e não só pela
LAN. Isto contraria o que se assumia — que `online-mode=false` e
`white-list=false` estavam protectedos por estarem dentro da rede de casa.

Isto tem de ser confirmado e fechado antes de o mod ir para o GitHub. Não é
deste spike, mas é mais urgente que o spike.