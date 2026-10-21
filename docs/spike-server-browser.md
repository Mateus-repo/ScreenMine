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
- **O probe no Linux** — nem sequer arrancou lá; no Windows morre em código
  nativo por resolver.