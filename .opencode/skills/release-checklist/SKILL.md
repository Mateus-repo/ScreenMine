---
name: release-checklist
description: Checklist para publicar uma versão do mod portado (jar, metadados, licença, changelog, tag). Usar quando o port está pronto para release.
---
# Release

## Antes
- [ ] `./gradlew build` limpo e `runClient` testado (ver `build-and-test`)
- [ ] `progresso.md` sem riscos críticos abertos
- [ ] Sem `TODO(port)` críticos

## Metadados
- [ ] `fabric.mod.json`: id, nome, versão, descrição, autores, ícone, `depends` com intervalo certo de Minecraft
- [ ] `mod_version` em `gradle.properties` e nome do jar coerentes (`mod-<versao>+<mc>.jar`)
- [ ] Licença presente e **créditos ao autor original** do mod portado, respeitando a licença dele
- [ ] README com: o que faz, versões suportadas, requisitos, como instalar

## Publicação
- [ ] Changelog
- [ ] Tag (`v<versao>+<mc>`) e release no GitHub com o jar
- [ ] Modrinth/CurseForge: loader, versão de Minecraft, dependências, lado (client/server)
- [ ] Se o mod é port de outro autor, confirma que a licença permite redistribuição e pede/dá permissão e créditos quando necessário

## Depois
- Regista a release em `progresso.md` e abre issues para o que ficou por fazer.
