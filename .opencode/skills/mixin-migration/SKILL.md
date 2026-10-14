---
name: mixin-migration
description: Migrar mixins, accessors e invokers para a versão destino. Usar quando há erros de mixin no arranque ou ao tocar em classes do Minecraft.
---
# Migração de mixins

## Antes de migrar
- Lista todos os mixins e para cada um: classe alvo, método alvo, tipo de injeção (`@Inject`, `@Redirect`, `@ModifyArg`, `@Accessor`...).
- Verifica se **ainda precisas** do mixin (muitas vezes a API passou a ter um evento/método público).

## Por mixin
1. Confirma que a classe alvo ainda existe e o nome/pacote (ver `api-verification`).
2. Confirma o **descritor do método alvo** (parâmetros e retorno). Mudou? Atualiza o `method = "..."`.
3. Reconfirma o ponto de injeção (`@At`) — o código à volta pode ter mudado.
4. Atualiza `@Accessor`/`@Invoker` com os nomes de campos/métodos reais.
5. Mantém `mixins.json` com o `compatibilityLevel` correto e a lista de mixins certa (client vs common).

## Sintomas
- `InvalidInjectionException`/`MixinApplyError` no arranque → alvo ou descritor errado.
- Mixin "não faz nada" → `@At` não encontra o ponto.
- Crash só em runtime → `@Redirect`/`@ModifyArg` com assinatura errada.

## Dicas
- `-Dmixin.debug.export=true` para exportar classes transformadas e inspecionar.
- Um mixin por mudança lógica; testa no `runClient` a seguir a cada um.
