---
name: dependency-check
description: Verificar se as dependências (Fabric API, ModMenu, libs, outros mods) existem para a versão destino e decidir alternativas. Usar na fase 2 ou quando uma lib falha.
---
# Verificação de dependências

Para cada dependência (mod, lib Java, nativos):
1. Existe build para a **versão exata** de Minecraft destino? Verifica no Modrinth/CurseForge/Maven.
2. Qual o loader, a versão do Java e as dependências dela?
3. Está mantida? (último commit, issues recentes)
4. Licença compatível com o teu projeto?

## Se não existe
Por ordem de preferência:
1. Existe fork ou alternativa mantida → avalia.
2. Dá para atualizar tu próprio a lib (spike pequeno) → avalia custo.
3. Tornar a dependência **opcional** (carregar só se estiver presente).
4. Cortar a funcionalidade e registar em `progresso.md`.

## Boas práticas
- Fixa versões exatas em `gradle.properties`.
- Para libs com binários nativos, testa **em runtime**, não só em compilação.
- Regista no `progresso.md`: nome, versão, de onde veio, e porquê.
- Nunca copies jars para o repo; usa o Maven/`modImplementation`.
