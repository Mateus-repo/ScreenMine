---
name: progress-tracking
description: Como manter o progresso.md atualizado durante o port. Usar no início e no fim de cada sessão e depois de cada passo concluído.
---
# Progress tracking

## Início da sessão
1. Lê `progresso.md` inteiro.
2. Resume ao utilizador em 3-5 linhas: onde ficámos, o que falta, o próximo passo.

## Durante
Atualiza imediatamente quando:
- Uma fase/passo fica concluído (marca `[x]`).
- Descobres uma API que mudou (tabela "Descobertas").
- Tomas uma decisão (secção "Decisões", com o motivo).
- Aparece um risco novo ou um risco é resolvido.

## Fim da sessão
- Atualiza "Estado atual" (fase, compila?, corre?, último commit).
- Acrescenta uma linha no "Registo de sessões": data, feito, falta.
- Faz commit `docs: update progress`.

## Regras
- Factual e curto. Nada de "acho que" sem marcar como hipótese.
- "Compila" e "corre no jogo" são coisas diferentes — regista as duas.
- Se algo falhou, regista **o que** falhou e **porquê**, para ninguém repetir.
