---
name: code-review
description: Auto-revisão rápida antes de cada commit relevante: corretude, segurança, restos de debug e consistência. Usar antes de commit/push de mudanças significativas.
---
# Auto-revisão

Antes de commitar, lê o `git diff` e verifica:

## Corretude
- [ ] Faz o que deveria? Casos limite (null, vazio, thread, mundo a carregar/descarregar)?
- [ ] Recursos libertados (texturas, buffers, threads, listeners)?
- [ ] Sem chamadas de render/GL fora da thread certa?

## Port
- [ ] Nenhum rename automático ambíguo ficou por rever (`TODO(port)` listados)
- [ ] Nomes de API confirmados (ver `api-verification`)
- [ ] Nada hardcoded de versão que devia estar em `gradle.properties`

## Higiene
- [ ] Sem `System.out.println`/prints de debug esquecidos
- [ ] Sem código comentado em bloco
- [ ] Sem segredos, caminhos locais (`C:\Users\<user>\...`) ou tokens
- [ ] Sem ficheiros gerados no diff

## Mensagem
- [ ] Commit descreve o **porquê**; uma mudança lógica por commit

Se encontrares problemas, corrige primeiro e só depois commita.
