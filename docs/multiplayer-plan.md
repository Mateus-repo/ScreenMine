# ScreenMine multiplayer — plano

Documento de estado. Só texto: nada disto é código feito. O que está descrito
como "verificado" foi lido nos jars e no código desta branch; o que está
descrito como "hipótese" não foi.

Contexto: a branch `multiplayer` (`screenmine_mp`) nasce da `port/26.3`
(`screenmine`). Os merges vão só de `port/26.3` para `multiplayer`.

---

## (a) O que o canal `where` transporta, de facto

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

- Ecrãs partilhados têm de viver num `CefRequestContext` próprio, com
  `CefRequestContext.create(...)`.
- Modo anónimo (`CefRequestHandler` a responder por todas asapanese) é a opção
  mais segura para conteúdo partilhado: sem cookies, sem `localStorage`, sem
  nada herdado da máquina.
- Decidir se o dono pode Promote ecrã próprio ao contexto global. É útil e é
  exactamente o que transforma isto numa superfície de ataque, por isso tem de
  ser explícito.

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

O que tornaria isto unattractive:

- SyncPlay é por **sessão de reprodução**, não por ecrã. Dois amigos a ver coisas
  diferentes em ecrãs diferentes do mesmo servidor não ficam em sync — o que
  provavelmente é o caso de uso principal (uma pessoa mostra um vídeo, outra um
  site).
- O servidor tem de ter a permissão de SyncPlay activa por utilizador.
- Continua a precisar de tudo em (c) menos o relógio: a autenticação e a
  navegação remota não ficam mais fáceis.

**O que verificar, quando isto interessar:** nas permissões do utilizador no
Jellyfin, se o SyncPlay está activo e com que âmbito.
pergunta original: confirma nas permissões do utilizador_.

## (e) Três coisas que tenho de saber antes de desenhar isto

1. **Só amigos, ou público?** Muda tudo. Convidados com código e TLS
   resolve-se num dia. Um serviço público com contas, abuso e moderação não é o
   mesmo projeto.

2. **Browser-de-cada-um chega, ou é preciso transmitir píxeis?** Browser-de-cada-um
   é barato e resolve páginas públicas. Não resolve o Jellyfin autenticado, uma
   página atrás de login, nem nada com DRM — para isso era preciso transmitir os
   píxeis (relay no servidor ou WebRTC), que é outro projeto.

3. **Os ecrãs são do dono da sala, ou de toda a gente?** Se são do dono, o
   `place` é só do dono e os outros veem. Se são de todos, qualquer um pode
   colocar e mover um ecrã, e "só o dono manda `navigate`" passa a "qualquer um
   escolhe o que toda a gente vê" - que é uma regra de permissões completamente
   diferente.

---

## Resumo do estado

| | |
|---|---|
| O que o `where` transporta | Só o endereço do servidor de Minecraft. Sem posições. |
| Partilhar colocação sem mod no servidor | Sim, com um pacote novo (`place`). O servidor do Minecraft fica de fora. |
| Autenticação | **Não existe.** O `hello` leva nome e versão. |
| Cifra | **Não existe.** TCP em claro. |
| Navegação remota controlada | **Não.** `navigate` é aceite de qualquer um. |
| Lista de domínios | **Não existe.** |
| Perfil de browser separado | **Não existe.** Todos os ecrãs partilham o contexto global do Chromium. |
| Limites de mensagem | **Não existem.** `readLine()` sem teto; a linha crua vai para o log. |
| SyncPlay | Hipótese, por verificar. |

Nenhum servidor real deve ser publicado com esta lista como está. O primeiro
item a fazer é a autenticação; sem ela os restantes são decoração.