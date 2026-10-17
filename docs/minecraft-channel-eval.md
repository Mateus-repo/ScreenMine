# Avaliação: usar o canal do Minecraft em vez de um servidor TCP

Documento de decisão. Nada disto está implementado — é a investigação que
decide se vale a pena construir, feita com `javap` contra os jars reais da 26.3
e da Fabric API que esta branch usa.

Pergunta: o canal de rede do próprio Minecraft substitui o "companion server"
que nunca existiu?

Resposta curta: **sim, e é muito mais barato.** Com uma condição que é
justamente a que torna a versão local necessária.

---

## 1. A API, com os nomes reais

Tudo verificado contra `fabric-networking-api-v1-6.3.10+5c1fee4d5d` e
`fabric-loader-0.19.5`.

### Registar um payload

```java
PayloadTypeRegistry.play(...)   // NÃO EXISTE
```

Os nomes que a memória dá estão errados. Os reais:

```java
public static PayloadTypeRegistry<RegistryFriendlyByteBuf> serverboundPlay();
public static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay();
public static PayloadTypeRegistry<FriendlyByteBuf>       serverboundConfiguration();
public static PayloadTypeRegistry<FriendlyByteBuf>       clientboundConfiguration();
```

E registar:

```java
public abstract <T extends CustomPacketPayload>
    TypeAndCodec<? super B, T> register(Type<T> type, StreamCodec<? super B, T> codec);

public abstract <T extends CustomPacketPayload>
    TypeAndCodec<? super B, T> registerLarge(Type<T> type, StreamCodec<? super B, T> codec, int maxSize);
public abstract <T extends CustomPacketPayload>
    TypeAndCodec<? super B, T> registerLarge(Type<T> type, StreamCodec<? super B, T> codec, IntSupplier maxSize);
```

O `Type` é simples:

```java
public CustomPacketPayload$Type(net.minecraft.resources.Identifier);
```

**`registerLarge` com o tamanho máximo já resolve o requisito de limites de
tamanho** que em (c.6) estava em falta. Não é preciso inventar um teto à mão, e
o servidor recusa a mensagem antes de a deserializar.

### Registar receptores

```java
ServerPlayNetworking.registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>);
ClientPlayNetworking.registerGlobalReceiver(Type<T>, PlayPayloadHandler<T>);
ServerPlayNetworking.send(ServerPlayer, CustomPacketPayload);
ClientPlayNetworking.send(CustomPayload);
```

E um detalhe que vale muito:

```java
public static boolean canSend(ServerPlayer, Type<?>);
public static boolean canSend(Identifier);
```

**É a resposta built-in a "o outro lado tem o mod?"** Não precisamos de
inventário, nem de mensagens de capability. O `canSend` diz se o destinatário
aceita aquele payload, e o Minecraft já garante que ninguém sem o mod consegue
registar um receptor para um tipo desconhecido — o pacote morre no parsing.

### Eventos de ligação — aqui é que a memória falha pior

O nome que se lembraria:

```java
net.fabricmc.fabric.api.event.lifecycle.v1.ServerPlayConnectionEvents   // NÃO EXISTE
```

Confirmado: não está em `fabric-lifecycle-events-v1`. Em 26.3 os eventos de
ligação moveram-se **para a networking API**:

```java
net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
```

Ambos com `INIT`, `JOIN`, `DISCONNECT`, como `Event<...>`.

Este exemplo vale por si só: se isto tivesse sido escrito de memória, o código
não compilava e a culpa caía na "API mudou", não em "eu adivinhei".

### O servidor dedicado carrega o mod?

Só se `environment` for universal. Os valores reais de `ModEnvironment` são
`CLIENT`, `SERVER`, `UNIVERSAL` — e o loader mapeia `UNIVERSAL` para `"*"` no
JSON. Hoje o `fabric.mod.json` diz `"client"`, pelo que um servidor dedicado
**ignora o mod**. Na multiplayer passa a `"*"`, e é a diferença entre isto
funcionar e não funcionar.

---

## 2. Os pacotes de `Packets.java`, mapeados

| `Packets.*` actual | Payload | Direcção | Notas |
|---|---|---|---|
| `streamNew(url)` | `PlacePayload` (novo) | S → C | Tem de ganhar posição, tamanho, dimensão. **Não é o que existe hoje**: `ScreenWorld.attach` calcula a posição localmente. |
| `streamRemove(id)` | `RemovePayload` | S → C | Trivial. |
| `ctl(id, "navigate", url)` | `NavigatePayload` | C → S, S → C | O URL vai num `String`. Vai no payload, com validação no servidor. |
| `ctl(id, "play"/"pause"/"seek_by", …)` | `ControlPayload` | C → S, S → C | `double` para o seek. |
| `state(id, time, paused, title, isAd, url)` | `StatePayload` | C → C via S | O mais frequente. `title` e `url` são strings; `url` é a que a lista de domínios tem de filtrar. |
| `where(server)` | **Deixa de existir** | — | O servidor de Minecraft já sabe em que servidor está cada jogador. Este canal foi um substituto do que agora é nativo. |
| `hello` | **Deixa de existir** | — | A autenticação passa a ser a do Minecraft. |
| `friend*`, `invite*` | **Deixa de existir** | — | Substituídos pelo `/whitelist`, por op, ou por um comando do mod. |

Quatro dos dezassete pacotes desaparecem. Isso é o argumento mais forte a favor
desta via: o código que era autenticação, convites e "quem estou eu" já não é
necessário porque o Minecraft faz isso.

### Persistência

Com mod no servidor, a persistência é a do Minecraft: um bloco com block entity
num save do mundo, ou `PersistentState`/`SavedData` da dimensão. Nada de um
ficheiro novo, e os ecrãs aparecem quando o mundo abre. Hoje `ScreenWorld` é um
`LinkedHashMap` em memória do cliente — essa parte tem de mudar de qualquer
forma, porque com o canal do Minecraft passa a haver estado autoritativo no
servidor.

---

## 3. Permissões, no servidor

Com a resposta (e.3) — os ecrãs são do dono da sala — isto é quase trivial:

- O dono é o **dono do ecrã**, guardado no estado do servidor. Qualquer payload
  `Navigate`/`Control` traz um `id` de ecrã; o servidor procura o dono e só
  reenvia se o remetente for o dono. O cliente **também** deve recusar, porque
  um cliente com mod alterado não pode fazer o servidor errar.
- **Lista de domínios**: o dono mantém a lista no servidor, junto do ecrã.
  `Navigate` para fora da lista é recusado. Isto é onde entra a confirmação "X
  quer abrir este endereço", e o servidor é quem decide.
- **Limites**: `registerLarge` dá o teto. Falta o limite de **frequência** —
  `StatePayload` a cada 60 ticks é 3,3 por segundo por ecrã; o servidor precisa
  de um teto por jogador para que ninguém envie milhares por segundo.
- **Op** para o mod: opcional. Quem é "dono" pode ser quem colocou o ecrã, sem
  nenhum comando. Se quiseres que só o dono do mundo possa colocar ecrãs, é um
  `requires(source, level == 2)`.

## 4. Como testar com dois clientes na mesma máquina

Ao contrário do que a primeira hipótese exigia, **não há segundo processo para
arrancar**. O `runClient` do Gradle e o perfil do Modrinth são dois clientes
independentes. Ligar ambos ao mesmo servidor (o integrado do primeiro, ou um
dedicado) e ver o segundo receber os payloads.

Cuidado: como as duas versões têm `breaks` mútuo, e o servidor dedicado passa a
precisar do mod, o perfil do Modrinth tem de levar **a versão multiplayer**, e o
`runClient` tem de arrancar com `environment: "*"` para o servidor integrado
carregar o mod. Um `runServer` dedicado é o caminho mais limpo para o primeiro
ensaio.

---

## 5. Comparação honesta

| | Mod no servidor do Minecraft | Servidor TCP à parte (Java) |
|---|---|---|
| Auth | **Já existe.** Contas do Minecraft, whitelist, op | Código de convite + TLS a escrever do zero |
| Portas | **Nenhuma nova.** O 25565 já está aberto | Abrir e proteger uma porta, firewall, upkeep |
| Quem pode navegar | O dono do ecrã, decidido no servidor | O mesmo, mas o servidor é nosso |
| Clientes | Quem tem o mod | Qualquer cliente, mesmo sem mod no servidor |
| Persistência | A do mundo, de graça | Ficheiro nosso |
| CanSend | **Built-in.** O Minecraft recusa o pacote a quem não tem o mod | Inventário à mão |
| Limite de tamanho | **Built-in.** `registerLarge(…, int)` | A inventar |
| Esforço | Payloads + block entity + permissões | Servidor, TLS, convites, framing, deploy |
| Propriedade | O servidor é teu, decides tu | Serviço teu, com custos e manutenção |
| Requer mod no servidor | **Sim** | Não |
| Servidores públicos alheios | **Não funciona** | Funciona |

A última linha é a única perda real, e é exactamente o motivo de a versão
local existir.

---

## 6. Recomendação

**Mod no servidor do Minecraft.** Sem hesitar.

Três razões, por ordem de peso:

1. **Apaga o requisito mais caro inteiro.** Convite, TLS e portas são o
   trabalho grande do plano original, e o Minecraft já os tem. O que sobra é
   retransmitir mensagens pequenas — que é o que um servidor de statutory
   transmita.
2. **A API dá de graça as duas peças que eu tinha posto na lista de segurança.**
   `canSend` é o "tem o mod?", `registerLarge` é o limite de tamanho. Duas
   linhas do plano desaparecem sem código nosso.
3. **Dois clientes na mesma máquina são dois perfis normais.** Não há um
   servidor novo para arrancar, manter e fazer backup. Isso é o que torna o
   teste honesto em vez de um exercício deriteiro.

O que fica de fora: servidores públicos onde não tenhas o mod instalado. Aceito,
e a versão local `screenmine` cobre esse caso — é a razão de ela existir, não
um acidente.

**O preço a pagar, dito com todas as letras:** a multiplayer passa a exigir o
mod no servidor. Um amigo com um servidor público seu não pode usar. Aceito
porque a alternativa é escrever, manter e asegurar um serviço de rede — e esse
eé o caminho que a AGPL torna ainda mais pesado.

## 7. O que continua por decidir

- **O `place` é do dono ou de qualquer um?** (e.3 diz do dono; confirmo)
- **Persistência:** bloco com block entity, ou estado da dimensão? O bloco é
  mais visível e mais fácil de usar; o estado da dimensão não ocupa lugar no
  mundo. Recomendo o bloco.
- **O que acontece quando o dono sai?** O ecrã fica no mundo e passa a não ter
  dono — ninguém pode navegar. Ou desaparece. Recomendo ficar, e o próximo a
  pegar num ecrã sem dono não poder navegar até alguém o reclaim.
