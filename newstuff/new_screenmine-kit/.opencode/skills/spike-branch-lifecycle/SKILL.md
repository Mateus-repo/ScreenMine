---
name: spike-branch-lifecycle
description: Como fechar uma branch de spike (spike/codecs): absorver codecs.md e review.md no progresso.md, apagar os registos temporários e voltar à port/26.3 sem merge perigoso. Usar quando uma investigação acabou ou quando houver mais de um ficheiro de "estado".
---
# Ciclo de vida de uma branch de spike

`spike/codecs` nasce de `port/26.3` e volta por **fast-forward**. `codecs.md` e `review.md` são registos temporários: dizem-no no cabeçalho e a regra 11 do `AGENTS.md` proíbe um segundo documento de estado.

## Antes de fechar
1. `git fetch` e confirma que `port/26.3` **não andou** desde que a spike nasceu (`git merge-base --is-ancestor port/26.3 spike/codecs`). Se andou, **não faças `git merge`** com `main`/`spike/browser-input`: copia ficheiro a ficheiro. Entre `port/26.3` e `spike/codecs` o fast-forward é seguro; confirma com `git log --oneline port/26.3..spike/codecs`.
2. Faz uma tag de segurança (`backup/spike-codecs-pre`).
3. Corrida de fecho: tudo o que ficou "por testar" tem de estar testado ou declarado como não testado.

## Absorver (um commit `docs:` por ficheiro)
| De | Para o `progresso.md` |
|---|---|
| `codecs.md` §1b, §3–§3e | secção **C** (resultado) + link para a skill `codec-probe`; o resto da proveniência fica em 5–8 linhas |
| `codecs.md` §4–§6 | secção **A** (teclado) + skill `jcef-keyboard-input` |
| `codecs.md` §5b | **Decisões** (ordem de criação do browser, `pumpAgents`) |
| `review.md` tabela de estado | **Bugs conhecidos** (só os abertos: B3, B4–B6, B9–B10, C6, E4, E5) |
| `review.md` achados corrigidos | **Registo de sessões** (hash + 1 linha) |
Depois **apaga** `codecs.md` e `review.md` (como se fez com `PORTING-26.3.md`). Os detalhes vivem no git e nas skills.

## Limpar contradições ao absorver
- `progresso.md` ("Decisões tomadas", 2026-10-14) ainda diz "licença é MIT"; está errado: **AGPL-3.0-only**. Corrige.
- `progresso.md` mostra `cd C:\Users\<nome>\...`: troca por caminho relativo (`cd .\ScreenMine` ou "na raiz do repo").
- `README.md` já é o README do mod; remove do `AGENTS.md`/skills qualquer menção a "README do kit de skills".
- A tabela "Branches" fala em `spike/browser-input`: se já foi absorvida, diz.
- `src/.../spike/` (C6, código morto com a câmara subtraída duas vezes) — decide: apagar (o registo do spike está no git) ou deixar com aviso.

## Manter o `progresso.md` pequeno
Alvo: **≤ ~250 linhas** de leitura obrigatória. O que está fechado (corridas antigas, tabelas de migração de API já aplicadas) vai para `docs/historico/` com cabeçalho "**arquivo — não é fonte de verdade nem plano**". O `AGENTS.md` só manda ler o `progresso.md`.

## Depois
- Atualiza "Estado atual" e "Topologia das branches".
- `git push` da `port/26.3`; apaga a branch remota da spike só com OK do utilizador.
