# ScreenMine multiplayer — plano

Documento de estado. Só texto: nada disto é código feito. O que está descrito
como "verificado" foi lido nos jars e no código desta branch; o que está
descrito como "hipótese" não foi.

Contexto: a branch `multiplayer` (`screenmine_mp`) nasce da `port/26.3`
(`screenmine`). Os merges vão só de `port/26.3` para `multiplayer`.

---

## (a) O que o canal `where` transporta, de facto

### Antes de tudo: o servidor não existe

Isto muda o resto do plano, por isso fica em primeiro.

`NetClient` é a metade **cliente** de um protocolo. A metade **servidor** não
está em lado nenhum:

| Onde | O que há |
|---|---|
| Este repo | `NetClient.java`, `Packets.java`. Nenhum socket de escuta, nenhum `ServerSocket`, nenhum ficheiro de servidor. |
| `../CoScreen-main` (referência) | Também **só o cliente**. O README dela é o kit de skills do opencode, não o mod. |

Portanto o CHANGELOG afirmava algo falso — dizia que "a companion server
exists behind them". Não existe. Corrigido.

O que isso significa na prática:

- **Não háBug de autenticação para corrigir.** Não há servidor para pôr auth.
  O trabalho é **escrever o servidor de raiz**, e só depois pôr-lhe auth.
- A metade que existe é o protocolo: os tipos de pacote estão definidos em
  `Packets.java` e o cliente já fala JSON delimitado por linhas. Isso é
  reaproveitável como especificação.
- O `hello` (nome e versão, sem token) é o que um servidor **novo** receberia.
  Como não há ninguém do outro lado, o formato ainda pode ser mudado para
  levar o código de convite sem compatibilidade a preservar.

**O que tens de ter a correr para dois clientes:** um servidor novo. Não
existe nada para arrancar. O `server.host`/`server.port` na config dizem aonde
ele estaria, e nada mais.

### O canal `where`

Verificado em `net/Packets.java` e `ScreenMineClient`.

O emissor é `Packets.where(String serverId)`:

```json
{"t": "where", "server": "<id>"}
```

E o `serverId` vem de `ScreenMineClient.currentServerId(Minecraft)`:

```java
ServerData info = mc.getCurrentServer();
if (info != null && info.ip != null && !info.ip.isBlank()) {
    return info.ip.toLowerCase(Locale.ROOT);
}
return "single:" + mc.getUser().getName();
```

Ou seja: **endereço do servidor de Minecraft em minúsculas, ou
`single:<nome>` num mundo só nosso.** É mandado uma vez por mudança de servidor
(`lastReportedServer` evita repetir).

**Não transporta posições.** Nem de ecrãs, nem de jogadores, nem de streams, nem
nada de geometria. A suposição de que o `where` do CoScreen original partilhava
posições **não se confirma neste código** — aqui é só "em que servidor estou".

## (b) Dá para partilhar a colocação dos ecrãs sem mod no servidor de Minecraft?

**Sim, e é mais barato do que parece — mas não pelo canal `where`.**

O que hoje acontece, verificado em `onStreamNew`:

```java
StreamInfo info = StreamInfo.fromJson(o);
state.streams.put(info.id, info);
world.attach(info.id, info.url);
```

E `ScreenWorld.attach(streamId, url)` calcula a posição **localmente**, a partir
dos olhos e da direção de olhar de quem recebe:

```java
Vec3 eye = p.getEyePosition();
Vec3 look = p.getLookAngle();
Vec3 pos = eye.add(look.scale(Cfg.SPAWN_DISTANCE));
float yaw = p.getYRot() + 180.0f;
```

Portanto hoje **cada jogador vê o ecrã à sua frente, no seu sitio**, e a
colocação nunca é transmitida. Para partilhar, o caminho não é mexer no `where`
mas **criar um pacote novo**, por exemplo `place {id, x, y, z, yaw, w, h,
dimension}`, e o servidor do ScreenMine reenvia-o a quem estiver no mesmo
`server`.

Isto continua a ser um canal **fora do Minecraft**: o servidor de Minecraft não
precisa do mod. O `where` deixa de ser candidato e passa a ser o que já é — o
rótulo que agrupa quem está no mesmo mundo, e que o servidor pode usar para
decidir a quem reenviar o `place`.

Para `where` servir mesmo para isso é preciso Treatar o endereço como
**segredo**: hoje manda-se o IP em claro, e o IP é o mesmo que o `server.host`
do Minecraft. Num serviço público isso é uma fuga de-addressa e uma forma de
agrupar pessoas. Ver (c).

## (c) Requisitos de segurança, antes de qualquer servidor real

Tudo isto está em falta hoje. O `hello` prova o essencial:

```java
JsonObject hello = new JsonObject();
hello.addProperty("t", "hello");
hello.addProperty("name", playerName);
hello.addProperty("ver", Cfg.PROTOCOL_VERSION);
```

Nome e versão. **Sem token.** Qualquer pessoa que chegue ao porto fica com um
cliente autenticado.

### 1. Autenticação

- Código de convite por sala, gerado pelo dono, de uso único e com validade.
  Entra no `hello` e é verificado no servidor antes de qualquer outro pacote.
- Sem isto, o resto da lista é irrelevante: um atacante não precisa de persuasion
  para enviar `navigate`.

### 2. Cifra

O transporte é um socket TCP em claro (`NetClient` abre
`new Socket()` e fala JSON linha a linha). Numa rede pública isso expõe nome,
endereço de servidor, títulos de página e URLs.

- TLS, com verificação de certificado. Não "aceitar tudo": o certificado tem de ser
  verificado, ou o TLS só dá uma falsa sensação.
- Se TLS não for aceitável, as mensagens têm de ser autenticadas e cifradas ao
  nível da aplicação. O que não pode é ser só opcional.

### 3. Navegação remota só pelo dono

Hoje `navigate`, `ctl` e `state` não têm dono verificado do lado do cliente.
`onStreamCtl` aceita o que vier com o `id` certo.

- Só o dono da sala envia `navigate` e `ctl`. Os clientes rejeitam o resto.
- `state` é o único pacote que os clientes aceitam de toda a gente, e só
  playback: tempo, pausado, título. Nunca um URL novo.

### 4. Lista de domínios permitidos

Isto é o que separa "os meus amigos veem o que eu vejo" de "qualquer pessoa
pode abrir o Chromium embebido do meu PC".

- O dono mantém uma lista de domínios que os ecrãs partilhados podem abrir.
- Um `navigate` para fora da lista é recusado **e** mostrado ao cliente com o
  endereço completo: "o dono quer abrir este endereço — aceitar?".
- O URL que vem em `state` também tem de ser filtrado. É um caminho de
  entrada igual ao `navigate` e hoje não é tratado.

### 5. Perfil de browser separado — e isto não é opcional

Verificado no bytecode de `MCEFApiImpl.createBrowser`:

```
invokestatic  org/cef/browser/CefRequestContext.getGlobalContext:()...
```

**Todos os ecrãs partilham o contexto global do Chromium.** Um único jar de
cookies para o mod inteiro.

Numa sessão local isso já é um problema (dois ecrãs/sites interferem um com o
outro). Numa sessão multiplayer é um problema de segurança: a sessão do
Jellyfin, ou de qualquer outro site onde o dono esteja autenticado, está
acessível a quem conseguir mandar um `navigate`. E como o browser é do dono, as
cookies são as do dono.

- Ecrãs abertos por comando remoto têm de usar um `CefRequestContext` **próprio**,
  com cache e cookies separados dos do dono. Sem isto, a sessão do dono é
  alcançável por quem mande um `navigate`.
- Modo anónimo é a opção mais segura para ecrãs partilhados: sem cookies, sem
  `localStorage`, sem nada herdado da máquina.
- Decidir se o dono pode promover um ecrã próprio ao contexto global. É útil e
  é exactamente o que transforma isto numa superfície de ataque, por isso tem de
  ser explícito.
- Isto tem de ser **opt-in por ecrã**, não global. Com as respostas em (e), cada
  amigo entra no Jellyfin com a **conta dele**, no browser embebido dele: um
  contexto partilhado destruiria isso. Contexto anónimo para ecrã de comando,
  contexto normal para ecrã que o dono declare seu.

#### O que encontrei no mcef-modern sobre contextos separados

Verificado com `javap`, sem implementar nada.

**A capacidade existe. A API do mcef-modern não a expõe.**

O que a API oferece:

```java
net.dimaskama.mcef.api.MCEFApi
  MCEFBrowser createBrowser(java.lang.String, boolean)
```

Dois parâmetros: URL e um booleano. **Não há onde pedir um request context.**
E o `MCEFApiImpl` não usa essa via — constrói o browser directamente e fixa o
contexto global:

```
createBrowser(String, boolean):
   0: new  net/dimaskama/mcef/impl/MCEFBrowserImpl
  10: invokestatic  org/cef/browser/CefRequestContext.getGlobalContext
  13: aconst_null                          // CefBrowserSettings = null
  14: invokespecial MCEFBrowserImpl.<init>:(CefClient;String;ZCefRequestContext;CefBrowserSettings)V
```

Portanto hoje **não há como pedir um contexto isolado ao mcef-modern**. Qualquer
isolamento teria de contornar a API.

Duas vias, ambas confirmadas:

1. **A suportada.** `org.cef.browser.CefBrowserFactory` (no `jcef-api`, não no
   mod) é `public static`:

   ```java
   public static CefBrowser create(CefClient, String, boolean, boolean,
                                   CefRequestContext, CefBrowserSettings)
   ```

   Aceita contexto. E `CefRequestContext.createContext(CefRequestContextHandler)`
   é `public static` e devolve um contexto novo e isolado. É a via que eu usaria.

2. **A que depende da implementação.** O jar do mcef-modern traz
   `org.cef.browser.CustomCefBrowserOsr` como classe **pública**, com construtor
   que aceita contexto. Funciona, mas é uma classe de implementação: se o
   mcef-modern a renomear ou tirar numa actualização, isto parte. Não é o que
   eu escolheria sem necessidade.

**O gancho para modo anónimo existe.** `CefRequestContextHandler` tem um método
abstracto:

```java
ResourceRequestHandler getResourceRequestHandler(browser, frame, request,
        isNavigation, isDownload, requestInitiator, disableDefaultHandling)
```

Um handler que não sirva nada dá um ecrã partilhado **sem armazenamento nenhum**,
o que é mais forte do que "jar de cookies separado".

**Um aviso, e é a armadilha habitual:** a assinatura da factory tem **dois
`boolean` seguidos** e o jar não traz atributo `MethodParameters`, portanto o
significado deles não se consegue ler. Não adivinhar. É a mesma armadilha dos
códigos de tecla: probei com códigos GLFW e o resultado não queria dizer nada.
Confirmar antes de usar.

### 6. Limites e validação das mensagens

Verificado em `NetClient`: a leitura é `in.readLine()`, **sem limite de
comprimento**, e o caminho de pacote mau escreve a linha inteira no log:

```java
while (running.get() && (line = in.readLine()) != null) {
    try {
        JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
        inbound.add(obj);
    } catch (Exception ex) {
        ScreenMineClient.LOG.warn("bad packet from server: {}", line);
    }
}
```

- Teto de tamanho por linha, no cliente e no servidor. Uma linha enorme é
  memória e é log.
- Deixar de registar a linha crua. É conteúdo que veio de fora e vai parar ao
  log do jogador.
- Validar tipos antes de usar. `getAsString()` e `getAsDouble()` levantam
  excepções em campo malformado, e `handlePacket` tem um `catch` largo — um
  pacote malformado vira um warning e um estado meio actualizado.
- `state` traz `title` e `url` de fora e ambos vão para o chat e para o HUD.
  São strings vindas da rede: não são comando, mas aparecem.

### 7. O endereço do servidor de Minecraft

`where` manda o IP em claro. Num serviço público: opcode próprio, não o
endereço em claro. E o mesmo cuidado para o `screenmine.properties`, que é
lido do disco do cliente e não é segredo de ninguém.

## (d) SyncPlay do Jellyfin — hipótese

**Não verificado.** Não tenho acesso ao servidor Jellyfin e não testei nada
disto.

A ideia: se cada amigo abrir o mesmo servidor Jellyfin no seu browser
embebido, o próprio Jellyfin trata de sincronizar play, pause e seek entre
sessões, e o mod só precisa de mandar a todos a mesma URL. Isso encolheria a
multiplayer para quase nada: sem relógio de playback partilhado, sem
`state`, sem `seek_by`.

O que **não** resolve:
- **Quem vê o ecrã de quem.** O SyncPlay decide o *quando* de um vídeo. Não tem
  nada a dizer sobre colocar, mover, ecrãs partilhados ou navegação remota.
  Continua a precisar de autenticação, lista de domínios e contextos isolados.
- **Ecrãs com conteúdo diferente.** Um ecrã com um site e outro com um filme
  continuam a precisar do relógio do mod. O SyncPlay só ajuda quando o grupo
  está todo no mesmo vídeo — o que, com a resposta (e.3) de que os ecrãs são do
  dono, é o caso comum, mas não o único.
Uma objecção que eu tinha levantado estava errada, e vale a pena registar. Disse
que o SyncPlay sincroniza por sessão de reprodução e não por ecrã, e que por isso
dois amigos a ver coisas diferentes não ficariam em sincronia. É verdade, e é
irrelevante: se todos estão a ver a mesma coisa, não há segunda coisa para
sincronizar. O caso de uso do SyncPlay é exactamente o caso em que ele
funciona — e o que ele resolve bem é tirar o relógio de playback do mod.
**O que verificar, quando isto interessar:** nas permissões do utilizador no
Jellyfin, se o SyncPlay está activo e com que âmbito. E um aviso de segurança:
com a resposta (e.2), cada amigo entra no Jellyfin com a **conta dele**, o que
significa distribuir contas do servidor a quem se confia.

## (e) Três coisas que tenho de saber antes de desenhar isto

**Respondidas.** São estas respostas que o resto do plano assume. A primeira
versão é a mais barata e a mais segura; as três são reversíveis.

### 1. Só amigos, com código de convite e TLS

Não um serviço público. Isso é outro projeto, com contas, abuso e moderação.

O que isto resolve em (c): a autenticação por convite deixa de ser o item mais
difícil — é o item mais fácil, porque não há quem se registe sozinho. E TLS é
obrigatório, não opcional.

O que fica resolvido com isto: nada mais precisa de ser perfeito para uma
primeira versão. Um erro de navegação afecta um amigo que foi convidado, não o
público.

### 2. Browser-de-cada-um; cada amigo entra no Jellyfin com a conta dele

Para páginas públicas (YouTube, um site), cada cliente abre a sua própria
página. **Sem transmitir píxeis.** Relay e WebRTC ficam de fora.

Para o Jellyfin, cada amigo entra com **a sua conta** no seu browser embebido.
Isto tem uma consequência que tem de estar explícita: **o contexto do browser
tem de ser isolado por ecrã**, senão as sessões de toda a gente se misturam
numa só e a conta de um amigo cruza com as dos outros. É o requisito de cookies
de (c.5), e é a resposta a isto que o torna obrigatório.

O custo honesto desta resposta: cada amigo precisa de ter conta no Jellyfin.
Significa distribuir contas do servidor a quem se confia. É uma decisão de
segurança e é tua.

O que fica de fora por já: o Jellyfin autenticado deixa de ser um caso
impossível e passa a ser um caso normal — cada um vê o que pode ver. O que
continua impossível é *partilhar* uma sessão autenticada sem dar a conta.

### 3. Os ecrãs são do dono da sala

Só o dono coloca, move, navega e controla. Os outros veem.

É a regra de permissões mais simples que existe: uma pessoa com poder, todos os
outros sem nenhum. E tem uma vantagem de segurança que vale a pena notar — com
esta resposta, **ninguém** pode mandar `navigate`, nem para os ecrãs de outro.
O requisito (c.3) fica "só o dono envia `navigate` e `ctl`" sem nenhum caso
especial.

Um ecrã de comando remoto, então, é sempre do dono. A lista de domínios (c.4)
é uma restrição a menos: o dono escolhe o que abre, em qualquer caso. Continua a
ser necessária — sem ela, um convite comprometido pilota o browser do dono.

**O que isto deixa de fora:** ecrãs que cada amigo coloca para mostrar a sua
coisa. É uma funcionalidade, não um bug, e fica para depois.

---

## Resumo do estado

| | |
|---|---|
| **Servidor** | **Não existe.** Nem aqui, nem no CoScreen de referência. Só o cliente. |
| O que o `where` transporta | Só o endereço do servidor de Minecraft. Sem posições. |
| Partilhar colocação sem mod no servidor | Sim, com um pacote novo (`place`). O servidor do Minecraft fica de fora. |
| Autenticação | **Não existe.** O `hello` leva nome e versão. |
| Cifra | **Não existe.** TCP em claro. |
| Navegação remota controlada | **Não.** `navigate` é aceite de qualquer um. |
| Lista de domínios | **Não existe.** |
| Perfil de browser separado | **Não existe.** Todos os ecrãs partilham o contexto global do Chromium. Mas a via existe: `CefRequestContext.createContext` + `CefBrowserFactory.create`. Ver (c.5). |
| Limites de mensagem | **Não existem.** `readLine()` sem teto; a linha crua vai para o log. |
| SyncPlay | Não verificado. Resolve o relógio de playback, não quem vê o ecrã de quem. |

## Ordem de trabalho

A ordem mudou depois de se descobrir que **o servidor não existe**. Antes o
primeiro item era a autenticação; agora é ter um servidor a correr.

A branch `multiplayer` continua a depender do bug A (teclas de edição) estar
fechado na `port/26.3` para o trabalho a sério. O que se segue pode ser feito
antes disso.

1. **Spike do contexto isolado.** Provar que um browser criado com
   `CefBrowserFactory.create` e um `CefRequestContext` próprio pinta na textura
   do Minecraft. Se não pintar, o desenho muda e é melhor sabê-lo já.
2. **Escrever o servidor.** Do zero. Precisa de: escutar, agrupar clientes por
   sala, relançar `place`, `state` e `ctl`, e ligar o relógio. Reaproveitar
   `Packets.java` como especificação.
3. **Convite + TLS**, no servidor novo. Como não há servidor antigo, o `hello`
   pode mudar de formato sem compatibilidade a preservar.
4. **Contexto isolado por ecrã**, ligado ao item 1.
5. Só o dono envia `navigate` e `ctl`.
6. Limites de tamanho e validação de tipos, dos dois lados.

Nada disto se publica. E nada disto se escreve antes de o bug A fechar.
