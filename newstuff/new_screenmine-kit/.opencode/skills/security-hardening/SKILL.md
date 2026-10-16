---
name: security-hardening
description: Lista ordenada de riscos de segurança do ScreenMine para antes de publicar (protocolo sem autenticação, allowlist de URLs, file:, cookies partilhados, mensagens forjáveis da página). Usar ao preparar alfa/release ou ao mexer em rede e navegação.
---
# Segurança antes de publicar

Estado (`review.md` E5 + `progresso.md`): aceitável para LAN pessoal, **bloqueador para publicação**.

## Ordem de trabalho
| # | Risco | Gravidade | O que fazer |
|---|---|---|---|
| 1 | **Protocolo sem autenticação** — `hello` leva só `name` + `ver`; quem alcance a porta 23481 pode injetar `navigate` nos ecrãs de outros | alta | Segredo partilhado/token por sessão, HMAC por pacote ou handshake com desafio; rate limit; `navigate` só de quem tem permissão |
| 2 | **`file:` não bloqueado** | média | Bloquear `file:`, `chrome:`, `devtools:`, `javascript:` em `loadURL` e em redirects (uma linha) |
| 3 | **Browser sem allowlist** | média | Lista opcional de esquemas/hosts nas definições; por omissão só `http(s)` |
| 4 | **Cookies partilhados** (`CefRequestContext.getGlobalContext()`): entrar no YouTube num ecrã entra em todos | média | Documentar como feature; considerar contexto por ecrã ou opção "isolar sessão" |
| 5 | **Mensagens da página forjáveis** (`SMX\|`, `SMA\|`) | baixa | Parse defensivo, limites, nunca executar nada com base no payload (`page-agent-channel`) |
| 6 | **URLs vindos da rede** | média | Validar antes de `loadURL`; `textureIdFor` já sanitiza o `streamId` **[V]** |
| 7 | **`inbound` sem limite / writer por tentativa** | baixa | Fila limitada; reutilizar a writer thread |
| 8 | **Binários descarregados em runtime** (se um dia se usar WaterMedia: yt-dlp, botguard; já é o caso do Chromium via `mcef-modern`) | média | Dizê-lo no README; fixar versões; só dependências que o utilizador instala (`external-decoder-research`) |

## Regras
- Cada item corrigido tem teste manual descrito em `playtest-handoff` (tentar a ação proibida e ver o log).
- Não vendas proteção que não existe: `[V]`/`[I]`/`[H]` (`evidence-labels`).
- O **README** tem de dizer a verdade sobre o estado de segurança até o item 1 estar feito.
- Dados de servidores pessoais nunca vão para o repo (`repo-hygiene-privacy`).
- Licença/atribuição à parte: `NOTICE` e `README` ("Credits and licence"). O pedido ao autor do CoScreen (`-only` vs `-or-later`) continua por fazer.
