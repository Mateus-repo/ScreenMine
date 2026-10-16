# AGENTS.md — ScreenMine (port Fabric 1.21.4 → 26.3)

## Projeto
- **Mod:** ScreenMine (antes CoScreen) · Fabric, cliente-only · Minecraft **26.3** · Java **25**
- **Repo de trabalho (PÚBLICO):** https://github.com/Mateus-repo/ScreenMine
- **Referência (só leitura, NUNCA alterar):** pasta irmã `../CoScreen-main`.
  Se o caminho for outro, fica em `AGENTS.local.md` (ignorado pelo git). **Nunca
  escrevas caminhos absolutos com o nome de utilizador num ficheiro versionado.**
- **Licença:** AGPL-3.0-only (obra derivada do CoScreen, ver `NOTICE`). Não escrevas "MIT" em lado nenhum.
- **Objetivo:** browser Chromium embebido (`mcef-modern`) desenhado como um ecrã
  plano no mundo — criar, mover, redimensionar, navegar e ver vídeo dentro do
  jogo. Tem de funcionar: (1) ecrã no mundo com a página real, (2) control mode
  com rato/teclas/scroll sem partir o WASD, (3) os 9 ecrãs de menu, (4) áudio com
  falloff por distância, (5) rede/sincronização de playback como na 1.21.4.

### Contexto específico deste port
- A 26.3 é **não ofuscada**: Yarn morreu, usam-se os nomes Mojang.
- A 26.3 trocou **GLFW por SDL3**. Não existe `org.lwjgl.glfw`.
- O render state saiu do `RenderSystem` para `RenderPipeline`. `Tessellator` /
  `BufferRenderer` não existem.
- **MCEF do CinemaMod morreu em 1.21.4.** Usamos `mcef-modern` (DimasKama). Não voltar ao `mcef` original.
- O Chromium do `mcef-modern` **não tem H.264, AAC nem HEVC** (provado em jogo,
  2026-10-15). Só VP9/AV1/Opus/Vorbis. Não gastes tempo a reabrir isto — ver skill `codec-probe`.
- **Nunca faças `git merge`** entre `port/26.3`, `main` e `spike/browser-input`
  (apaga o mod). Traz código ficheiro a ficheiro com `git checkout <branch> -- <ficheiro>`.
  Detalhe em `progresso.md` → "Topologia das branches".

## Ambiente
- **Windows + PowerShell.** `.\gradlew.bat build`, `.\gradlew.bat runClient`, log em `run\logs\latest.log`.
- **O agente não consegue correr o cliente do Minecraft** (precisa de ecrã e de uma pessoa).
  Tudo o que depende do jogo passa por um pedido de corrida — skill `playtest-handoff`.
  **Nunca escrevas "funciona" sem o log de uma corrida real.**
- O repo é público e o auto-commit faz push. Ativa o hook uma vez por clone:
  `git config core.hooksPath .githooks` (corre `.opencode/lib/guard-cli.js`).

## Comunicação
- Responde em **português europeu (PT-PT)**, direto e sem enrolar.
- Código, identificadores e commits em **inglês** (Conventional Commits).
- Se não tens certeza, diz. Não inventes. Se o utilizador te corrigir, regista a correção (ver `evidence-labels`).
- Código pedido ao utilizador: **ficheiros completos e prontos a copiar**, não diffs soltos.

## Onde está cada coisa
| Ficheiro | Papel |
|---|---|
| `progresso.md` | **Único** estado/plano. Lê no início, atualiza no fim de cada passo. |
| `codecs.md`, `review.md` | Registos **temporários** da branch `spike/codecs`. Não são planos. Absorver no `progresso.md` e apagar (skill `spike-branch-lifecycle`). |
| `NOTICE`, `LICENSE` | AGPL-3.0 + atribuição ao CoScreen (Ma3stro567) e nota LGPL do `mcef-modern`. |
| `.opencode/skills/` | Skills. `.opencode/lib/guard.js` = guarda de privacidade. |

## Regras de ouro
1. **Início de sessão:** lê `progresso.md` (a secção "Estado atual" e "O que falta"). **Fim de cada passo:** atualiza-o.
2. **Nunca adivinhes nomes de API.** Confirma contra o jar real (`api-verification`).
3. **Sem rename em massa às cegas.** Lista o impacto; o que mudou de significado trata-se à mão.
4. **Compila depois de cada ficheiro ou grupo pequeno.**
5. **Compilar ≠ funcionar. Estático ≠ runtime.** Ler código/flags prova o que o código faz, não o que o browser faz. Só um log de corrida fecha uma hipótese de runtime.
6. **Etiqueta o que afirmas:** `[V]` verificado (jar, `javap`, log com hora), `[I]` inferido, `[H]` hipótese. Nunca apresentes `[I]`/`[H]` como facto (`evidence-labels`).
7. **Incerteza grande → diagnóstico/spike pequeno primeiro.** Instrumenta **todas** as hipóteses concorrentes na mesma corrida, para uma corrida chegar para decidir.
8. **Cada correção cria a hipótese de outro bug.** Depois de corrigir, pede a corrida que o prova; não declares "resolvido" sem ela.
9. **Threads:** render thread, CEF UI thread e threads de rede não partilham estado sem `volatile`/fila. Nada de GL fora da render thread (`thread-safety-jcef`).
10. **Dados privados nunca entram no repo:** hostnames, IPs, tokens, nomes de utilizador do SO, logs em bruto. Usa `<my-server>`, `<user>`. Se algo vazou, **não reescrevas o histórico sem OK explícito** (`repo-hygiene-privacy`).
11. **Um único documento de estado.** Outro ficheiro de plano passa a ser lido como fonte de verdade — absorve-o ou apaga-o.
12. **Um commit = uma mudança lógica** (`auto-commit`). Nunca `push --force`.
13. **Versões de dependências:** confirma no Maven/Modrinth. Não inventes números.
14. **Não mexas na referência**, nem fora do repo de trabalho.

## Qual skill usar
| Se a tarefa é… | Usa |
|---|---|
| Vídeo/áudio não toca num sítio | `codec-probe` |
| Teclas, edição de texto, atalhos Ctrl/Alt, seleção com o rato | `jcef-keyboard-input` (+ `input-and-lwjgl`) |
| Agente JS na página, mute/volume, estado de playback, `SMX\|`/`SMA\|` | `page-agent-channel` |
| Crash, fuga de textura, bug intermitente, estado partilhado | `thread-safety-jcef` + `crash-debugging` |
| Preciso que o utilizador corra o jogo | `playtest-handoff` |
| Vou afirmar algo sobre causa/estado | `evidence-labels` |
| Antes de commit/push | `repo-hygiene-privacy` + `code-review` |
| Preparar publicação/alfa | `security-hardening` + `release-checklist` |
| Fechar/absorver uma branch de spike | `spike-branch-lifecycle` |
| Migração de API | `api-verification`, `mixin-migration`, `render-gui-migration`, `fabric-loom-setup`, `dependency-check` |
| Fluxo geral do port | `mc-port-workflow`, `build-and-test`, `progress-tracking`, `git-github`, `auto-commit` |

## Skills disponíveis (`.opencode/skills/`)
| Skill | Para quê |
|---|---|
| `auto-commit` | Quando e como fazer commit (e push) |
| `git-github` | Branches, remote, tags, releases |
| `progress-tracking` | Como manter o `progresso.md` curto e útil |
| `mc-port-workflow` | Roteiro completo do port |
| `fabric-loom-setup` | Gradle, Loom, versões, `fabric.mod.json` |
| `dependency-check` | Verificar libs/mods dependentes |
| `api-verification` | Confirmar nomes reais de classes/métodos |
| `mixin-migration` | Migrar mixins e accessors |
| `render-gui-migration` | Render e GUI entre versões |
| `input-and-lwjgl` | Teclado/rato/janela (GLFW/SDL) |
| `build-and-test` | Build, runClient, testes |
| `crash-debugging` | Ler crash reports e logs |
| `code-review` | Auto-revisão antes de commit |
| `release-checklist` | Publicar o jar |
| **`evidence-labels`** | Etiquetas `[V]/[I]/[H]` e como registar corridas |
| **`playtest-handoff`** | Pedir ao utilizador uma corrida em jogo, com critério de sucesso |
| **`codec-probe`** | Diagnosticar codecs/contentores/erros de vídeo |
| **`jcef-keyboard-input`** | Teclado e rato no CEF offscreen |
| **`page-agent-channel`** | Canais Java↔JS do agente de página |
| **`thread-safety-jcef`** | Mapa de threads e ciclo de vida de texturas/browsers |
| **`repo-hygiene-privacy`** | Repo público: segredos, hostnames, caminhos, logs |
| **`security-hardening`** | Auth do protocolo, allowlist, `file:`, cookies |
| **`spike-branch-lifecycle`** | Absorver `codecs.md`/`review.md` e fechar a branch |

## Definition of done (por tarefa)
- [ ] Compila sem erros nem avisos novos
- [ ] Testado no `runClient` **por uma pessoa**, com log colado e registado como "Corrida N" — ou dito claramente que não foi testado
- [ ] Afirmações de causa com etiqueta `[V]/[I]/[H]`
- [ ] `progresso.md` atualizado
- [ ] Guarda de privacidade passou (hook/plugin) e `git diff` sem caminhos locais, hostnames ou tokens
- [ ] Commit com mensagem clara
