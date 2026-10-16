---
name: progress-tracking
description: Como manter o progresso.md curto, factual e útil. Usar no início e no fim de cada sessão e depois de cada passo concluído.
---
# Progress tracking

## Início da sessão
1. Lê o `progresso.md`: "Estado atual", "Riscos", "O que falta" e o último "Registo de sessões".
2. Resume ao utilizador em 3-5 linhas: onde ficámos, o que falta, o próximo passo.
3. Se há `codecs.md`/`review.md` por absorver, diz-o (`spike-branch-lifecycle`).

## Durante
Atualiza imediatamente quando:
- Um passo fica concluído (marca `[x]`).
- Descobres uma API que mudou (tabela "Descobertas": antes → agora, confirmado com `javap`).
- Tomas uma decisão (secção "Decisões", com data e motivo).
- Um risco aparece ou é resolvido.
- Há uma corrida em jogo: linha "Corrida N" (`evidence-labels`).

## Fim da sessão
- Atualiza "Estado atual" (fase, compila?, **corre no jogo?**, último commit).
- Acrescenta no "Registo de sessões": `data · feito · falta · commits`. (A secção existe e está **vazia**: preenche-a.)
- Commit `docs: update progress`.

## Regras
- Factual e curto. Hipóteses marcadas `[H]`; nada de "acho que" solto.
- "Compila" ≠ "corre no jogo". Regista as duas.
- Se algo falhou, regista **o que** falhou e **porquê**, para ninguém repetir.
- **Tamanho:** o ficheiro de leitura obrigatória deve caber em ~250 linhas. Corridas antigas e tabelas de migração já aplicadas vão para `docs/historico/` com cabeçalho "arquivo — não é fonte de verdade". Um só plano; o arquivo não conta.
- **Privacidade:** nunca colar logs em bruto, hostnames, IPs, tokens ou caminhos com o nome de utilizador (`repo-hygiene-privacy`).
- Quando corrigires algo que afirmaste antes, escreve o que estava errado.
