# progresso.md — ScreenMine · 1.21.4 → 26.3

> **Documento curto de propósito.** Estado actual, bugs abertos, como testar e
> próximo passo. Tudo o histórico — runs antigas, decisões datadas, a tabela de
> APIs confirmadas com `javap` — está em `docs/archive/`.
>
> Detalhes de investigação que ainda estão a correr: `codecs.md` e `review.md`
> (registos temporários da branch `spike/codecs`; absorver e apagar antes de
> fechar a branch).

**Última actualização:** 2026-10-16 · **Branch:** `spike/codecs` · **Compila:** sim, 0 erros

---

## Estado actual

O port **corre em jogo num servidor multiplayer**. O que está provado com log:

| | Estado | Prova |
|---|---|---|
| Ecrã desenhado no mundo | ✅ | `run/screenshots/2026-10-15_12.10.33.png` (corrida 3) |
| YouTube reproduz no mundo | ✅ | `agent report: media=2 … href=…/watch?v=…` (corrida 7) |
| **`N` muta** | ✅ | `23:48:30 agent report: vol=0, media=2, vvolume=0.00` |
| Queda de volume por distância | ⬜ por testar | código existe, nunca medido |
| Mixins instalam sem partir o arranque | ✅ | arranque limpo na corrida 7 |
| Agente entra em iframes | ✅ | `agent report: … top=iframe` |
| Teclado no overlay (`B`) | ⚠️ parcial | letras OK; `Ctrl+*`/`Alt+seta` OK por API; `Backspace`/`Enter` falhavam → ver bug C |
| Teclado no mundo | ⛔ **bloqueado** | ver bug A |
| Roda no mundo | ⬜ por testar | mixins escritos, nunca testados |
| Menu com lista de ecrãs | ⬜ por testar | `108c275` |
| Codecs H.264/AAC/HEVC | ✅ causa confirmada | `codecs.md` §1b |

**Fases**

- [x] 0–4 · reconhecimento, build, dependências, spike de render, GUI
- [x] 5 · input do overlay
- [x] 6 · render no mundo — **provado em jogo**
- [~] 7 · teste no cliente — a correr; falta o teclado
- [ ] 8 · polimento e release

---

## Bugs abertos

### A) Teclado de edição no mundo — **bloqueia mais trabalho** `[PRIORIDADE 1]`

`Backspace`, `Enter`, `Ctrl+A/C/V`, `Alt+seta` e escrever num campo de texto
**não funcionam no mundo**. No overlay ainda não foram testados.

**Porquê, e é uma só causa:**

`isEditing()` é lido do payload `SM|` que a página escreve em
`document.title`. O log da corrida 7 mostra **zero recibos `SM|`**. Logo
`isEditing()` é sempre `false`, e `ScreenMineClient.onWorldKey()` devolve
`false` — propositadamente, para o WASD continuar a andar. **Os mixins estão
instalados e recusan a tecla de propósito.**

O canal é frágil por natureza: a página também escreve `document.title`
(é o que o YouTube faz), e o guard antigo do agente perdia essa corrida
(`376d7d4` tentou corrigir e não chegou).

**Próximo passo:** `5ced97f` já conta *todos* os `onTitleChange`, não só os
reconhecidos. Uma corrida diz se o JCEF reporta sequer o título. Depois, a
correcção é mover o payload para `console` (`onConsoleMessage`), que já está
provado a funcionar para a sonda de codecs.

### C) `Backspace`/`Enter` no overlay — causa encontrada, correção por provar

**Sintoma:** no overlay (`B`), letras, clique, scroll, `Ctrl+A/C/V/Z/Y` e
`Alt+seta` funcionam; `Backspace` e `Enter` não. Testado pelo utilizador.

**Causa, verificada nos jars e não deduzida:**

1. `KeyEvent.key()` em 26.3 devolve um **SDL scancode**, não um código GLFW nem
   um Virtual-Key. Confirmado com `javap -constants` em `InputConstants`:
   `KEY_A=4`, `KEY_RETURN=40`, `KEY_BACKSPACE=42`.
2. O SDL3 só entrega caracteres **imprimíveis** no evento de texto. O GLFW
   (1.21.4) também entregava `\b` e `\r`. Logo, `Backspace`/`Enter` já nunca
   chegam como `charTyped` — e o `charTyped` é **o único caminho que se sabe
   funcionar** (é por ele que as letras entram).
3. Bónus, pela mesma via: a tabela `toAwtKeyCode` do mcef-modern mapeia
   `KEY_RETURN` para **10** (o `VK_ENTER` do AWT, que é o enter do teclado
   numérico) em vez de **13** (`VK_RETURN`). `INSERT` e `DELETE` também estão
   errados. `tools/ProbeKeycodes.java` executa essa tabela e mostra o resultado.

**Correção:** `BrowserInstance.sendControlCharacter()` envia o carácter de
controlo pelo caminho `charTyped`, que é o comprovadamente funcional, como
**extras** — o evento de tecla bruto continua a ser enviado. Cobre `Backspace`
(8), `Enter` (13), `Tab` (9), `Delete` (127). Log: `SYNTH CHAR key=… cp=…`.

**Previsão que o teste tem de confirmar:** o log da página de teste vai mostrar
`INPUT`/`KEYPRESS` com `data` = `'\b'` ou `'\r'`, mas **sem `KEYDOWN`**. Isso
provaria que o caminho de tecla bruto não chega ao Chromium, e que é por isso
que as setas, `Home`/`End` e `PageUp`/`PageDown` provavelmente também não
funcionam — caso que fica para o bug A resolver pelo mesmo caminho.

**Estado:** por testar. `9a0f...` (ver `git log`).

### B) ~~Tecla `N`~~ — **RESOLVIDO** 2026-10-16

Muta mesmo, com prova no log. Resolvido pela mudança que injectou o agente em
**todos** os frames (`1e3a570`): o vídeo do YouTube vive num documento que o
agente não alcançava. Ver `review.md` §A2.

### C) Vídeo noutros sítios — **causa confirmada, solução do teu lado** `[PRIORIDADE 2]`

O Chromium do `mcef-modern` **não tem H.264, nem AAC, nem HEVC** — medido em
jogo, não inferido. Só VP9, AV1, Opus, Vorbis.

**O teu Jellyfin tem caminho** e é barato: o teu ficheiro é **AV1 + AAC**, o mod
toca AV1 e não toca AAC, por isso **só o áudio precisa de transcodificação** e
o vídeo faz direct-play. Jellyfin 10.11.3 tem `libsvtav1`, `libopus` e
`libvpx-vp9`. Passos em `codecs.md` §1b.

**TikTok, Twitch e MedalTV não têm solução** sem uma build própria do JCEF.
Pesquisa registada em `codecs.md` §3e.

### D) UX por fazer

- Ecrã colocado noutra dimensão fica inalcançável (não há como mover sem o ver).
- `MenuStreams` e `MenuInvites` truncam sem avisar; sem scroll.
- `N` sem ecrã apontado não faz **nem** diz nada, ao contrário do `B`.

---

## Como testar

```
cd <repo>
.\gradlew.bat runClient
```

Installado à mão (perfil `Fabric 26.2 (1)` do Modrinth, que tem o mcef-modern
certo):

```
build\libs\screenmine-1.0.0.jar  ->  <perfil>\mods\
<perfil>\config\screenmine.properties  ->  debug.codecs=1
```

> Se houver outro jar com `id: screenmine` na pasta, o Fabric não arranca. Renomeia
> o antigo para `.disabled`.

**Depois da corrida:**

```powershell
Select-String "SM\||onTitleChange|agent report|INJECT|GAIN|Mixin|ERROR" `
  "<perfil>\logs\latest.log"
```

`SM| channel alive` é a linha que decide o bug A.

---

## Próximo passo

1. **Correr** e ver `onTitleChange` — decide o bug A.
2. **Mover o payload `SM|` para `console`**, com filtro por prefixo e sem
   registar conteúdo da página.
3. **Alargar a sonda**: pares `AV1+AAC` e `VP9+Opus`, e `video.error.code`.
   Diz se o alvo de transcodificação do Jellyfin resulta antes de mexeres nele.
4. **Testar** o que está escrito e nunca medido: overlay, `Ctrl+*`, roda, menu.

---

## Riscos

| Risco | Estado |
|---|---|
| Ordem de desenho vs. nuvens | aceite — `END_MAIN` corre antes das nuvens; numa tela com neve a imagem fica briefly velada |
| Codecs proprietários | **sem solução dentro do mod**; resolvido por transcodificação no servidor |
| Auth do protocolo | **aberta.** `hello` leva só `name`+`ver`. Aceitável numa LAN, bloqueador para publicar |
| Cookies partilhados entre ecrãs | conhecido — `CefRequestContext.getGlobalContext()`. Útil, e é fuga de privacidade |
| Licença | **AGPL-3.0-only**, obra derivada do CoScreen. `-only` vs `-or-later` por esclarecer com o autor |

## Repositório público — higiene

- Hostname do servidor e caminhos com o nome de utilizador: **removidos** dos
  ficheiros versionados. **Continuam em commits antigos**, alcançáveis por SHA.
- `docs/archive/`, `codecs.md`, `review.md` ainda por absorver.

---

## Registo de sessões

| Data | O que se fez | O que se provou |
|---|---|---|
| 2026-10-14 | Reconhecimento, spike de render, migração de GUI | A API de render compila — **mas nunca desenhou** |
| 2026-10-15 | Corridas 1–6 | Render no mundo, depois as causas de A e B |
| 2026-10-15 | Revisão de 5 passagens, depois 8 correcções | `review.md` |
| 2026-10-16 | Corrida 7 em servidor multiplayer | **Mute resolvido e provado.** Bug A bloqueado pelo canal `SM|` |
| 2026-10-16 | Limpeza: `newstuff/`, licença, caminhos, documento curto | este ficheiro |

---

## Ficheiros

| Ficheiro | Papel |
|---|---|
| `progresso.md` | **este** — estado, bugs, próximo passo |
| `docs/archive/` | histórico: runs antigas, decisões datadas, APIs confirmadas |
| `codecs.md` | investigação dos codecs (temporário, branch `spike/codecs`) |
| `review.md` | revisão de código de 2026-10-15 (temporário) |
| `AGENTS.md` | regras do projecto |
| `NOTICE`, `LICENSE` | AGPL-3.0 + atribuição ao CoScreen |