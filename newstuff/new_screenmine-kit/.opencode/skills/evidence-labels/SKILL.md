---
name: evidence-labels
description: Etiquetar afirmações como verificadas, inferidas ou hipótese, e registar corridas em jogo. Usar sempre que afirmares uma causa, um estado ("resolvido", "funciona") ou escreveres no progresso.md.
---
# Etiquetas de evidência

Este projeto já perdeu corridas por tratar hipóteses como factos (a hipótese dos
codecs "sem flag = sem codec", o canal `document.title` que "devia funcionar").
Por isso tudo o que não é óbvio leva etiqueta.

## Etiquetas
- **[V] verificado** — li o código, corri `javap` no jar real, ou vi a linha de log (cita-a, com hora).
- **[I] inferido** — o raciocínio é sólido, mas não foi visto a correr.
- **[H] hipótese** — precisa de uma corrida para decidir. Diz qual.

## Regras
1. **Estático ≠ runtime.** Provar que ninguém liga `proprietary_codecs` não prova que os codecs faltam; só a sonda em jogo provou. Se a pergunta é sobre o que o browser/jogo faz, a prova é um log.
2. **"Resolvido" exige log.** Escreve o excerto e a hora: `23:48:30 agent report: vol=0, media=2, vvolume=0.00`. Sem isso, o estado é "corrigido, por provar".
3. **Dizer o que mudou de ideias.** Se corrigires algo que afirmaste antes, escreve "O que estava errado no que eu assumi" (como na secção C-bis do `progresso.md`). Não apagues o erro em silêncio.
4. **Não concluas por ausência de evidência** sem dizer que é ausência. "Não encontrei nenhuma flag" ≠ "não existe".
5. **Quem fez o quê:** se não consegues executar algo (correr o jogo), escreve-o. Não preenchas tabelas de resultados com palpites.
6. **Cada correção abre a hipótese de outro bug.** Marca `[H]` nos efeitos secundários possíveis até uma corrida os excluir.

## Formato de corrida (para a tabela "Estado dos testes em jogo")
`| Corrida N (hora) | resultado em 1 linha | o que ensina |`

Mais, abaixo, só se for preciso: commit testado, config usada, linhas de log que provam.

## Exemplo
- Mau: "O agente não é injectado porque o `onLoadEnd` nunca dispara."
- Bom: "[V] O log de 12:41 não tem nenhuma linha `ONLOADEND`. [V] `createBrowser` chama `createImmediately()` (bytecode), logo o load começa antes de os handlers existirem. [I] É essa a corrida que perdemos. Prova: próxima corrida tem de mostrar `ONLOADEND`."
